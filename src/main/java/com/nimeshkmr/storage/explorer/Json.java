package com.nimeshkmr.storage.explorer;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON writer and parser so the adapter needs no third-party dependency.
 *
 * <p>The writer handles null, strings, numbers, booleans, enums, maps, iterables and records
 * (record components become fields named after them, with a trailing "ID" written as "Id"). The parser returns LinkedHashMap, ArrayList,
 * String, Long or Double, Boolean and null, and is mainly used by tests and the Java-side
 * checks; the Python client uses its own JSON library.
 */
public final class Json {

    private Json() {}

    // ------------------------------------------------------------------ writer

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Short || v instanceof Byte) {
            sb.append(v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isFinite(d)) {
                sb.append(d);
            } else {
                sb.append("null");
            }
        } else if (v instanceof Enum<?> e) {
            quote(sb, e.name());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v.getClass().isRecord()) {
            sb.append('{');
            boolean first = true;
            for (RecordComponent rc : v.getClass().getRecordComponents()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, fieldName(rc.getName()));
                sb.append(':');
                try {
                    write(sb, rc.getAccessor().invoke(v));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot serialize " + rc, e);
                }
            }
            sb.append('}');
        } else {
            throw new IllegalArgumentException("Unsupported JSON type: " + v.getClass().getName());
        }
    }

    /** JSON field name for a record component: a trailing "ID" becomes "Id" (rootPageID -> rootPageId). */
    static String fieldName(String component) {
        return component.endsWith("ID") ? component.substring(0, component.length() - 2) + "Id" : component;
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ parser

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("Trailing characters at " + p.pos);
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(pos);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String k = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(k, value());
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Expected , or } at " + (pos - 1));
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("Expected , or ] at " + (pos - 1));
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); pos += 4; }
                    default -> sb.append(e);
                }
            }
        }

        Object number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String t = s.substring(start, pos);
            if (t.isEmpty()) throw new IllegalArgumentException("Unexpected character at " + start);
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
            return Long.parseLong(t);
        }

        void expect(char c) {
            if (s.charAt(pos) != c) throw new IllegalArgumentException("Expected " + c + " at " + pos);
            pos++;
        }
    }
}
