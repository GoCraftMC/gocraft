package com.go.craft.gojar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A parsed build.toml plus the paths everything else derives from it. */
final class Project {
	final Path root;
	final Map<String, Object> toml;
	final String id;
	final String version;
	final String minecraft;
	final String loader;
	final String fabricApi;
	final String goVersion;
	final List<String> targets;
	final Path userHome;

	Project(Path root) {
		this.root = root.toAbsolutePath().normalize();
		Path file = this.root.resolve("build.toml");

		if (!Files.exists(file)) {
			throw new BuildException("No build.toml in " + this.root + ". Run 'java -jar go.jar init <mod-id>' to start a project.");
		}

		try {
			toml = Toml.parse(Files.readString(file), "build.toml");
		} catch (IOException e) {
			throw new BuildException("Could not read build.toml: " + e.getMessage());
		}

		id = requireString("project", "id");
		version = requireString("project", "version");
		minecraft = requireString("minecraft", "version");
		loader = string("minecraft", "loader", "0.20.1");
		fabricApi = string("minecraft", "fabric_api", null);
		goVersion = string("go", "version", "system");

		List<String> configured = list("go", "targets");
		targets = configured.isEmpty() ? List.of(hostPlatform()) : configured;
		userHome = Path.of(System.getProperty("gocraft.home", System.getProperty("user.home") + "/.gocraft"));
	}

	@SuppressWarnings("unchecked")
	Map<String, Object> table(String name) {
		Object value = toml.get(name);
		return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
	}

	String string(String table, String key, String fallback) {
		Object value = table(table).get(key);
		return value == null ? fallback : String.valueOf(value);
	}

	private String requireString(String table, String key) {
		String value = string(table, key, null);
		if (value == null) throw new BuildException("build.toml: [" + table + "] needs '" + key + "'");
		return value;
	}

	@SuppressWarnings("unchecked")
	List<String> list(String table, String key) {
		Object value = table(table).get(key);
		if (value == null) return List.of();
		if (!(value instanceof List<?>)) throw new BuildException("build.toml: [" + table + "] '" + key + "' must be an array");
		List<String> out = new ArrayList<>();
		for (Object item : (List<Object>) value) out.add(String.valueOf(item));
		return out;
	}

	boolean flag(String table, String key) {
		return Boolean.TRUE.equals(table(table).get(key));
	}

	Path build() {
		return root.resolve("build");
	}

	Path state() {
		return root.resolve(".gocraft");
	}

	Path cache() {
		return userHome.resolve("cache");
	}

	/** The module path from go.mod. */
	String goModule() {
		for (String line : goModLines()) {
			String stripped = line.strip();
			if (stripped.startsWith("module ")) return stripped.substring(7).strip();
		}
		throw new BuildException("go.mod has no module line");
	}

	/** Local directories named by go.mod replace directives: their sources are build inputs too. */
	List<Path> goModReplacements() {
		List<Path> paths = new ArrayList<>();
		Pattern arrow = Pattern.compile("=>\\s*(\\.{1,2}/\\S*|/\\S*)\\s*$");

		for (String line : goModLines()) {
			Matcher matcher = arrow.matcher(line.strip());
			if (matcher.find()) paths.add(root.resolve(matcher.group(1)).normalize());
		}
		return paths;
	}

	private List<String> goModLines() {
		try {
			return Files.readAllLines(root.resolve("go.mod"));
		} catch (IOException e) {
			throw new BuildException("Could not read go.mod. Run 'go mod init <module>' in the project root.");
		}
	}

	static String hostPlatform() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
		String goOs = os.contains("win") ? "windows" : os.contains("mac") ? "darwin" : "linux";
		String goArch = arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "amd64";
		return goOs + "-" + goArch;
	}

	static String libraryExtension(String platform) {
		return switch (platform.split("-")[0]) {
			case "windows" -> "dll";
			case "darwin" -> "dylib";
			default -> "so";
		};
	}
}
