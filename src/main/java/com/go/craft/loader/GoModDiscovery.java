package com.go.craft.loader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.go.craft.GoCraft;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/** Finds mods/gomods/*.gomod, unpacks them into .gocraft/runtime and validates dependencies. */
final class GoModDiscovery {
	private static final LogCategory LOG = LogCategory.create("GoCraft");
	private static final Gson GSON = new Gson();
	/** Part of the runtime cache key: bump when the generated jar's shape changes. */
	private static final int RUNTIME_FORMAT = 1;

	/** gomod.json keys that pass straight through to the generated fabric.mod.json. */
	private static final List<String> FABRIC_KEYS = List.of("id", "version", "name", "description", "authors", "contributors",
			"contact", "license", "icon", "environment", "depends", "recommends", "suggests", "conflicts", "breaks",
			"provides", "accessWidener", "classTweaker", "custom");

	private final Path gameDir;

	GoModDiscovery(Path gameDir) {
		this.gameDir = gameDir;
	}

	List<GoModCandidate> discover() {
		Path dir = gameDir.resolve("mods").resolve("gomods");
		List<Path> files = new ArrayList<>();

		try {
			Files.createDirectories(dir);
			try (Stream<Path> list = Files.list(dir)) {
				list.filter(p -> p.getFileName().toString().endsWith(".gomod") && Files.isRegularFile(p)).sorted().forEach(files::add);
			}
		} catch (IOException e) {
			throw new GoModException("Could not read " + dir, e);
		}

		EnvType env = FabricLoader.getInstance().getEnvironmentType();
		Map<String, GoModCandidate> mods = new LinkedHashMap<>();
		List<String> problems = new ArrayList<>();

		for (Path file : files) {
			try {
				GoModCandidate mod = open(file, env);
				if (mod == null) continue;

				GoModCandidate previous = mods.putIfAbsent(mod.id(), mod);
				if (previous != null) problems.add("Mod id '" + mod.id() + "' is provided by both " + previous.source().getFileName() + " and " + file.getFileName());
				if (FabricLoader.getInstance().isModLoaded(mod.id())) problems.add("Mod id '" + mod.id() + "' from " + file.getFileName() + " clashes with an installed Fabric mod");
			} catch (GoModException e) {
				problems.add(e.getMessage());
			} catch (Exception e) {
				throw new GoModException("Failed to read " + file.getFileName() + ": " + e, e);
			}
		}

		for (GoModCandidate mod : mods.values()) checkDependencies(mod, mods, problems);

		if (!problems.isEmpty()) {
			throw new GoModException("GoCraft could not load your Go mods:\n - " + String.join("\n - ", problems));
		}

		return sortByDependencies(mods);
	}

	/** Returns null for a mod that is not for this environment. */
	private GoModCandidate open(Path file, EnvType env) throws IOException {
		String hash = sha256(file);

		try (ZipFile zip = new ZipFile(file.toFile())) {
			ZipEntry manifestEntry = zip.getEntry("gomod.json");
			if (manifestEntry == null) throw new GoModException(file.getFileName() + " has no gomod.json");

			JsonObject manifest;
			try (InputStream in = zip.getInputStream(manifestEntry)) {
				manifest = GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
			}

			if (manifest == null || !manifest.has("id") || !manifest.has("version")) {
				throw new GoModException(file.getFileName() + ": gomod.json needs at least 'id' and 'version'");
			}

			String id = manifest.get("id").getAsString();
			int abi = manifest.has("abi") ? manifest.get("abi").getAsInt() : -1;
			if (abi != GoCraft.ABI_VERSION) {
				throw new GoModException(id + " was built for GoCraft ABI " + abi + " but this GoCraft uses ABI " + GoCraft.ABI_VERSION + ". Update " + (abi < GoCraft.ABI_VERSION ? "the mod" : "GoCraft") + ".");
			}

			List<HookSpec> hooks = new ArrayList<>();
			if (manifest.has("hooks")) {
				for (JsonElement hook : manifest.getAsJsonArray("hooks")) hooks.add(HookSpec.parse(hook.getAsJsonObject(), id));
			}

			String fabricJson = fabricModJson(manifest, hooks);
			LoaderModMetadata metadata = parseMetadata(fabricJson, file);

			// Before the platform check: a client-only mod need not ship natives for a server's platform.
			if (!metadata.loadsInEnvironment(env)) {
				Log.info(LOG, "Skipping %s: not for the %s environment", id, env);
				return null;
			}

			String platform = platform();
			String libraryName = "natives/" + platform + "/mod." + libraryExtension();
			ZipEntry library = zip.getEntry(libraryName);
			if (library == null) {
				throw new GoModException(id + " has no native library for " + platform + " (it ships: " + platforms(zip) + ")");
			}

			Path runtime = gameDir.resolve(".gocraft").resolve("runtime").resolve(id + "-" + hash.substring(0, 16) + "-" + RUNTIME_FORMAT);
			Path jar = runtime.resolve(id + ".jar");
			Path libraryFile = runtime.resolve("mod." + libraryExtension());

			if (!Files.exists(jar) || !Files.exists(libraryFile)) {
				Files.createDirectories(runtime);
				writeAtomic(libraryFile, out -> {
					try (InputStream in = zip.getInputStream(library)) {
						in.transferTo(out);
					}
				});
				writeAtomic(jar, out -> buildJar(zip, id, fabricJson, hooks, out));
			}

			return new GoModCandidate(id, manifest.get("version").getAsString(), file, jar, libraryFile, metadata, List.copyOf(hooks));
		}
	}

