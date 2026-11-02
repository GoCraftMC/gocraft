package com.go.craft.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.go.craft.GoCraft;
import com.go.craft.loader.GoLanguageAdapter;
import com.go.craft.loader.GoModCandidate;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Tells the player what Go mods can do, once for each new set of installed mods. */
public final class GoCraftClient implements ClientModInitializer {
	private static final int MAX_LISTED_MODS = 8;

	private boolean pending;

	@Override
	public void onInitializeClient() {
		List<GoModCandidate> mods = GoLanguageAdapter.loadedMods();
		if (mods.isEmpty()) return;

		// Versions count too: an update is new code, so it is worth repeating the warning for.
		String installed = mods.stream().map(GoModCandidate::describe).sorted().collect(Collectors.joining("\n"));
		Path seen = FabricLoader.getInstance().getGameDir().resolve(".gocraft").resolve("warned-mods.txt");
		pending = !installed.equals(read(seen));

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!pending || !(screen instanceof TitleScreen)) return;

			pending = false;
			// Not during this screen's own init, which is still running.
			client.execute(() -> client.gui.setScreen(warning(client, screen, mods, seen, installed)));
		});
	}

	private static Screen warning(Minecraft client, Screen previous, List<GoModCandidate> mods, Path seen, String installed) {
		Component title = Component.translatable("gocraft.warning.title");
		Component message = Component.translatable("gocraft.warning.message", modNames(mods));

		return new AlertScreen(() -> {
			write(seen, installed);
			client.gui.setScreen(previous);
		}, title, message, CommonComponents.GUI_ACKNOWLEDGE, false);
	}

	private static String modNames(List<GoModCandidate> mods) {
		String names = mods.stream().limit(MAX_LISTED_MODS).map(mod -> mod.metadata().getName()).collect(Collectors.joining(", "));
		return mods.size() > MAX_LISTED_MODS ? names + " and " + (mods.size() - MAX_LISTED_MODS) + " more" : names;
	}

	private static String read(Path file) {
		try {
			return Files.exists(file) ? Files.readString(file) : null;
		} catch (IOException e) {
			return null; // Unreadable means we warn again, which is the safe direction.
		}
	}

	private static void write(Path file, String content) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);
		} catch (IOException e) {
			GoCraft.LOGGER.warn("Could not remember the Go mod warning, so it will show again", e);
		}
	}
}
