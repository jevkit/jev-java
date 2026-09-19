package io.github.jevkit.model;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates and copies content that is sent to the API as JSON: {@code state}, {@code instructions}, and option or level
 * descriptions.
 *
 * <p>The result is built only from {@code String}, {@code Boolean}, {@code Number}, {@code null}, unmodifiable
 * {@code List} and unmodifiable insertion-ordered {@code Map<String, Object>}. Records are converted to maps here, in
 * component order, so serialization never depends on Gson's record support, which older Gson versions lack.
 */
final class Content {

    private static final int MAX_DEPTH = 64;

    private Content() {
    }

    /**
     * @param value the caller's value, possibly {@code null}
     * @param path  where the value sits, used in error messages, e.g. {@code "instructions"}
     * @return an immutable copy of {@code value}
     * @throws IllegalArgumentException if the value, or anything nested in it, cannot be represented as JSON
     */
    static Object copyOf(Object value, String path) {
        return copy(value, path, 0);
    }

    private static Object copy(Object value, String path, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(path + ": nested more than " + MAX_DEPTH + " levels deep (is there a cycle?)");
        }

        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }

        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();

            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new IllegalArgumentException(path + ": " + value + " cannot be represented in JSON");
            }

            return value;
        }

        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }

        if (value instanceof Enum<?> constant) {
            return constant.name();
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(path + ": map keys must be strings, found " + describe(entry.getKey()));
                }

                copy.put(key, copy(entry.getValue(), path + "." + key, depth + 1));
            }

            return Collections.unmodifiableMap(copy);
        }

        if (value instanceof Collection<?> collection) {
            return copyList(collection, path, depth);
        }

        if (value instanceof Object[] array) {
            return copyList(List.of(array), path, depth);
        }

        if (value.getClass().isRecord()) {
            return copyRecord((Record) value, path, depth);
        }

        throw new IllegalArgumentException(path + ": unsupported type " + value.getClass().getName()
                + "; use String, Number, Boolean, Map, List, a record, or null");
    }

    private static List<Object> copyList(Collection<?> values, String path, int depth) {
        List<Object> copy = new ArrayList<>(values.size());
        int index = 0;

        for (Object element : values) {
            copy.add(copy(element, path + "[" + index++ + "]", depth + 1));
        }

        return Collections.unmodifiableList(copy);
    }

    private static Map<String, Object> copyRecord(Record record, String path, int depth) {
        Map<String, Object> copy = new LinkedHashMap<>();

        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Method accessor = component.getAccessor();
            Object componentValue;

            try {
                // Lets private and nested records work on the classpath; a named module that doesn't open the
                // package still refuses, and invoke() below reports it.
                accessor.setAccessible(true);
            } catch (RuntimeException ignored) {
                // InaccessibleObjectException or SecurityException: fall through to invoke()
            }

            try {
                componentValue = accessor.invoke(record);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException(path + ": record " + record.getClass().getName()
                        + " is not accessible; make it public or pass a Map instead", e);
            } catch (InvocationTargetException e) {
                throw new IllegalArgumentException(path + "." + component.getName() + ": accessor threw "
                        + e.getCause(), e.getCause());
            }

            copy.put(component.getName(), copy(componentValue, path + "." + component.getName(), depth + 1));
        }

        return Collections.unmodifiableMap(copy);
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
