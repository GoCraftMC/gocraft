package com.go.craft.loader;

import java.nio.file.Path;
import java.util.List;

import net.fabricmc.loader.impl.metadata.LoaderModMetadata;

/**
 * A validated .gomod, unpacked for the current platform.
 *
 * @param source  the .gomod the user installed
 * @param jar     generated runtime jar holding resources, metadata and hook mixins
 * @param library extracted native library for this platform
 * @param hooks   hook declarations from gomod.json
 */
public record GoModCandidate(String id, String version, Path source, Path jar, Path library,
		LoaderModMetadata metadata, List<HookSpec> hooks) {
	public String describe() {
		return id + "@" + version;
	}
}
