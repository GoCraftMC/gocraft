package com.go.craft.gojar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds or provisions the Go and C toolchains a build needs, so that a fresh checkout builds with
 * nothing but Java installed. Cross-compiling cgo needs a cross C compiler; Zig is one for every
 * target, in a single download.
 */
final class Toolchains {
	private static final Object LOCK = new Object();

	private final Project project;

	Toolchains(Project project) {
		this.project = project;
	}

	/** Absolute path of the go binary to use. */
	String go() throws IOException, InterruptedException {
		String wanted = project.goVersion;
		if (wanted.equals("system")) {
			String system = onPath("go");
			if (system != null) return system;
			throw new BuildException("Go was not found on PATH. Install it, or set a version under [go] in build.toml and go.jar will download it.");
		}

		String system = onPath("go");
		if (system != null && systemGoVersion(system).equals("go" + wanted)) return system;

		synchronized (LOCK) {
			return provisionGo(wanted);
		}
	}

	private String systemGoVersion(String go) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(go, "env", "GOVERSION").redirectErrorStream(true).start();
		String out = new String(process.getInputStream().readAllBytes()).strip();
		process.waitFor();
		return out.split("-")[0];
	}

	private String provisionGo(String version) throws IOException, InterruptedException {
		String platform = Project.hostPlatform();
		String[] parts = platform.split("-");
		Path home = project.userHome.resolve("toolchains").resolve("go" + version + "-" + platform);
		String binary = parts[0].equals("windows") ? "go.exe" : "go";
		Path go = home.resolve("go").resolve("bin").resolve(binary);
		if (Files.isExecutable(go)) return go.toString();

		String file = "go" + version + "." + parts[0] + "-" + parts[1] + (parts[0].equals("windows") ? ".zip" : ".tar.gz");
		String sha = goChecksum(version, file);
		Log.line("Downloading Go " + version + " for " + platform + "...");
		Path archive = Http.download("https://go.dev/dl/" + file, project.userHome.resolve("downloads").resolve(file), "sha256:" + sha);
		extract(archive, home);

		if (!Files.isExecutable(go)) throw new BuildException("Go archive did not contain " + go);
		return go.toString();
	}

	@SuppressWarnings("unchecked")
	private String goChecksum(String version, String file) throws IOException, InterruptedException {
		List<Object> releases = (List<Object>) Json.parse(Http.text("https://go.dev/dl/?mode=json&include=all"));
		for (Object release : releases) {
			Map<String, Object> entry = (Map<String, Object>) release;
			if (!("go" + version).equals(entry.get("version"))) continue;
			for (Object f : (List<Object>) entry.get("files")) {
				Map<String, Object> download = (Map<String, Object>) f;
				if (file.equals(download.get("filename"))) return (String) download.get("sha256");
			}
		}
		throw new BuildException("Go version " + version + " has no release named " + file + ". Check [go] version in build.toml.");
	}

	/**
	 * The CC value for a target, or null when the host has a compiler that already targets it. With
	 * none installed the host is built with Zig too, so a fresh checkout needs nothing but Java.
	 */
	String cCompiler(String target) throws IOException, InterruptedException {
		if (target.equals(Project.hostPlatform()) && (System.getenv("CC") != null || onPath("gcc") != null || onPath("clang") != null || onPath("cc") != null)) {
			return null;
		}

		synchronized (LOCK) {
			return provisionZig() + " cc -target " + zigTarget(target);
		}
	}

	static String zigTarget(String target) {
		String[] parts = target.split("-");
		String arch = parts[1].equals("arm64") ? "aarch64" : "x86_64";
		return switch (parts[0]) {
			case "windows" -> arch + "-windows-gnu";
			case "darwin" -> arch + "-macos";
			case "linux" -> arch + "-linux-gnu";
			default -> throw new BuildException("Unsupported target " + target);
		};
	}

	/**
	 * A macOS cross build has no SDK, and runtime/cgo links libresolv and, on arm64, CoreFoundation.
	 * These .tbd files are the same kind of stub Apple's SDK contains: they name libraries every Mac
	 * has, so nothing is bundled. Returns linker flags that make them visible.
	 */
	String macosStubFlags() throws IOException {
		Path dir = project.userHome.resolve("toolchains").resolve("macos-stubs-2");

		synchronized (LOCK) {
			writeStub(dir.resolve("libresolv.tbd"), "/usr/lib/libresolv.9.dylib",
					"_res_9_nclose, _res_9_ninit, _res_9_nsearch, _res_9_nquery");
			writeStub(dir.resolve("CoreFoundation.framework/CoreFoundation.tbd"),
					"/System/Library/Frameworks/CoreFoundation.framework/Versions/A/CoreFoundation",
					"_CFBundleCopyResourceURL, _CFBundleGetMainBundle, _CFBundleGetValueForInfoDictionaryKey, _CFRelease, "
							+ "_CFStringGetCString, _CFURLGetString, ___CFConstantStringClassReference");
		}
		return "-L" + dir + " -F" + dir;
	}

	private static void writeStub(Path file, String installName, String symbols) throws IOException {
		if (Files.exists(file)) return;

		Files2.writeAtomic(file, """
				--- !tapi-tbd
				tbd-version: 4
				targets: [ x86_64-macos, arm64-macos ]
				install-name: %s
				current-version: 1
				compatibility-version: 1
				exports:
				  - targets: [ x86_64-macos, arm64-macos ]
				    symbols: [ %s ]
				...
				""".formatted(installName, symbols).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@SuppressWarnings("unchecked")
	private String provisionZig() throws IOException, InterruptedException {
		String system = onPath("zig");
		if (system != null) return system;

		String version = project.string("go", "zig", "0.14.1");
		String platform = Project.hostPlatform();
		String[] parts = platform.split("-");
		String arch = parts[1].equals("arm64") ? "aarch64" : "x86_64";
		String os = parts[0].equals("darwin") ? "macos" : parts[0];

		Path home = project.userHome.resolve("toolchains").resolve("zig" + version + "-" + platform);
		String binary = parts[0].equals("windows") ? "zig.exe" : "zig";

		if (Files.isDirectory(home)) {
			try (var stream = Files.walk(home, 2)) {
				Path found = stream.filter(p -> p.getFileName().toString().equals(binary) && Files.isExecutable(p)).findFirst().orElse(null);
				if (found != null) return found.toString();
			}
		}

		Map<String, Object> index = Json.parseObject(Http.text("https://ziglang.org/download/index.json"));
		Map<String, Object> release = (Map<String, Object>) index.get(version);
		if (release == null) throw new BuildException("Zig version " + version + " not found. Check [go] zig in build.toml.");

		Map<String, Object> build = (Map<String, Object>) release.get(arch + "-" + os);
		if (build == null) throw new BuildException("Zig " + version + " has no build for " + arch + "-" + os);

		String url = (String) build.get("tarball");
		Log.line("Downloading Zig " + version + " to cross-compile cgo...");
		Path archive = Http.download(url, project.userHome.resolve("downloads").resolve(url.substring(url.lastIndexOf('/') + 1)), "sha256:" + build.get("shasum"));
		extract(archive, home);

		try (var stream = Files.walk(home, 2)) {
			return stream.filter(p -> p.getFileName().toString().equals(binary)).findFirst().orElseThrow(() -> new BuildException("Zig archive did not contain " + binary)).toString();
		}
	}

	private static void extract(Path archive, Path into) throws IOException, InterruptedException {
		Path tmp = into.resolveSibling(into.getFileName() + ".tmp");
		Files2.deleteTree(tmp);
		Files.createDirectories(tmp);

		String name = archive.getFileName().toString();
		if (name.endsWith(".zip")) {
			Files2.unzip(archive, tmp);
		} else {
			// The JDK has no xz or tar support, and every platform that gets here ships tar.
			String flags = name.endsWith(".xz") ? "xJf" : "xzf";
			Process process = new ProcessBuilder("tar", flags, archive.toString(), "-C", tmp.toString()).inheritIO().start();
			if (process.waitFor() != 0) throw new BuildException("Could not extract " + archive);
		}

		Files2.deleteTree(into);
		Files.move(tmp, into);
	}

	private static String onPath(String name) {
		String path = System.getenv("PATH");
		if (path == null) return null;

		List<String> candidates = new ArrayList<>(List.of(name));
		if (System.getProperty("os.name").toLowerCase().contains("win")) candidates.add(name + ".exe");

		for (String dir : path.split(java.io.File.pathSeparator)) {
			for (String candidate : candidates) {
				Path file = Path.of(dir).resolve(candidate);
				if (Files.isExecutable(file) && !Files.isDirectory(file)) return file.toString();
			}
		}
		return null;
	}
}