	private static String fabricModJson(JsonObject manifest, List<HookSpec> hooks) {
		JsonObject fabric = new JsonObject();
		fabric.addProperty("schemaVersion", 1);

		for (String key : FABRIC_KEYS) {
			if (manifest.has(key)) fabric.add(key, manifest.get(key));
		}

		JsonObject depends = fabric.has("depends") ? fabric.getAsJsonObject("depends") : new JsonObject();
		if (!depends.has(GoCraft.MOD_ID)) depends.addProperty(GoCraft.MOD_ID, "*");
		fabric.add("depends", depends);

		// Which keys exist is declared by the manifest; Go registers handlers against the same names.
		JsonObject entrypoints = new JsonObject();
		List<String> keys = new ArrayList<>(List.of("preLaunch", "main", "client", "server"));
		if (manifest.has("customEntrypoints")) {
			for (JsonElement key : manifest.getAsJsonArray("customEntrypoints")) keys.add(key.getAsString());
		}

		for (String key : keys) {
			JsonObject entry = new JsonObject();
			entry.addProperty("adapter", GoCraft.MOD_ID);
			entry.addProperty("value", key);
			var array = new com.google.gson.JsonArray();
			array.add(entry);
			entrypoints.add(key, array);
		}

		fabric.add("entrypoints", entrypoints);

		if (!hooks.isEmpty()) {
			var mixins = new com.google.gson.JsonArray();
			mixins.add(HookMixinGenerator.configName(manifest.get("id").getAsString()));
			fabric.add("mixins", mixins);
		}

		return GSON.toJson(fabric);
	}

