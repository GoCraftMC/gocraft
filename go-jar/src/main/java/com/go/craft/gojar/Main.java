package com.go.craft.gojar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Command line entry point: {@code java -jar go.jar [flags] task...}. */
public final class Main {
	static final String VERSION = Main.class.getPackage().getImplementationVersion() == null ? "dev" : Main.class.getPackage().getImplementationVersion();

	private Main() { }

	public static void main(String[] args) {
		boolean stacktrace = List.of(args).contains("--stacktrace");
		try {
			System.exit(run(args));
		} catch (BuildException e) {
			Log.error("\nFAILURE: " + e.getMessage());
			if (stacktrace) e.printStackTrace();
			Log.error("\nBUILD FAILED");
			System.exit(1);
		} catch (Exception e) {
			Log.error("\nFAILURE: " + e);
			if (stacktrace) e.printStackTrace();
			System.exit(1);
		}
	}

	private static int run(String[] args) throws Exception {
		Map<String, String> flags = new LinkedHashMap<>();
		List<String> requested = new ArrayList<>();

		for (String arg : args) {
			if (arg.startsWith("--")) {
				int eq = arg.indexOf('=');
				if (eq > 0) flags.put(arg.substring(2, eq), arg.substring(eq + 1));
				else flags.put(arg.substring(2), "true");
			} else if (arg.equals("-t")) {
				flags.put("continuous", "true");
			} else {
				requested.add(arg);
			}
		}

		if (flags.containsKey("version")) {
			Log.line("go.jar " + VERSION + " on Java " + System.getProperty("java.version"));
			return 0;
		}
		if (flags.containsKey("help")) {
			usage();
			return 0;
		}

		Path root = Path.of(flags.getOrDefault("project-dir", ".")).toAbsolutePath().normalize();

		if (!requested.isEmpty() && requested.getFirst().equals("init")) {
			String id = requested.size() > 1 ? requested.get(1) : root.getFileName().toString().toLowerCase();
			Scaffold.init(root, id);
			return 0;
		}

		Project project = new Project(root);
		Map<String, Task> tasks = new BuiltinTasks(project, flags).all();

		if (requested.isEmpty()) requested = List.of("tasks");
		if (requested.equals(List.of("tasks"))) {
			printTasks(tasks);
			return 0;
		}

		int workers = Integer.parseInt(flags.getOrDefault("workers", String.valueOf(Runtime.getRuntime().availableProcessors())));
		Engine.Options options = new Engine.Options(flags.containsKey("rerun"), flags.containsKey("no-cache"), flags.containsKey("dry-run"), flags.containsKey("continue"), workers, flags.containsKey("stacktrace"));

		if (flags.containsKey("continuous")) return continuous(project, tasks, options, requested);
		return once(project, tasks, options, requested, flags);
	}

	private static int once(Project project, Map<String, Task> tasks, Engine.Options options, List<String> requested, Map<String, String> flags) throws Exception {
		long start = System.nanoTime();
		Engine engine = new Engine(project, tasks, options);
		List<Task> plan = engine.plan(requested);
		boolean ok = engine.run(plan);

		for (Engine.Result result : engine.results()) {
			if (result.outcome() != Engine.Outcome.FAILED) continue;
			Throwable error = result.error();
			Log.error("\nFAILURE: Task :" + result.task().name + " failed.\n" + error.getMessage());
			if (options.stacktrace() || !(error instanceof BuildException)) error.printStackTrace();
		}

		long millis = (System.nanoTime() - start) / 1_000_000;
		summarize(ok, engine.results(), millis);
		if (flags.containsKey("profile")) writeProfile(project, engine.results(), millis);
		return ok ? 0 : 1;
	}

	private static void summarize(boolean ok, List<Engine.Result> results, long millis) {
		long executed = results.stream().filter(r -> r.outcome() == Engine.Outcome.EXECUTED).count();
		long upToDate = results.stream().filter(r -> r.outcome() == Engine.Outcome.UP_TO_DATE).count();
		long cached = results.stream().filter(r -> r.outcome() == Engine.Outcome.FROM_CACHE).count();

		Log.line("\nBUILD " + (ok ? "SUCCESSFUL" : "FAILED") + " in " + human(millis));
		Log.line((executed + upToDate + cached) + " actionable tasks: " + executed + " executed, " + cached + " from cache, " + upToDate + " up-to-date");
	}

