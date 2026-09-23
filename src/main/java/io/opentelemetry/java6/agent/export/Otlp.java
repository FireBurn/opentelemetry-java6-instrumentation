/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.export;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import java.util.Map;

/** Shared OTLP protobuf encoding: typed attributes, resource, scope, ids. */
public final class Otlp {

  private Otlp() {}

  public static AnyValue value(Object v) {
    AnyValue.Builder b = AnyValue.newBuilder();
    if (v instanceof String) {
      b.setStringValue((String) v);
    } else if (v instanceof Long || v instanceof Integer || v instanceof Short) {
      b.setIntValue(((Number) v).longValue());
    } else if (v instanceof Double || v instanceof Float) {
      b.setDoubleValue(((Number) v).doubleValue());
    } else if (v instanceof Boolean) {
      b.setBoolValue(((Boolean) v).booleanValue());
    } else if (v instanceof String[]) {
      ArrayValue.Builder array = ArrayValue.newBuilder();
      String[] values = (String[]) v;
      for (int i = 0; i < values.length; i++) {
        array.addValues(AnyValue.newBuilder().setStringValue(values[i]));
      }
      b.setArrayValue(array);
    } else {
      b.setStringValue(String.valueOf(v));
    }
    return b.build();
  }

  public static KeyValue keyValue(String key, Object value) {
    return KeyValue.newBuilder().setKey(key).setValue(value(value)).build();
  }

  public static void addAll(Map<String, Object> attributes, Adder adder) {
    for (Map.Entry<String, Object> e : attributes.entrySet()) {
      adder.add(keyValue(e.getKey(), e.getValue()));
    }
  }

  /** Receives encoded attributes (protobuf builders have no common supertype for this). */
  public interface Adder {
    void add(KeyValue keyValue);
  }

  public static Resource resource(Map<String, Object> attributes) {
    final Resource.Builder resource = Resource.newBuilder();
    addAll(attributes, new Adder() {
      public void add(KeyValue kv) {
        resource.addAttributes(kv);
      }
    });
    return resource.build();
  }

  public static InstrumentationScope scope(String name, String version) {
    InstrumentationScope.Builder b = InstrumentationScope.newBuilder().setName(name);
    if (version != null && version.length() > 0) {
      b.setVersion(version);
    }
    return b.build();
  }

  public static ByteString id(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] =
          (byte) ((Character.digit(hex.charAt(2 * i), 16) << 4)
              + Character.digit(hex.charAt(2 * i + 1), 16));
    }
    return ByteString.copyFrom(out);
  }
}
