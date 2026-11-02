package com.go.craft.gojar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader and writer. go.jar deliberately has no dependencies. */
final class Json {
	private final String text;
	private int pos;

	private Json(String text) {
		this.text = text;
	}

	static Object parse(String text) {
		Json json = new Json(text);
		json.skipSpace();
		Object value = json.value();
		json.skipSpace();
		if (json.pos != text.length()) throw json.error("trailing content");
		return value;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> parseObject(String text) {
		return (Map<String, Object>) parse(text);
	}

	private BuildException error(String message) {
		return new BuildException("Invalid JSON at offset " + pos + ": " + message);
	}

	private void skipSpace() {
		while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
	}

	private Object value() {
		if (pos >= text.length()) throw error("unexpected end");
		char c = text.charAt(pos);
		return switch (c) {
			case '{' -> object();
			case '[' -> array();
			case '"' -> string();
			case 't' -> literal("true", true);
			case 'f' -> literal("false", false);
			case 'n' -> literal("null", null);
			default -> number();
		};
	}

	private Object literal(String word, Object value) {
		if (!text.startsWith(word, pos)) throw error("expected " + word);
		pos += word.length();
		return value;
	}

	private Object number() {
		int start = pos;
		while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
		if (start == pos) throw error("unexpected character '" + text.charAt(pos) + "'");
		String number = text.substring(start, pos);
		if (number.contains(".") || number.contains("e") || number.contains("E")) return Double.parseDouble(number);
		return Long.parseLong(number);
	}

	private Map<String, Object> object() {
		Map<String, Object> map = new LinkedHashMap<>();
		pos++;
		skipSpace();
		if (text.charAt(pos) == '}') {
			pos++;
			return map;
		}
		while (true) {
			skipSpace();
			if (text.charAt(pos) != '"') throw error("expected a key");
			String key = string();
			skipSpace();
			if (text.charAt(pos++) != ':') throw error("expected ':'");
			skipSpace();
			map.put(key, value());
			skipSpace();
			char c = text.charAt(pos++);
			if (c == '}') return map;
			if (c != ',') throw error("expected ',' or '}'");
		}
	}

	private List<Object> array() {
		List<Object> list = new ArrayList<>();
		pos++;
		skipSpace();
		if (text.charAt(pos) == ']') {
			pos++;
			return list;
		}
		while (true) {
			skipSpace();
			list.add(value());
			skipSpace();
			char c = text.charAt(pos++);
			if (c == ']') return list;
			if (c != ',') throw error("expected ',' or ']'");
		}
	}

	private String string() {
		StringBuilder out = new StringBuilder();
		pos++;
		while (true) {
			if (pos >= text.length()) throw error("unterminated string");
			char c = text.charAt(pos++);
			if (c == '"') return out.toString();
			if (c != '\\') {
				out.append(c);
				continue;
			}
			char e = text.charAt(pos++);
			switch (e) {
				case 'n' -> out.append('\n');
				case 't' -> out.append('\t');
				case 'r' -> out.append('\r');
				case 'b' -> out.append('\b');
				case 'f' -> out.append('\f');
				case 'u' -> {
					out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
					pos += 4;
				}
				default -> out.append(e);
			}
		}
	}

	static String write(Object value) {
		StringBuilder out = new StringBuilder();
		write(value, out, 0);
		return out.append('\n').toString();
	}

	private static void write(Object value, StringBuilder out, int depth) {
		if (value instanceof Map<?, ?> map) {
			if (map.isEmpty()) {
				out.append("{}");
				return;
			}
			out.append("{\n");
			int i = 0;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				out.append("  ".repeat(depth + 1));
				quote(String.valueOf(entry.getKey()), out);
				out.append(": ");
				write(entry.getValue(), out, depth + 1);
				out.append(++i < map.size() ? ",\n" : "\n");
			}
			out.append("  ".repeat(depth)).append('}');
		} else if (value instanceof List<?> list) {
			if (list.isEmpty()) {
				out.append("[]");
				return;
			}
			out.append("[\n");
			for (int i = 0; i < list.size(); i++) {
				out.append("  ".repeat(depth + 1));
				write(list.get(i), out, depth + 1);
				out.append(i + 1 < list.size() ? ",\n" : "\n");
			}
			out.append("  ".repeat(depth)).append(']');
		} else if (value instanceof String s) {
			quote(s, out);
		} else {
			out.append(value);
		}
	}

	private static void quote(String s, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
					else out.append(c);
				}
			}
		}
		out.append('"');
	}
}