	private static String human(long millis) {
		return millis < 1000 ? millis + "ms" : String.format("%.1fs", millis / 1000.0);
	}

	/** Rebuilds whenever an input of the planned tasks changes, like 'gradle -t'. */
	private static int continuous(Project project, Map<String, Task> tasks, Engine.Options options, List<String> requested) throws Exception {
		Engine probe = new Engine(project, tasks, options);
		List<Task> plan = probe.plan(requested);
		String last = null;
		Log.line("Waiting for changes. Press Ctrl+C to stop.");

		while (true) {
			StringBuilder fingerprint = new StringBuilder();
			for (Task task : plan) {
				if (!task.inputs.isEmpty()) fingerprint.append(probe.fingerprint(task));
			}

			if (!fingerprint.toString().equals(last)) {
				last = fingerprint.toString();
				long start = System.nanoTime();
				Engine engine = new Engine(project, tasks, options);
				boolean ok = engine.run(plan);

				for (Engine.Result result : engine.results()) {
					if (result.outcome() == Engine.Outcome.FAILED) Log.error("\nFAILURE: Task :" + result.task().name + " failed.\n" + result.error().getMessage());
				}
				summarize(ok, engine.results(), (System.nanoTime() - start) / 1_000_000);
				Log.line("\nWaiting for changes. Press Ctrl+C to stop.");
			}
			Thread.sleep(500);
		}
	}

	private static void writeProfile(Project project, List<Engine.Result> results, long totalMillis) throws IOException {
		StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>go.jar build profile</title><style>")
				.append("body{font:14px system-ui;margin:2rem}table{border-collapse:collapse}td,th{padding:.25rem .75rem;text-align:left;border-bottom:1px solid #ddd}td.n{text-align:right;font-variant-numeric:tabular-nums}")
				.append("</style></head><body><h1>Build profile</h1><p>Total: ").append(human(totalMillis)).append("</p><table><tr><th>Task</th><th>Outcome</th><th>Time</th></tr>");

		List<Engine.Result> sorted = new ArrayList<>(results);
		sorted.sort(Comparator.comparingLong(Engine.Result::millis).reversed());
		for (Engine.Result result : sorted) {
			html.append("<tr><td>").append(result.task().name).append("</td><td>").append(result.outcome()).append("</td><td class=\"n\">").append(human(result.millis())).append("</td></tr>");
		}
		html.append("</table></body></html>\n");

		Path file = project.build().resolve("reports/profile.html");
		Files2.writeAtomic(file, html.toString().getBytes(StandardCharsets.UTF_8));
		Log.line("Profile: " + file);
	}

	private static void printTasks(Map<String, Task> tasks) {
		Map<String, List<Task>> groups = new TreeMap<>();
		for (Task task : tasks.values()) {
			if (!task.name.startsWith("compileGo-")) groups.computeIfAbsent(task.group, k -> new ArrayList<>()).add(task);
		}
		for (Map.Entry<String, List<Task>> group : groups.entrySet()) {
			Log.line(capitalize(group.getKey()) + " tasks");
			Log.line("-".repeat(group.getKey().length() + 6));
			for (Task task : group.getValue()) Log.line(task.name + (task.description.isEmpty() ? "" : " - " + task.description));
			Log.line("");
		}
	}

	private static String capitalize(String value) {
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	private static void usage() {
		Log.line("""
				go.jar %s: the build tool for GoCraft mods.

				Usage: ./gow [flags] task...
				       java -jar go.jar init <mod-id>

				Flags:
				  --rerun        Run tasks even if they are up to date.
				  --no-cache     Do not read or write the build cache.
				  --dry-run      Show which tasks would run.
				  --continue     Keep going after a task fails.
				  --workers=N    Limit parallel tasks. Defaults to the CPU count.
				  --profile      Write a timing report to build/reports/profile.html.
				  -t             Rebuild whenever inputs change.
				  --accept-eula  Accept the Minecraft EULA for runServer.
				  --stacktrace   Show stack traces for failures.
				  --project-dir=DIR  Build a project in another directory.
				  --version      Show the version.
				""".formatted(VERSION));
	}
}
