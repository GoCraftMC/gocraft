package com.go.craft.gojar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The subset of TOML build files use: tables, strings, numbers, booleans and flat arrays. */
final class Toml {
	private Toml() { }

	static Map<String, Object> parse(String text, String fileName) {
		Map<String, Object> root = new LinkedHashMap<>();
		Map<String, Object> table = root;
		String[] lines = text.split("\r?\n");

		for (int i = 0; i < lines.length; i++) {
			String line = stripComment(lines[i]).strip();
			if (line.isEmpty()) continue;

			try {
				if (line.startsWith("[")) {
					if (!line.endsWith("]")) throw new IllegalArgumentException("unterminated table header");
					table = root;
					for (String part : line.substring(1, line.length() - 1).strip().split("\\.")) {
						table = child(table, part.strip());
					}
					continue;
				}

				int eq = line.indexOf('=');
				if (eq <= 0) throw new IllegalArgumentException("expected key = value");

				String key = line.substring(0, eq).strip();
				String value = line.substring(eq + 1).strip();

				// Arrays may span lines.
				while (value.startsWith("[") && !balanced(value) && i + 1 < lines.length) {
					value += " " + stripComment(lines[++i]).strip();
				}

				table.put(unquote(key), parseValue(value));
			} catch (IllegalArgumentException e) {
				throw new BuildException(fileName + ":" + (i + 1) + ": " + e.getMessage());
			}
		}

		return root;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> child(Map<String, Object> table, String key) {
		return (Map<String, Object>) table.computeIfAbsent(unquote(key), k -> new LinkedHashMap<String, Object>());
	}

	private static String unquote(String key) {
		return key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"") ? key.substring(1, key.length() - 1) : key;
	}

	private static String stripComment(String line) {
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '\\' && quoted) i++;
			else if (c == '"') quoted = !quoted;
			else if (c == '#' && !quoted) return line.substring(0, i);
		}
		return line;
	}

	private static boolean balanced(String value) {
		int depth = 0;
		boolean quoted = false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '\\' && quoted) i++;
			else if (c == '"') quoted = !quoted;
			else if (!quoted && c == '[') depth++;
			else if (!quoted && c == ']') depth--;
		}
		return depth == 0;
	}

	private static Object parseValue(String value) {
		if (value.startsWith("\"")) {
			if (!value.endsWith("\"") || value.length() < 2) throw new IllegalArgumentException("unterminated string");
			return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t");
		}
		if (value.startsWith("[")) {
			if (!value.endsWith("]")) throw new IllegalArgumentException("unterminated array");
			List<Object> list = new ArrayList<>();
			for (String item : splitTopLevel(value.substring(1, value.length() - 1))) {
				if (!item.isBlank()) list.add(parseValue(item.strip()));
			}
			return list;
		}
		if (value.equals("true")) return true;
		if (value.equals("false")) return false;
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("cannot parse value: " + value);
		}
	}

	private static List<String> splitTopLevel(String body) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		boolean quoted = false;
		int start = 0;

		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c == '\\' && quoted) i++;
			else if (c == '"') quoted = !quoted;
			else if (!quoted && c == '[') depth++;
			else if (!quoted && c == ']') depth--;
			else if (!quoted && c == ',' && depth == 0) {
				parts.add(body.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(body.substring(start));
		return parts;
	}
}
