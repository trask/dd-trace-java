package datadog.trace.instrumentation.netty40.client;

import datadog.trace.instrumentation.api.Propagation;
import io.netty.handler.codec.http.HttpRequest;

public class NettyResponseInjectAdapter implements Propagation.Setter<HttpRequest> {

  public static final NettyResponseInjectAdapter SETTER = new NettyResponseInjectAdapter();

  @Override
  public void set(final HttpRequest carrier, final String key, final String value) {
    carrier.headers().set(key, value);
  }
}
