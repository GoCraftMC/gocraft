package com.go.craft.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entry point of generated hook mixins: looks up the bound Go function and calls it. */
public final class HookRuntime {
	private record Binding(NativeMod owner, long callback) { }

	private static final Map<String, Binding> BINDINGS = new ConcurrentHashMap<>();
	private static final Class<?>[] CONTEXT_TYPES = {HookContext.class};

	private HookRuntime() { }

	static void bind(String hookId, NativeMod owner, long callback) {
		BINDINGS.put(hookId, new Binding(owner, callback));
	}

	public static void fire(String hookId, Object self, Object[] args, CallbackInfo info) {
		Binding binding = BINDINGS.get(hookId);
		if (binding == null) return;

		try {
			binding.owner.call(binding.callback, CONTEXT_TYPES, new Object[] {new HookContext(self, args, info)}, void.class);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new RuntimeException("Go hook " + hookId + " failed", t);
		}
	}
}
