package com.go.craft.loader;

import com.google.gson.JsonObject;

/**
 * A Go-side mixin declared in gomod.json. Becomes a generated {@code @Inject} that forwards to the
 * Go function bound to {@code id}.
 *
 * @param at one of HEAD, RETURN, TAIL
 * @param environment "*", "client" or "server"
 */
public record HookSpec(String id, String target, String method, String desc, String at, boolean isStatic, String environment) {
	static HookSpec parse(JsonObject json, String modId) {
		String id = required(json, "id", modId);
		String at = json.has("at") ? json.get("at").getAsString().toUpperCase(java.util.Locale.ROOT) : "HEAD";

		if (!at.equals("HEAD") && !at.equals("RETURN") && !at.equals("TAIL")) {
			throw new GoModException(modId + ": hook '" + id + "' has unsupported at '" + at + "' (use HEAD, RETURN or TAIL)");
		}

		String desc = required(json, "desc", modId);
		if (!desc.startsWith("(") || !desc.contains(")")) {
			throw new GoModException(modId + ": hook '" + id + "' has malformed descriptor '" + desc + "'");
		}

		return new HookSpec(id, required(json, "target", modId), required(json, "method", modId), desc, at,
				json.has("static") && json.get("static").getAsBoolean(),
				json.has("environment") ? json.get("environment").getAsString() : "*");
	}

	private static String required(JsonObject json, String key, String modId) {
		if (!json.has(key)) throw new GoModException(modId + ": a hook is missing required field '" + key + "'");
		return json.get(key).getAsString();
	}

	public boolean returnsVoid() {
		return desc.endsWith(")V");
	}
}
