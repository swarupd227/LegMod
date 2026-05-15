package com.envestnet.broadridge;

import org.apache.axis.encoding.ser.SimpleSerializer;
import org.apache.axis.encoding.ser.SimpleDeserializer;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Custom Axis serializer pair for tradeDate. Emits and parses MM/dd/yyyy
 * exclusively — Broadridge ops have not adopted ISO 8601 and reject any other
 * format. The Atlas Migrate Translation agent must produce an equivalent
 * JAXB XmlAdapter so wire parity is preserved.
 */
public class BroadridgeDateAdapter {

    private static final ThreadLocal<SimpleDateFormat> FMT =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("MM/dd/yyyy"));

    public static String marshal(Date d) {
        return d == null ? null : FMT.get().format(d);
    }

    public static Date unmarshal(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return FMT.get().parse(s);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Bad Broadridge date: " + s, e);
        }
    }

    public static class SerializerFactory
            extends org.apache.axis.encoding.ser.BaseSerializerFactory {
        public SerializerFactory(Class<?> javaType) {
            super(SimpleSerializer.class, null, javaType);
        }
    }

    public static class DeserializerFactory
            extends org.apache.axis.encoding.ser.BaseDeserializerFactory {
        public DeserializerFactory(Class<?> javaType) {
            super(SimpleDeserializer.class, null, javaType);
        }
    }
}
