package datadog.trace.instrumentation.jaxrs.v1;

import com.sun.jersey.api.client.ClientRequest;
import datadog.trace.instrumentation.api.Propagation;

public final class InjectAdapter implements Propagation.Setter<ClientRequest> {

  public static final InjectAdapter SETTER = new InjectAdapter();

  @Override
  public void set(final ClientRequest carrier, final String key, final String value) {
    // Don't allow duplicates.
    carrier.getHeaders().putSingle(key, value);
  }
}
