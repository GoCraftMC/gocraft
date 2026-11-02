package com.go.craft.gojar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The standard lifecycle every Go mod gets without configuring anything. */
final class BuiltinTasks {
	/** Must match {@code GoCraft.ABI_VERSION} in the loader. */
	static final int ABI_VERSION = 1;
	private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
	private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

	private final Project project;
	private final Toolchains toolchains;
	private final Map<String, Task> tasks = new LinkedHashMap<>();
	private final Map<String, String> flags;

	BuiltinTasks(Project project, Map<String, String> flags) {
		this.project = project;
		this.toolchains = new Toolchains(project);
		this.flags = flags;
	}

	private Task task(String name) {
		Task task = new Task(name);
		if (tasks.put(name, task) != null) throw new BuildException("Duplicate task " + name);
		return task;
	}

	Map<String, Task> all() {
		Path mainResources = project.root.resolve("src/main/resources");
		Path clientResources = project.root.resolve("src/client/resources");
		Path generatedResources = project.build().resolve("resources");
		// The underscore hides it from Go's wildcards. go mod tidy would otherwise read this stale file, and
		// fail on a package that was just renamed or deleted, until the next build regenerated it.
		Path generatedMain = project.build().resolve("_generated/gocraftmain");

		task("processResources").describe("build", "Expands ${tokens} in gomod.json and collects resources.")
				.input(mainResources, clientResources, project.root.resolve("build.toml"))
				.output(generatedResources)
				.does(t -> processResources(mainResources, clientResources, generatedResources));

		boolean hasBindings = !project.list("bindings", "include").isEmpty();
		Path bindingsDir = project.root.resolve("src/generated/go");
		if (hasBindings) {
			Task gen = task("genBindings").describe("minecraft", "Generates typed Go bindings for the classes under [bindings].")
					.property("minecraft", project.minecraft)
					.property("include", String.valueOf(project.list("bindings", "include")))
					.property("generator", String.valueOf(Bindings.VERSION))
					.input(project.root.resolve("build.toml"))
					.output(bindingsDir);
			gen.does(t -> {
				new Bindings(project).generate(bindingsDir);
				Exec.capture(project.root, Map.of(), List.of(toolchains.go(), "fmt", "./src/generated/..."));
			});
		}

		task("genMain").describe("build", "Generates the package main that links in your entrypoint packages.")
				.input(project.root.resolve("src/main/go"), project.root.resolve("src/client/go"), project.root.resolve("go.mod"), mainResources.resolve("gomod.json"))
				.output(generatedMain)
				.does(t -> generateMain(generatedMain));

		List<Path> goInputs = new ArrayList<>(List.of(project.root.resolve("src"), project.root.resolve("go.mod"), project.root.resolve("go.sum")));
		goInputs.add(generatedMain);
		goInputs.addAll(project.goModReplacements());

		List<String> compileTasks = new ArrayList<>();
		for (String target : project.targets) {
			String name = "compileGo-" + target;
			compileTasks.add(name);
			Path output = project.build().resolve("natives").resolve(target);

			Task compile = task(name).describe("build", "Compiles the native library for " + target + ".")
					.depends("genMain")
					.property("target", target)
					.property("go", project.goVersion)
					.property("gojar", Main.VERSION)
					.property("flags", String.valueOf(project.list("go", "build_flags")))
					.input(goInputs.toArray(new Path[0]))
					.output(output);
			compile.cacheable = true;
			if (hasBindings) compile.depends("genBindings");
			compile.does(t -> compile(target, output, generatedMain));
		}

		task("compileGo").describe("build", "Compiles native libraries for every target.").depends(compileTasks.toArray(new String[0]));

		Path gomod = project.build().resolve("libs").resolve(project.id + "-" + project.version + ".gomod");
		task("gomod").describe("build", "Packages the mod into a .gomod file.")
				.depends("processResources", "compileGo")
				.input(generatedResources, project.build().resolve("natives"))
				.output(gomod)
				.does(t -> packageMod(generatedResources, project.build().resolve("natives"), gomod));

		Map<String, String> noEnv = Map.of();

		Task vet = task("vet").describe("verification", "Runs go vet.").depends("genMain");
		vet.does(t -> Exec.capture(project.root, goEnv(Project.hostPlatform()), List.of(toolchains.go(), "vet", "./src/...")));

		Task test = task("test").describe("verification", "Runs Go tests that do not need the game.").depends("genMain");
		test.does(t -> Log.line(Exec.capture(project.root, goEnv(Project.hostPlatform()), List.of(toolchains.go(), "test", "./src/...")).strip()));

		if (hasBindings) {
			vet.depends("genBindings");
			test.depends("genBindings");
		}

		task("fmt").describe("verification", "Formats Go sources with gofmt.")
				.does(t -> Exec.capture(project.root, noEnv, List.of(toolchains.go(), "fmt", "./src/...")));

		task("check").describe("verification", "Runs all verification tasks.").depends("vet", "test");
		task("assemble").describe("build", "Builds the .gomod without running verification.").depends("gomod");
		task("build").describe("build", "Assembles and verifies the mod.").depends("gomod", "check");

		task("clean").describe("build", "Deletes the build directory and task state.").does(t -> {
			Files2.deleteTree(project.build());
			Files2.deleteTree(project.state().resolve("state"));
		});

		task("dependencies").describe("help", "Shows the Go module graph.")
				.does(t -> Log.line(Exec.capture(project.root, noEnv, List.of(toolchains.go(), "list", "-m", "all")).strip()));
		task("tidy").describe("help", "Runs go mod tidy.")
				.does(t -> Exec.capture(project.root, noEnv, List.of(toolchains.go(), "mod", "tidy")));

		Runs runs = new Runs(project, flags);
		Task server = task("runServer").describe("minecraft", "Starts a dedicated server with this mod in run/server.").depends("gomod");
		server.interactive = true;
		server.does(t -> runs.server(gomod));

		Task client = task("runClient").describe("minecraft", "Starts the game client with this mod in run/client.").depends("gomod");
		client.interactive = true;
		client.does(t -> runs.client(gomod));

		task("prepareClient").describe("minecraft", "Downloads everything runClient needs, without starting the game.")
				.does(t -> Log.line("Client ready: " + runs.prepareClient().classpath().size() + " classpath entries"));

		task("wrapper").describe("help", "Writes the gow launcher scripts into the project.").does(t -> Scaffold.writeWrapper(project.root));

		addCustomTasks();
		return tasks;
	}

