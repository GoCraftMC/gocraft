package com.go.craft.loader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;

/**
 * Adds Go mods to Fabric Loader's own mod list, so entrypoints, mixins, access wideners, resource
 * packs and mod lists all see them as ordinary mods.
 *
 * <p>This touches Loader internals, so every failure is reported as an explicit incompatibility
 * rather than a confusing crash later.
 */
final class GoModInjector {
	private GoModInjector() { }

	@SuppressWarnings("unchecked")
	static void inject(List<GoModCandidate> mods) {
		if (mods.isEmpty()) return;

		try {
			FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
			Method createPlain = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
			createPlain.setAccessible(true);

			Field modsField = FabricLoaderImpl.class.getDeclaredField("mods");
			Field mapField = FabricLoaderImpl.class.getDeclaredField("modMap");
			modsField.setAccessible(true);
			mapField.setAccessible(true);

			Map<String, ModContainerImpl> modMap = (Map<String, ModContainerImpl>) mapField.get(loader);
			// setupLanguageAdapters() is iterating the current list right now. It keeps that iterator;
			// everything afterwards reads the field and sees the replacement.
			List<ModContainerImpl> replacement = new ArrayList<>((List<ModContainerImpl>) modsField.get(loader));

			for (GoModCandidate mod : mods) {
				List<Path> paths = List.of(mod.jar());
				ModCandidateImpl candidate = (ModCandidateImpl) createPlain.invoke(null, paths, mod.metadata(), false, List.of());
				ModContainerImpl container = new ModContainerImpl(candidate);

				replacement.add(container);
				modMap.put(mod.id(), container);
				for (String provided : mod.metadata().getProvides()) modMap.put(provided, container);

				// finishModLoading() already ran this step for the mods it knew about.
				FabricLauncherBase.getLauncher().addToClassPath(mod.jar());
			}

			modsField.set(loader, replacement);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new GoModException("This version of GoCraft is not compatible with the installed Fabric Loader. Update GoCraft.", e);
		}
	}
}
