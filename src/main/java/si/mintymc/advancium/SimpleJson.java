package si.mintymc.advancium;

import java.util.*;

/** Tiny standalone JSON reader/writer for Minecraft progress and Discord replies. */
final class SimpleJson {
    private final String s;
    private int i;
    private SimpleJson(String s) { this.s = s; }

    static Object parse(String s) {
        SimpleJson reader = new SimpleJson(s);
        Object result = reader.value();
        reader.skip();
        if (reader.i != s.length()) throw new IllegalArgumentException("Trailing JSON content");
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object item) {
        return item instanceof Map<?, ?> ? (Map<String, Object>) item : Map.of();
    }

    static String string(Object item) { return item instanceof String s ? s : ""; }

    static String quote(String input) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : input.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 32) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private char peek() { skip(); if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON"); return s.charAt(i); }
    private boolean match(char c) { if (peek() == c) { i++; return true; } return false; }
    private void need(char c) { if (!match(c)) throw new IllegalArgumentException("Expected " + c + " at " + i); }
    private Object value() {
        char c = peek();
        if (c == '{') return map();
        if (c == '[') return array();
        if (c == '"') return readString();
        if (s.startsWith("true", i)) { i += 4; return true; }
        if (s.startsWith("false", i)) { i += 5; return false; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int start = i;
        while (i < s.length() && "0123456789+-.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw new IllegalArgumentException("Invalid JSON value at " + i);
        return Double.parseDouble(s.substring(start, i));
    }
    private Map<String, Object> map() {
        need('{'); Map<String, Object> out = new LinkedHashMap<>();
        if (match('}')) return out;
        do { String key = readString(); need(':'); out.put(key, value()); if (match('}')) return out; need(','); } while (true);
    }
    private List<Object> array() {
        need('['); List<Object> out = new ArrayList<>();
        if (match(']')) return out;
        do { out.add(value()); if (match(']')) return out; need(','); } while (true);
    }
    private String readString() {
        need('"'); StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw new IllegalArgumentException("Unterminated string escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                case 'n' -> b.append('\n'); case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                case 'u' -> { if (i + 4 > s.length()) throw new IllegalArgumentException("Invalid unicode"); b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> throw new IllegalArgumentException("Invalid escape");
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }
}
