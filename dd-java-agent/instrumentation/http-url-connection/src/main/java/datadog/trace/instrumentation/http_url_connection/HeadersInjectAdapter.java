package datadog.trace.instrumentation.http_url_connection;

import datadog.trace.instrumentation.api.Propagation;
import java.net.HttpURLConnection;

public class HeadersInjectAdapter implements Propagation.Setter<HttpURLConnection> {

  public static final HeadersInjectAdapter SETTER = new HeadersInjectAdapter();

  @Override
  public void set(final HttpURLConnection carrier, final String key, final String value) {
    carrier.setRequestProperty(key, value);
  }
}
