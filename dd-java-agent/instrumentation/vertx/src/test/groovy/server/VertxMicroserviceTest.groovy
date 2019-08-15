package server

import datadog.opentracing.DDSpan
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import io.opentracing.tag.Tags
import io.reactivex.Single
import io.reactivex.functions.Consumer
import io.vertx.core.DeploymentOptions
import io.vertx.core.Future
import io.vertx.core.VertxOptions
import io.vertx.reactivex.core.AbstractVerticle
import io.vertx.reactivex.core.Vertx
import io.vertx.reactivex.ext.web.Route
import io.vertx.reactivex.ext.web.Router
import io.vertx.reactivex.ext.web.client.WebClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.CompletableFuture

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

/**
 * This test sets up the following architecture:
 *
 * External Client (OkHttp)--> Vert.x Server -(vertx WebClient)--> Internal HTTP Server
 *
 * Each test defines the router on the Vert.x server and how it interacts with the internal http server
 */
class VertxMicroserviceTest extends AgentTestRunner {
  @Shared
  OkHttpClient externalClient = OkHttpUtils.client()

  @Shared
  int vertxPort = PortUtils.randomOpenPort()

  @Shared
  URI vertxAddress = new URI("http://localhost:$vertxPort/")

  @AutoCleanup
  @Shared
  def internalServer = httpServer {
    handlers {
      prefix("success") {
        handleDistributedRequest()
        String msg = "Hello."
        response.status(200).send(msg)
      }
      prefix("anothersuccess") {
        handleDistributedRequest()
        String msg = "Hello."
        response.status(200).send(msg)
      }
    }
  }

  Vertx vertxServer
  WebClient internalClient

  def startVertxServer(Consumer<Route> routeConfigurer) {
    CompletableFuture<Void> future = new CompletableFuture<>()

    VertxOptions options = new VertxOptions()
    options.getEventBusOptions().setClustered(false)
    vertxServer = Vertx.vertx(options)
    vertxServer.getDelegate().deployVerticle(new PluggableRouterVerticle(routeConfigurer, vertxPort), new DeploymentOptions(), { res ->
      if (!res.succeeded()) {
        throw new RuntimeException("Cannot deploy server Verticle", res.cause())
      }
      future.complete(null)
    })

    internalClient = WebClient.create(vertxServer)

    future.get()
  }

  Response initiateExternalRequest() {
    Request request = new Request.Builder()
      .url(vertxAddress.toURL())
      .get()
      .build()
    return externalClient.newCall(request).execute()
  }

  Single<String> initiateInternalRequest(boolean instrumentedService, String path = "/success") {
    def request = internalClient.get(internalServer.address.getPort(), internalServer.address.getHost(), path)
    if (!instrumentedService) {
      request.putHeader("is-dd-server", "false")
    }

    return request
      .rxSend()
      .map({ httpResponse -> httpResponse.bodyAsString() })
  }

  def cleanup() {
    internalClient?.close()
    vertxServer?.close()
  }

  static class PluggableRouterVerticle extends AbstractVerticle {
    Consumer<Route> routeConfigurer
    int port

    PluggableRouterVerticle(Consumer<Route> routeConfigurer, int port) {
      this.routeConfigurer = routeConfigurer
      this.port = port
    }

    @Override
    void start(final Future<Void> startFuture) {
      Router router = Router.router(super.@vertx)
      Route route = router.route("/")

      routeConfigurer.accept(route)

      super.@vertx.createHttpServer()
        .requestHandler { router.accept(it) }
        .listen(port) { startFuture.complete() }
    }
  }

  def "single call to uninstrumented service"() {
    given:
    def routeConfigurer = { Route route ->
      route.handler({ context ->
        initiateInternalRequest(false).subscribe({ data -> context.response().end(data) } as Consumer)
      })
    }
    startVertxServer(routeConfigurer)

    when:
    Response response = initiateExternalRequest()

    then:
    response.code() == 200
    assertTraces(1) {
      trace(0, 2) {
        serverSpan(it, 0)
        clientSpan(it, 1, span(0))
      }
    }
  }

  def "single call to service"() {
    given:
    def routeConfigurer = { Route route ->
      route.handler({ context ->
        initiateInternalRequest(true).subscribe({ data -> context.response().end(data) } as Consumer)
      })
    }
    startVertxServer(routeConfigurer)

    when:
    Response response = initiateExternalRequest()

    then:
    response.code() == 200
    assertTraces(2) {
      internalServer.distributedRequestTrace(it, 0, trace(1).last())
      trace(1, 2) {
        serverSpan(it, 0)
        clientSpan(it, 1, span(0))
      }
    }
  }

  def "chained calls using subscribe"() {
    given:
    def routeConfigurer = { Route route ->
      route.handler({ context ->
        initiateInternalRequest(true).subscribe({ data ->
          initiateInternalRequest(true, "/anothersuccess")
            .subscribe({ data2 -> context.response().end(data + data2) } as Consumer)
        } as Consumer)
      })
    }
    startVertxServer(routeConfigurer)

    when:
    Response response = initiateExternalRequest()

    then:
    response.code() == 200
    // client spans in reverse order because the first isn't closed until the second finishes
    assertTraces(3) {
      internalServer.distributedRequestTrace(it, 0, trace(2)[2])
      internalServer.distributedRequestTrace(it, 1, trace(2)[1])
      trace(2, 3) {
        serverSpan(it, 0)
        clientSpan(it, 1, span(0), "GET", internalServer.address.resolve("/anothersuccess"))
        clientSpan(it, 2, span(0))
      }
    }
  }

