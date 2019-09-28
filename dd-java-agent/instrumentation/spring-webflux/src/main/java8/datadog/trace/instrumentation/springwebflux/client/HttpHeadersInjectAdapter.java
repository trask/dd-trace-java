package datadog.trace.instrumentation.springwebflux.client;

import datadog.trace.instrumentation.api.Propagation;
import org.springframework.http.HttpHeaders;

public class HttpHeadersInjectAdapter implements Propagation.Setter<HttpHeaders> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();

  @Override
  public void set(final HttpHeaders carrier, final String key, final String value) {
    carrier.set(key, value);
  }
}