	/** [tasks.name] tables declare tasks in build.toml; inputs and outputs make them incremental. */
	@SuppressWarnings("unchecked")
	private void addCustomTasks() {
		for (Map.Entry<String, Object> entry : project.table("tasks").entrySet()) {
			if (!(entry.getValue() instanceof Map<?, ?>)) throw new BuildException("build.toml: tasks." + entry.getKey() + " must be a table");

			String name = entry.getKey();
			Map<String, Object> table = (Map<String, Object>) entry.getValue();
			Task task = tasks.containsKey(name) ? tasks.get(name) : task(name).describe("custom", String.valueOf(table.getOrDefault("description", "")));

			for (String dep : customList(table, "depends_on")) task.depends(dep);
			for (String input : customList(table, "inputs")) task.input(project.root.resolve(input));
			for (String output : customList(table, "outputs")) task.output(project.root.resolve(output));

			Object exec = table.get("exec");
			if (exec != null) {
				List<String> command = exec instanceof List<?> ? customList(table, "exec") : List.of("sh", "-c", String.valueOf(exec));
				task.property("exec", String.valueOf(command));
				Task.Action previous = task.action;
				task.does(t -> {
					if (previous != null) previous.run(t);
					Log.line(Exec.capture(project.root, Map.of(), command).strip());
				});
			}
			// Attaching custom work to a lifecycle task: [tasks.build] after = ["myTask"].
			for (String after : customList(table, "finalizes")) {
				Task lifecycle = tasks.get(after);
				if (lifecycle == null) throw new BuildException("build.toml: tasks." + name + " finalizes unknown task " + after);
				lifecycle.depends(name);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static List<String> customList(Map<String, Object> table, String key) {
		Object value = table.get(key);
		if (value == null) return List.of();
		if (value instanceof List<?>) return ((List<Object>) value).stream().map(String::valueOf).toList();
		return List.of(String.valueOf(value));
	}

	private Map<String, String> tokens() {
		Map<String, String> tokens = new LinkedHashMap<>();
		tokens.put("id", project.id);
		tokens.put("version", project.version);
		tokens.put("minecraft_version", project.minecraft);
		tokens.put("loader_version", project.loader);
		for (Map.Entry<String, Object> property : project.table("properties").entrySet()) {
			tokens.put(property.getKey(), String.valueOf(property.getValue()));
		}
		return tokens;
	}

	private String expand(String text) {
		Map<String, String> tokens = tokens();
		Matcher matcher = TOKEN.matcher(text);
		StringBuilder out = new StringBuilder();

		while (matcher.find()) {
			String value = tokens.get(matcher.group(1));
			if (value == null) throw new BuildException("gomod.json uses unknown token ${" + matcher.group(1) + "}. Define it under [properties] in build.toml.");
			matcher.appendReplacement(out, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	private void processResources(Path main, Path client, Path out) throws IOException {
		Files2.deleteTree(out);
		Files.createDirectories(out);
		Files2.copyTree(main, out);
		Files2.copyTree(client, out);

		Path manifest = out.resolve("gomod.json");
		if (!Files.exists(manifest)) throw new BuildException("Missing src/main/resources/gomod.json");

		Map<String, Object> json;
		try {
			json = Json.parseObject(expand(Files.readString(manifest)));
		} catch (ClassCastException e) {
			throw new BuildException("gomod.json must be a JSON object");
		}

		validate(json);
		json.put("abi", (long) ABI_VERSION);
		Files.writeString(manifest, Json.write(json));
	}

	private void validate(Map<String, Object> json) {
		List<String> problems = new ArrayList<>();
		Object id = json.get("id");

		if (!(id instanceof String s) || !MOD_ID.matcher(s).matches()) problems.add("'id' must match " + MOD_ID.pattern());
		else if (!s.equals(project.id)) problems.add("'id' is '" + s + "' but build.toml says '" + project.id + "'. Use \"${id}\".");
		if (!(json.get("version") instanceof String)) problems.add("'version' is required. Use \"${version}\".");

		Object environment = json.get("environment");
		if (environment != null && !List.of("*", "client", "server").contains(environment)) problems.add("'environment' must be *, client or server");

		if (json.get("hooks") instanceof List<?> hooks) {
			Set<String> ids = new LinkedHashSet<>();
			for (Object hook : hooks) {
				if (!(hook instanceof Map<?, ?> map)) {
					problems.add("each hook must be an object");
					continue;
				}
				for (String key : List.of("id", "target", "method", "desc")) {
					if (!map.containsKey(key)) problems.add("hook " + map.get("id") + " is missing '" + key + "'");
				}
				if (!ids.add(String.valueOf(map.get("id")))) problems.add("duplicate hook id " + map.get("id"));
			}
		}

		if (!problems.isEmpty()) throw new BuildException("Invalid gomod.json:\n - " + String.join("\n - ", problems));
	}

	/**
	 * c-shared needs a package main, so mod authors never write one: each package under src/main/go
	 * and src/client/go is linked in, and registers its entrypoints from init.
	 */
	@SuppressWarnings("unchecked")
	private void generateMain(Path out) throws IOException {
		String module = project.goModule();
		Set<String> packages = new LinkedHashSet<>();

		Path manifest = project.root.resolve("src/main/resources/gomod.json");
		Map<String, Object> json = Files.exists(manifest) ? Json.parseObject(expand(Files.readString(manifest))) : Map.of();

		if (json.get("entrypoints") instanceof Map<?, ?> declared) {
			addDeclared((Map<String, Object>) declared, "main", "src/main/go", module, packages);
			addDeclared((Map<String, Object>) declared, "client", "src/client/go", module, packages);
		} else {
			addScanned(project.root.resolve("src/main/go"), module, packages);
			addScanned(project.root.resolve("src/client/go"), module, packages);
		}

		if (packages.isEmpty()) throw new BuildException("No Go packages found under src/main/go or src/client/go.");

		StringBuilder source = new StringBuilder("// Code generated by go.jar. DO NOT EDIT.\npackage main\n\nimport (\n");
		for (String pkg : packages) source.append("\t_ \"").append(pkg).append("\"\n");
		source.append(")\n\nfunc main() {}\n");

		Files2.deleteTree(out);
		Files2.writeAtomic(out.resolve("main.go"), source.toString().getBytes(StandardCharsets.UTF_8));
	}

	private void addDeclared(Map<String, Object> declared, String key, String root, String module, Set<String> packages) throws IOException {
		Object value = declared.get(key);
		if (value == null) return;

		List<?> list = value instanceof List<?> l ? l : List.of(value);
		for (Object item : list) {
			Path dir = project.root.resolve(root).resolve(String.valueOf(item));
			if (!Files.isDirectory(dir)) throw new BuildException("Entrypoint package not found: " + project.root.relativize(dir));
			packages.add(importPath(module, dir));
		}
	}

	private void addScanned(Path root, String module, Set<String> packages) throws IOException {
		for (Path file : Files2.walk(root)) {
			String name = file.getFileName().toString();
			if (!name.endsWith(".go") || name.endsWith("_test.go")) continue;

			boolean linkable = true;
			for (Path part : root.relativize(file.getParent())) {
				String element = part.toString();
				// Go forbids importing internal packages from outside, and ignores the rest. Whichever
				// package uses an internal one links it anyway.
				if (element.equals("internal") || element.equals("testdata") || element.startsWith("_") || element.startsWith(".")) linkable = false;
			}
			if (linkable) packages.add(importPath(module, file.getParent()));
		}
	}

	private String importPath(String module, Path dir) {
		return module + "/" + project.root.relativize(dir).toString().replace('\\', '/');
	}

	/** The environment for any go command that compiles this project's cgo for a target. */
	private Map<String, String> goEnv(String target) throws IOException, InterruptedException {
		String[] parts = target.split("-");
		Map<String, String> env = new LinkedHashMap<>();
		env.put("CGO_ENABLED", "1");
		env.put("GOOS", parts[0]);
		env.put("GOARCH", parts[1]);
		if (!project.goVersion.equals("system")) env.put("GOTOOLCHAIN", "local");

		String cc = toolchains.cCompiler(target);
		if (cc != null) env.put("CC", cc);
		if (cc != null && parts[0].equals("darwin")) env.put("CGO_LDFLAGS", toolchains.macosStubFlags());
		return env;
	}

	private void compile(String target, Path output, Path generatedMain) throws IOException, InterruptedException {
		String[] parts = target.split("-");
		if (parts.length != 2) throw new BuildException("Target must look like linux-amd64, got " + target);

		Map<String, String> env = goEnv(target);
		String cc = env.get("CC");

		Files2.deleteTree(output);
		Files.createDirectories(output);
		Path library = output.resolve("mod." + Project.libraryExtension(target));

		List<String> command = new ArrayList<>(List.of(toolchains.go(), "build", "-buildmode=c-shared", "-trimpath", "-ldflags=-s -w", "-o", library.toString()));
		// A cross build has no macOS SDK to link cgo's resolver and user lookup against. The pure Go
		// versions need none, and a build on a real Mac keeps the system ones.
		if (cc != null && parts[0].equals("darwin")) command.add("-tags=netgo,osusergo");
		command.addAll(project.list("go", "build_flags"));
		command.add("./" + project.root.relativize(generatedMain).toString().replace('\\', '/'));

		Exec.capture(project.root, env, command);
		// The generated C header describes exports only the loader calls; it is not part of the mod.
		Files.deleteIfExists(output.resolve("mod.h"));
	}

	private void packageMod(Path resources, Path natives, Path out) throws IOException {
		Map<String, Path> entries = new java.util.TreeMap<>();
		for (Path file : Files2.walk(resources)) entries.put(resources.relativize(file).toString().replace('\\', '/'), file);

		for (String target : project.targets) {
			Path dir = natives.resolve(target);
			String library = "mod." + Project.libraryExtension(target);
			Path file = dir.resolve(library);
			if (!Files.exists(file)) throw new BuildException("Missing compiled library for " + target);
			entries.put("natives/" + target + "/" + library, file);
		}

		Path license = project.root.resolve("LICENSE");
		if (Files.exists(license)) entries.put("LICENSE", license);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, Path> entry : entries.entrySet()) {
				ZipEntry zipEntry = new ZipEntry(entry.getKey());
				zipEntry.setTime(0);
				zip.putNextEntry(zipEntry);
				Files.copy(entry.getValue(), zip);
				zip.closeEntry();
			}
		}

		Files2.writeAtomic(out, bytes.toByteArray());
		Log.line("Built " + project.root.relativize(out) + " (" + bytes.size() / 1024 + " KiB, " + String.join(", ", project.targets) + ")");
	}
}
