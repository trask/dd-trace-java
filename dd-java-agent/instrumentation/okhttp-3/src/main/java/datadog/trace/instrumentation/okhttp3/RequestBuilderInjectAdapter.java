package datadog.trace.instrumentation.okhttp3;

import datadog.trace.instrumentation.api.Propagation;
import okhttp3.Request;

/**
 * Helper class to inject span context into request headers.
 *
 * @author Pavol Loffay
 */
public class RequestBuilderInjectAdapter implements Propagation.Setter<Request.Builder> {

  public static final RequestBuilderInjectAdapter SETTER = new RequestBuilderInjectAdapter();

  @Override
  public void set(final Request.Builder carrier, final String key, final String value) {
    carrier.addHeader(key, value);
  }
}
