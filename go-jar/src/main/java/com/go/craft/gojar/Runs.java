package com.go.craft.gojar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Starts the real game with the mod installed, the same layout a player's instance has. */
final class Runs {
	private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/";
	private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";

	record ClientInstall(List<Path> classpath, Path assets, String assetIndex, String mainClass) { }

	private final Project project;
	private final Map<String, String> flags;

	Runs(Project project, Map<String, String> flags) {
		this.project = project;
		this.flags = flags;
	}

	private String java() {
		String home = System.getProperty("java.home");
		String binary = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
		return Path.of(home, "bin", binary).toString();
	}

	private void installMods(Path gameDir, Path gomod) throws IOException, InterruptedException {
		Path mods = gameDir.resolve("mods");
		Path gomods = mods.resolve("gomods");
		Files.createDirectories(gomods);

		// Stale copies of this mod would clash with the fresh build.
		try (var stream = Files.list(gomods)) {
			for (Path old : stream.filter(p -> p.getFileName().toString().startsWith(project.id + "-")).toList()) Files.delete(old);
		}
		Files.copy(gomod, gomods.resolve(gomod.getFileName()), StandardCopyOption.REPLACE_EXISTING);

		if (project.fabricApi != null) {
			String jar = "fabric-api-" + project.fabricApi + ".jar";
			Path cached = Http.download(FABRIC_MAVEN + "net/fabricmc/fabric-api/fabric-api/" + project.fabricApi.replace("+", "%2B") + "/" + jar,
					project.cache().resolve("mods").resolve(jar), null);
			Files.copy(cached, mods.resolve(jar), StandardCopyOption.REPLACE_EXISTING);
		}

		String gocraft = project.string("minecraft", "gocraft_jar", null);
		if (gocraft == null) throw new BuildException("Set gocraft_jar under [minecraft] in build.toml to the GoCraft mod jar.");

		Path jar = project.root.resolve(gocraft).normalize();
		if (!Files.exists(jar)) throw new BuildException("GoCraft jar not found: " + jar);
		Files.copy(jar, mods.resolve("gocraft.jar"), StandardCopyOption.REPLACE_EXISTING);

		for (String extra : project.list("run", "extra_gomods")) {
			Path file = project.root.resolve(extra).normalize();
			Files.copy(file, gomods.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	void server(Path gomod) throws IOException, InterruptedException {
		Path gameDir = project.root.resolve("run/server");
		Files.createDirectories(gameDir);
		installMods(gameDir, gomod);

		// The EULA is the player's to accept, so it is only ever written on an explicit request.
		Path eula = gameDir.resolve("eula.txt");
		boolean accepted = Files.exists(eula) && Files.readString(eula).contains("eula=true");
		if (!accepted) {
			if (!flags.containsKey("accept-eula")) {
				throw new BuildException("The Minecraft EULA (https://aka.ms/MinecraftEULA) must be accepted to run a server.\n"
						+ "Read it, then re-run with --accept-eula or set eula=true in " + project.root.relativize(eula) + ".");
			}
			Files.writeString(eula, "eula=true\n");
		}

		String installer = project.string("minecraft", "installer", "1.1.2");
		String file = "fabric-server-" + project.minecraft + "-" + project.loader + "-" + installer + ".jar";
		Path launcher = Http.download(FABRIC_META + project.minecraft + "/" + project.loader + "/" + installer + "/server/jar",
				project.cache().resolve("servers").resolve(file), null);

		List<String> command = new ArrayList<>(List.of(java(), "--enable-native-access=ALL-UNNAMED"));
		command.addAll(project.list("run", "jvm_args"));
		command.addAll(List.of("-jar", launcher.toString(), "nogui"));

		int code = Exec.attached(gameDir, Map.of(), command);
		if (code != 0) throw new BuildException("Server exited with code " + code);
	}

	void client(Path gomod) throws IOException, InterruptedException {
		ClientInstall install = prepareClient();
		Path gameDir = project.root.resolve("run/client");
		Files.createDirectories(gameDir);
		installMods(gameDir, gomod);

		List<String> classpath = install.classpath.stream().map(Path::toString).toList();
		List<String> command = new ArrayList<>(List.of(java(), "--enable-native-access=ALL-UNNAMED"));
		// GLFW can only create a window from the first thread on macOS.
		if (Project.hostPlatform().startsWith("darwin")) command.add("-XstartOnFirstThread");
		command.addAll(project.list("run", "jvm_args"));
		command.addAll(List.of("-cp", String.join(java.io.File.pathSeparator, classpath), "-Dfabric.gameJarPath.client=" + classpath.getLast(), install.mainClass,
				"--username", flags.getOrDefault("username", "Dev"), "--version", project.minecraft,
				"--gameDir", gameDir.toString(), "--assetsDir", install.assets.toString(), "--assetIndex", install.assetIndex,
				"--uuid", "00000000-0000-0000-0000-000000000000", "--accessToken", "0", "--userType", "legacy", "--versionType", "release"));

		int code = Exec.attached(gameDir, Map.of(), command);
		if (code != 0) throw new BuildException("Client exited with code " + code);
	}

	/** Resolves the Mojang version file and Fabric's profile into a classpath, as a launcher would. */
	@SuppressWarnings("unchecked")
	ClientInstall prepareClient() throws IOException, InterruptedException {
		Path versions = project.cache().resolve("minecraft").resolve(project.minecraft);
		Map<String, Object> version = Json.parseObject(versionJson(versions));
		Map<String, Object> downloads = (Map<String, Object>) version.get("downloads");
		Map<String, Object> clientDownload = (Map<String, Object>) downloads.get("client");
		Path clientJar = Http.download((String) clientDownload.get("url"), versions.resolve("client.jar"), "sha1:" + clientDownload.get("sha1"));

		Map<String, Path> libraries = new LinkedHashMap<>();
		Path libDir = project.cache().resolve("libraries");

		for (Object entry : (List<Object>) version.get("libraries")) {
			Map<String, Object> library = (Map<String, Object>) entry;
			if (!rulesAllow((List<Object>) library.get("rules"))) continue;

			Map<String, Object> artifact = (Map<String, Object>) ((Map<String, Object>) library.get("downloads")).get("artifact");
			if (artifact == null) continue;

			Path file = Http.download((String) artifact.get("url"), libDir.resolve((String) artifact.get("path")), "sha1:" + artifact.get("sha1"));
			libraries.put(libraryKey((String) library.get("name")), file);
		}

		String profileText = Http.text(FABRIC_META + project.minecraft + "/" + project.loader + "/profile/json");
		Map<String, Object> profile = Json.parseObject(profileText);

		// Fabric's libraries win over Mojang's where both name a library, as in the official launcher.
		for (Object entry : (List<Object>) profile.get("libraries")) {
			Map<String, Object> library = (Map<String, Object>) entry;
			String name = (String) library.get("name");
			String url = (String) library.getOrDefault("url", FABRIC_MAVEN);
			String path = mavenPath(name);
			Object sha1 = library.get("sha1");
			libraries.put(libraryKey(name), Http.download(url + path, libDir.resolve(path), sha1 == null ? null : "sha1:" + sha1));
		}

		Path assets = project.cache().resolve("assets");
		String assetIndex = downloadAssets(version, assets);

		List<Path> classpath = new ArrayList<>(libraries.values());
		classpath.add(clientJar);
		return new ClientInstall(classpath, assets, assetIndex, (String) profile.get("mainClass"));
	}

	private static String libraryKey(String mavenName) {
		String[] parts = mavenName.split(":");
		return parts[0] + ":" + parts[1] + (parts.length > 3 ? ":" + parts[3] : "");
	}

	private static String mavenPath(String name) {
		String[] parts = name.split(":");
		String file = parts[1] + "-" + parts[2] + (parts.length > 3 ? "-" + parts[3] : "") + ".jar";
		return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + file;
	}

	@SuppressWarnings("unchecked")
	private String versionJson(Path dir) throws IOException, InterruptedException {
		Path cached = dir.resolve("version.json");
		if (Files.exists(cached)) return Files.readString(cached);

		Map<String, Object> manifest = Json.parseObject(Http.text("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
		for (Object entry : (List<Object>) manifest.get("versions")) {
			Map<String, Object> version = (Map<String, Object>) entry;
			if (project.minecraft.equals(version.get("id"))) {
				Http.download((String) version.get("url"), cached, "sha1:" + version.get("sha1"));
				return Files.readString(cached);
			}
		}
		throw new BuildException("Minecraft version " + project.minecraft + " does not exist. Check [minecraft] version in build.toml.");
	}

	/** Downloads the client jar alone, which is all genBindings needs. */
	@SuppressWarnings("unchecked")
	Path clientJar() throws IOException, InterruptedException {
		Path versions = project.cache().resolve("minecraft").resolve(project.minecraft);
		Map<String, Object> version = Json.parseObject(versionJson(versions));
		Map<String, Object> client = (Map<String, Object>) ((Map<String, Object>) version.get("downloads")).get("client");
		return Http.download((String) client.get("url"), versions.resolve("client.jar"), "sha1:" + client.get("sha1"));
	}

	@SuppressWarnings("unchecked")
	private String downloadAssets(Map<String, Object> version, Path assets) throws IOException, InterruptedException {
		Map<String, Object> indexInfo = (Map<String, Object>) version.get("assetIndex");
		String id = (String) indexInfo.get("id");
		Path indexFile = Http.download((String) indexInfo.get("url"), assets.resolve("indexes").resolve(id + ".json"), "sha1:" + indexInfo.get("sha1"));
		Map<String, Object> objects = (Map<String, Object>) Json.parseObject(Files.readString(indexFile, StandardCharsets.UTF_8)).get("objects");

		List<Map.Entry<String, Object>> missing = objects.entrySet().stream().filter(e -> {
			String hash = (String) ((Map<String, Object>) e.getValue()).get("hash");
			return !Files.exists(assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash));
		}).toList();

		if (missing.isEmpty()) return id;
		Log.line("Downloading " + missing.size() + " game assets...");

		try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			var limiter = new java.util.concurrent.Semaphore(16);
			List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
			for (Map.Entry<String, Object> entry : missing) {
				String hash = (String) ((Map<String, Object>) entry.getValue()).get("hash");
				futures.add(pool.submit(() -> {
					limiter.acquire();
					try {
						String path = hash.substring(0, 2) + "/" + hash;
						return Http.download("https://resources.download.minecraft.net/" + path, assets.resolve("objects").resolve(path), "sha1:" + hash);
					} finally {
						limiter.release();
					}
				}));
			}
			for (var future : futures) {
				try {
					future.get();
				} catch (java.util.concurrent.ExecutionException e) {
					throw new BuildException("Could not download game assets: " + e.getCause().getMessage(), e.getCause());
				}
			}
		}
		return id;
	}

	@SuppressWarnings("unchecked")
	private static boolean rulesAllow(List<Object> rules) {
		if (rules == null) return true;

		String os = Project.hostPlatform().split("-")[0];
		String mojangOs = os.equals("darwin") ? "osx" : os;
		boolean allowed = false;

		for (Object entry : rules) {
			Map<String, Object> rule = (Map<String, Object>) entry;
			Map<String, Object> ruleOs = (Map<String, Object>) rule.get("os");
			if (ruleOs == null || mojangOs.equals(ruleOs.get("name"))) allowed = "allow".equals(rule.get("action"));
		}
		return allowed;
	}
}