	private static void buildJar(ZipFile zip, String id, String fabricJson, List<HookSpec> hooks, OutputStream out) throws IOException {
		try (ZipOutputStream jar = new ZipOutputStream(out)) {
			jar.putNextEntry(new ZipEntry("fabric.mod.json"));
			jar.write(fabricJson.getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();

			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();

				if (entry.isDirectory() || name.startsWith("natives/") || name.equals("gomod.json") || name.equals("fabric.mod.json")) continue;
				if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
					throw new GoModException(id + " contains an unsafe path: " + name);
				}

				jar.putNextEntry(new ZipEntry(name));
				try (InputStream in = zip.getInputStream(entry)) {
					in.transferTo(jar);
				}
				jar.closeEntry();
			}

			HookMixinGenerator.write(id, hooks, jar);
		}
	}

	private static LoaderModMetadata parseMetadata(String fabricJson, Path file) {
		try (InputStream in = new ByteArrayInputStream(fabricJson.getBytes(StandardCharsets.UTF_8))) {
			return ModMetadataParser.parseMetadata(in, file.toString(), List.of(), new VersionOverrides(),
					new DependencyOverrides(FabricLoader.getInstance().getConfigDir()), FabricLoader.getInstance().isDevelopmentEnvironment());
		} catch (Exception e) {
			throw new GoModException(file.getFileName() + ": invalid gomod.json: " + e.getMessage(), e);
		}
	}

	private static void checkDependencies(GoModCandidate mod, Map<String, GoModCandidate> mods, List<String> problems) {
		for (ModDependency dep : mod.metadata().getDependencies()) {
			Version version = findVersion(dep.getModId(), mods);

			switch (dep.getKind()) {
				case DEPENDS -> {
					if (version == null) problems.add(mod.describe() + " requires " + dep.getModId() + " " + dep.getVersionRequirements() + ", which is not installed");
					else if (!dep.matches(version)) problems.add(mod.describe() + " requires " + dep.getModId() + " " + dep.getVersionRequirements() + " but found " + version.getFriendlyString());
				}
				case BREAKS -> {
					if (version != null && dep.matches(version)) problems.add(mod.describe() + " is incompatible with " + dep.getModId() + " " + version.getFriendlyString());
				}
				case CONFLICTS -> {
					if (version != null && dep.matches(version)) Log.warn(LOG, "%s conflicts with %s %s", mod.describe(), dep.getModId(), version.getFriendlyString());
				}
				default -> { }
			}
		}
	}

	private static Version findVersion(String id, Map<String, GoModCandidate> mods) {
		GoModCandidate go = findProvider(id, mods);
		if (go != null) return go.metadata().getVersion();
		return FabricLoader.getInstance().getModContainer(id).map(ModContainer::getMetadata).map(m -> m.getVersion()).orElse(null);
	}

	private static GoModCandidate findProvider(String id, Map<String, GoModCandidate> mods) {
		GoModCandidate direct = mods.get(id);
		if (direct != null) return direct;

		for (GoModCandidate mod : mods.values()) {
			if (mod.metadata().getProvides().contains(id)) return mod;
		}
		return null;
	}

	/** Dependencies initialize first, matching how a Go mod would expect its libraries to behave. */
	private static List<GoModCandidate> sortByDependencies(Map<String, GoModCandidate> mods) {
		List<GoModCandidate> sorted = new ArrayList<>();
		Map<String, Boolean> visiting = new HashMap<>();

		for (GoModCandidate mod : mods.values()) visit(mod, mods, visiting, sorted);
		return sorted;
	}

	private static void visit(GoModCandidate mod, Map<String, GoModCandidate> mods, Map<String, Boolean> state, List<GoModCandidate> out) {
		Boolean seen = state.get(mod.id());
		if (seen != null) {
			if (!seen) throw new GoModException("Dependency cycle through Go mod " + mod.id());
			return;
		}

		state.put(mod.id(), false);
		for (ModDependency dep : mod.metadata().getDependencies()) {
			GoModCandidate provider = findProvider(dep.getModId(), mods);
			if (dep.getKind() == ModDependency.Kind.DEPENDS && provider != null) visit(provider, mods, state, out);
		}
		state.put(mod.id(), true);
		out.add(mod);
	}

	static String platform() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
		String goOs = os.contains("win") ? "windows" : os.contains("mac") ? "darwin" : "linux";
		String goArch = arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "amd64";
		return goOs + "-" + goArch;
	}

	static String libraryExtension() {
		return switch (platform().split("-")[0]) {
			case "windows" -> "dll";
			case "darwin" -> "dylib";
			default -> "so";
		};
	}

	private static String platforms(ZipFile zip) {
		List<String> found = new ArrayList<>();
		zip.stream().map(ZipEntry::getName).filter(n -> n.startsWith("natives/") && n.contains("/mod.")).forEach(n -> found.add(n.split("/")[1]));
		return found.isEmpty() ? "nothing" : String.join(", ", found);
	}

	private interface Writer {
		void write(OutputStream out) throws IOException;
	}

	private static void writeAtomic(Path target, Writer writer) throws IOException {
		Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
		try (OutputStream out = Files.newOutputStream(tmp)) {
			writer.write(out);
		}
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static String sha256(Path file) throws IOException {
		try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
			MessageDigest digest = ((DigestInputStream) in).getMessageDigest();
			in.transferTo(OutputStream.nullOutputStream());
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
