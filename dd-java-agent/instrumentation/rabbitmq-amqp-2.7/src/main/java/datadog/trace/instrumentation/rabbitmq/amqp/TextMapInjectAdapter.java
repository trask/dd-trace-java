package datadog.trace.instrumentation.rabbitmq.amqp;

import datadog.trace.instrumentation.api.Propagation;
import java.util.Map;

public class TextMapInjectAdapter implements Propagation.Setter<Map<String, Object>> {

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final Map<String, Object> carrier, final String key, final String value) {
    carrier.put(key, value);
  }
}
