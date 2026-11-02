package com.go.craft.loader;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.go.craft.bridge.NativeMod;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/**
 * The hinge of GoCraft, in the same role fabric-language-kotlin's adapter plays for Kotlin.
 *
 * <p>Fabric Loader instantiates language adapters in {@code finishModLoading()}, after its own
 * solver but before entrypoints, access wideners and mixin configs are collected. That makes the
 * constructor the one moment where a mod added to the loader's list becomes indistinguishable from
 * a jar it found itself, which is what the rest of GoCraft relies on.
 */
public final class GoLanguageAdapter implements LanguageAdapter {
	private static List<GoModCandidate> loaded = List.of();
	private static Map<String, GoModCandidate> byId = Map.of();

	public GoLanguageAdapter() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		loaded = new GoModDiscovery(gameDir).discover();
		byId = loaded.stream().collect(Collectors.toMap(GoModCandidate::id, Function.identity()));
		GoModInjector.inject(loaded);
	}

	public static List<GoModCandidate> loadedMods() {
		return loaded;
	}

	/** Each entrypoint value names the entrypoint key; Go code registers against that key. */
	@Override
	public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
		GoModCandidate candidate = byId.get(mod.getMetadata().getId());
		if (candidate == null) {
			throw new LanguageAdapterException("Mod " + mod.getMetadata().getId() + " uses the gocraft adapter but is not a .gomod");
		}
		if (!type.isInterface()) {
			throw new LanguageAdapterException("Go entrypoints can only implement interfaces, got " + type.getName());
		}

		InvocationHandler handler = (proxy, method, args) -> dispatch(candidate, value, proxy, method, args);
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
	}

	private static Object dispatch(GoModCandidate candidate, String key, Object proxy, Method method, Object[] args) throws Throwable {
		if (method.getDeclaringClass() == Object.class) {
			return switch (method.getName()) {
				case "toString" -> "GoEntrypoint[" + candidate.id() + ":" + key + "]";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> throw new UnsupportedOperationException(method.getName());
			};
		}
		if (method.isDefault()) {
			return InvocationHandler.invokeDefault(proxy, method, args);
		}
		NativeMod.of(candidate).enter(key);
		return null;
	}
}