  def "single call (second position) in multihandler setup"() {
    given:
    def routeConfigurer = { Route route ->
      route.handler({ context -> context.next() })
      route.handler({ context ->
        initiateInternalRequest(true)
          .subscribe({ data -> context.response().end(data) } as Consumer)
      })
    }
    startVertxServer(routeConfigurer)

    when:
    Response response = initiateExternalRequest()

    then:
    response.code() == 200
    assertTraces(2) {
      internalServer.distributedRequestTrace(it, 0, trace(1)[1])
      trace(1, 2) {
        serverSpan(it, 0)
        clientSpan(it, 1, span(0))
      }
    }
  }

  def "single call to uninstrumented in multihandler setup"() {
    given:
    def routeConfigurer = { Route route ->
      route.handler({ context -> context.next() })
      route.handler({ context ->
        initiateInternalRequest(false).subscribe({ data -> context.response().end(data) } as Consumer)
      })
    }
    startVertxServer(routeConfigurer)

    when:
    Response response = initiateExternalRequest()

    then:
    response.code() == 200
    assertTraces(1) {
      trace(0, 2) {
        serverSpan(it, 0)
        clientSpan(it, 1, span(0))
      }
    }
  }

  def "client call to example.com"() {
    given:
    def routeConfigurer = { Route route ->
      route.handler({ context ->
        internalClient.get("example.com", "/")
          .rxSend()
          .map({ httpResponse -> httpResponse.bodyAsString() })
          .subscribe({ data ->
            context.response().end(data + "\n");
          } as Consumer)
      })
    }
    startVertxServer(routeConfigurer)

    when:
    Response response = initiateExternalRequest()

    then:
    response.code() == 200

    //This fails with 2 traces
    assertTraces(1) {
      trace(0, 2) {
        serverSpan(it, 0)
        clientSpan(it, 1, span(0))
      }
    }
  }

  def "client call to example.com twice"() {
    given:
    def routeConfigurer = { Route route ->
      route.handler({ context ->
        internalClient.get("example.com", "/")
          .rxSend()
          .map({ httpResponse -> httpResponse.bodyAsString() })
          .subscribe({ data ->
            context.response().end(data + "\n");
          } as Consumer)
      })
    }
    startVertxServer(routeConfigurer)

    when:
    Response response = initiateExternalRequest()
    Response response2 = initiateExternalRequest()

    then:
    response.code() == 200
    response2.code() == 200

    // this fails with 3 traces:
    // - two broken traces for the first call
    // - one correct trace for the second call
    assertTraces(2) {
      trace(0, 2) {
        serverSpan(it, 0)
        clientSpan(it, 1, span(0))
      }
      trace(1, 2) {
        serverSpan(it, 0)
        clientSpan(it, 1, span(0))
      }
    }
  }

  void serverSpan(TraceAssert trace, int index, String traceID = null, String parentID = null, String method = "GET", boolean hasError = false) {
    trace.span(index) {
      serviceName "unnamed-java-app"
      operationName "netty.request"
      resourceName "$method ${vertxAddress.path}"
      spanType DDSpanTypes.HTTP_SERVER
      errored hasError
      if (parentID != null) {
        traceId traceID
        parentId parentID
      } else {
        parent()
      }
      tags {
        defaultTags(true)
        "$Tags.COMPONENT.key" "netty"
        if (hasError) {
          "$Tags.ERROR.key" true
          "$Tags.HTTP_STATUS.key" 500
        } else {
          "$Tags.HTTP_STATUS.key" 200
        }

        "$Tags.HTTP_URL.key" "${vertxAddress}"
        "$Tags.PEER_HOSTNAME.key" "localhost"
        "$Tags.PEER_PORT.key" Integer
        "$Tags.PEER_HOST_IPV4.key" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.HTTP_METHOD.key" method
        "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
      }
    }
  }

  void clientSpan(TraceAssert trace, int index, Object parentSpan, String method = "GET", URI uri = internalServer.address.resolve("/success"), Integer status = 200, Throwable exception = null) {
    trace.span(index) {
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      serviceName "unnamed-java-app"
      operationName "netty.client.request"
      resourceName "$method $uri.path"
      spanType DDSpanTypes.HTTP_CLIENT
      errored exception != null
      tags {
        defaultTags()
        if (exception) {
          errorTags(exception.class, exception.message)
        }
        "$Tags.COMPONENT.key" "netty-client"
        if (status) {
          "$Tags.HTTP_STATUS.key" status
        }
        "$Tags.HTTP_URL.key" "${uri.resolve(uri.path)}"

        "$Tags.PEER_HOSTNAME.key" "localhost"
        "$Tags.PEER_PORT.key" uri.port
        "$Tags.PEER_HOST_IPV4.key" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.HTTP_METHOD.key" method
        "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
      }
    }
  }
}
