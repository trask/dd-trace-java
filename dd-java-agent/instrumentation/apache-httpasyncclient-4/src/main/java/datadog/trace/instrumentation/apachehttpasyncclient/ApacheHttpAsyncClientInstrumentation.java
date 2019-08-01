package datadog.trace.instrumentation.apachehttpasyncclient;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientInstrumentation.HttpHeadersSetter.SETTER;
import static datadog.trace.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.api.AgentTracer.startSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HttpContext;

@AutoService(Instrumenter.class)
public class ApacheHttpAsyncClientInstrumentation extends Instrumenter.Default {

  public ApacheHttpAsyncClientInstrumentation() {
    super("httpasyncclient", "apache-httpasyncclient");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return safeHasSuperType(named("org.apache.http.nio.client.HttpAsyncClient"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      getClass().getName() + "$HttpHeadersSetter",
      getClass().getName() + "$DelegatingRequestProducer",
      getClass().getName() + "$TraceContinuedFutureCallback",
      "datadog.trace.agent.decorator.BaseDecorator",
      "datadog.trace.agent.decorator.ClientDecorator",
      "datadog.trace.agent.decorator.HttpClientDecorator",
      packageName + ".ApacheHttpAsyncClientDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(4))
            .and(takesArgument(0, named("org.apache.http.nio.protocol.HttpAsyncRequestProducer")))
            .and(takesArgument(1, named("org.apache.http.nio.protocol.HttpAsyncResponseConsumer")))
            .and(takesArgument(2, named("org.apache.http.protocol.HttpContext")))
            .and(takesArgument(3, named("org.apache.http.concurrent.FutureCallback"))),
        ClientAdvice.class.getName());
  }

  public static class ClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan methodEnter(
        @Advice.Argument(value = 0, readOnly = false) HttpAsyncRequestProducer requestProducer,
        @Advice.Argument(2) final HttpContext context,
        @Advice.Argument(value = 3, readOnly = false) FutureCallback<?> futureCallback) {

      final TraceScope.Continuation parentContinuation = propagate().capture();
      final AgentSpan clientSpan = startSpan(DECORATE);

      requestProducer = new DelegatingRequestProducer(clientSpan, requestProducer);
      futureCallback =
          new TraceContinuedFutureCallback(parentContinuation, clientSpan, context, futureCallback);

      return clientSpan;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentSpan span, @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        DECORATE.onError(span, throwable);
        span.finish();
      }
    }
  }

  public static class DelegatingRequestProducer implements HttpAsyncRequestProducer {
    final AgentSpan span;
    final HttpAsyncRequestProducer delegate;

    public DelegatingRequestProducer(
        final AgentSpan span, final HttpAsyncRequestProducer delegate) {
      this.span = span;
      this.delegate = delegate;
    }

    @Override
    public HttpHost getTarget() {
      return delegate.getTarget();
    }

    @Override
    public HttpRequest generateRequest() throws IOException, HttpException {
      final HttpRequest request = delegate.generateRequest();
      DECORATE.onRequest(span, request);
      propagate().inject(span, request, SETTER);
      return request;
    }

    @Override
    public void produceContent(final ContentEncoder encoder, final IOControl ioctrl)
        throws IOException {
      delegate.produceContent(encoder, ioctrl);
    }

    @Override
    public void requestCompleted(final HttpContext context) {
      delegate.requestCompleted(context);
    }

    @Override
    public void failed(final Exception ex) {
      delegate.failed(ex);
    }

    @Override
    public boolean isRepeatable() {
      return delegate.isRepeatable();
    }

    @Override
    public void resetRequest() throws IOException {
      delegate.resetRequest();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  public static class TraceContinuedFutureCallback<T> implements FutureCallback<T> {
    private final TraceScope.Continuation parentContinuation;
    private final AgentSpan clientSpan;
    private final HttpContext context;
    private final FutureCallback<T> delegate;

    public TraceContinuedFutureCallback(
        final TraceScope.Continuation parentContinuation,
        final AgentSpan clientSpan,
        final HttpContext context,
        final FutureCallback<T> delegate) {
      this.parentContinuation = parentContinuation;
      this.clientSpan = clientSpan;
      this.context = context;
      // Note: this can be null in real life, so we have to handle this carefully
      this.delegate = delegate;
    }

    @Override
    public void completed(final T result) {
      DECORATE.onResponse(clientSpan, context);
      DECORATE.beforeFinish(clientSpan);
      clientSpan.finish(); // Finish span before calling delegate

      if (parentContinuation == null) {
        completeDelegate(result);
      } else {
        try (final TraceScope scope = parentContinuation.activate()) {
          scope.setAsyncPropagation(true);
          completeDelegate(result);
        }
      }
    }

    @Override
    public void failed(final Exception ex) {
      DECORATE.onResponse(clientSpan, context);
      DECORATE.onError(clientSpan, ex);
      clientSpan.finish(); // Finish span before calling delegate

      if (parentContinuation == null) {
        failDelegate(ex);
      } else {
        try (final TraceScope scope = parentContinuation.activate()) {
          scope.setAsyncPropagation(true);
          failDelegate(ex);
        }
      }
    }

    @Override
    public void cancelled() {
      DECORATE.onResponse(clientSpan, context);
      clientSpan.finish(); // Finish span before calling delegate

      if (parentContinuation == null) {
        cancelDelegate();
      } else {
        try (final TraceScope scope = parentContinuation.activate()) {
          scope.setAsyncPropagation(true);
          cancelDelegate();
        }
      }
    }

    private void completeDelegate(final T result) {
      if (delegate != null) {
        delegate.completed(result);
      }
    }

    private void failDelegate(final Exception ex) {
      if (delegate != null) {
        delegate.failed(ex);
      }
    }

    private void cancelDelegate() {
      if (delegate != null) {
        delegate.cancelled();
      }
    }
  }

  public static class HttpHeadersSetter
      implements datadog.trace.instrumentation.api.Propagation.Setter<org.apache.http.HttpRequest> {
    public static HttpHeadersSetter SETTER = new HttpHeadersSetter();

    @Override
    public void set(final HttpRequest carrier, final String key, final String value) {
      carrier.setHeader(key, value);
    }
  }
}
