package com.go.craft;

import com.go.craft.loader.GoLanguageAdapter;
import com.go.craft.loader.GoModCandidate;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GoCraft implements ModInitializer {
	public static final String MOD_ID = "gocraft";
	/** Bumped whenever the native table in {@code bridge.Abi} changes incompatibly. */
	public static final int ABI_VERSION = 1;
	public static final Logger LOGGER = LoggerFactory.getLogger("GoCraft");

	@Override
	public void onInitialize() {
		var mods = GoLanguageAdapter.loadedMods();
		LOGGER.info("GoCraft ready with {} Go mod(s): {}", mods.size(),
				mods.stream().map(GoModCandidate::describe).toList());
	}
}
