package datadog.trace.instrumentation.apachehttpasyncclient;

import datadog.trace.instrumentation.api.Propagation;
import org.apache.http.HttpRequest;

public class HttpHeadersInjectAdapter implements Propagation.Setter<HttpRequest> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();

  @Override
  public void set(final HttpRequest carrier, final String key, final String value) {
    carrier.setHeader(key, value);
  }
}
