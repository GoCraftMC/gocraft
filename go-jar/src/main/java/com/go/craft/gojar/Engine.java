package com.go.craft.gojar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Resolves the task graph and runs it in parallel, skipping work that is already done. */
final class Engine {
	enum Outcome { EXECUTED, UP_TO_DATE, FROM_CACHE, NO_ACTION, FAILED }

	record Result(Task task, Outcome outcome, long millis, Throwable error) { }

	record Options(boolean rerun, boolean noCache, boolean dryRun, boolean keepGoing, int workers, boolean stacktrace) { }

	private final Project project;
	private final Map<String, Task> tasks;
	private final Options options;
	private final List<Result> results = new ArrayList<>();

	Engine(Project project, Map<String, Task> tasks, Options options) {
		this.project = project;
		this.tasks = tasks;
		this.options = options;
	}

	List<Result> results() {
		return results;
	}

	/** Dependency-ordered closure of the requested tasks. */
	List<Task> plan(List<String> requested) {
		Map<String, Task> ordered = new LinkedHashMap<>();
		Set<String> visiting = new HashSet<>();
		for (String name : requested) visit(resolve(name), ordered, visiting, new ArrayList<>());
		return new ArrayList<>(ordered.values());
	}

	private Task resolve(String name) {
		Task task = tasks.get(name);
		if (task != null) return task;

		// Like Gradle, allow an unambiguous prefix: 'runS' for runServer.
		List<String> matches = tasks.keySet().stream().filter(t -> t.startsWith(name)).toList();
		if (matches.size() == 1) return tasks.get(matches.get(0));
		if (matches.isEmpty()) throw new BuildException("Task '" + name + "' not found. Run 'tasks' to list them.");
		throw new BuildException("Task '" + name + "' is ambiguous: " + String.join(", ", matches));
	}

	private void visit(Task task, Map<String, Task> ordered, Set<String> visiting, List<String> path) {
		if (ordered.containsKey(task.name)) return;
		path.add(task.name);
		if (!visiting.add(task.name)) throw new BuildException("Task dependency cycle: " + String.join(" -> ", path));
		for (String dep : task.dependsOn) visit(resolve(dep), ordered, visiting, path);
		visiting.remove(task.name);
		path.remove(path.size() - 1);
		ordered.put(task.name, task);
	}

	/**
	 * Runs requested tasks one after another, each with its dependencies in parallel, so that
	 * 'clean build' cannot delete what the build is writing. A task runs at most once.
	 */
	boolean runRequested(List<String> requested) throws InterruptedException {
		Map<String, Task> done = new LinkedHashMap<>();
		for (String name : requested) {
			Map<String, Task> ordered = new LinkedHashMap<>();
			visit(resolve(name), ordered, new HashSet<>(), new ArrayList<>());
			List<Task> group = ordered.values().stream().filter(t -> !done.containsKey(t.name)).toList();
			if (!run(group)) return false;
			for (Task task : group) done.put(task.name, task);
		}
		return true;
	}

	/** Returns false if any task failed. */
	boolean run(List<Task> plan) throws InterruptedException {
		if (options.dryRun) {
			for (Task task : plan) Log.line("> Task :" + task.name + " SKIPPED (dry run)");
			return true;
		}

		List<Task> background = plan.stream().filter(t -> !t.interactive).toList();
		boolean ok = runParallel(background);
		if (!ok) return false;

		// Terminal-attached tasks such as runServer go last and alone.
		for (Task task : plan) {
			if (!task.interactive) continue;
			Result result = execute(task);
			record(result);
			if (result.outcome == Outcome.FAILED) return false;
		}
		return true;
	}

	private boolean runParallel(List<Task> plan) throws InterruptedException {
		Map<String, Integer> waitingOn = new HashMap<>();
		Map<String, List<Task>> dependents = new HashMap<>();
		Set<String> planned = new HashSet<>();
		for (Task task : plan) planned.add(task.name);

		for (Task task : plan) {
			int count = 0;
			for (String dep : task.dependsOn) {
				String resolved = resolve(dep).name;
				if (planned.contains(resolved)) {
					count++;
					dependents.computeIfAbsent(resolved, k -> new ArrayList<>()).add(task);
				}
			}
			waitingOn.put(task.name, count);
		}

		ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, options.workers));
		CompletionService<Result> completion = new ExecutorCompletionService<>(pool);
		int running = 0;
		boolean failed = false;

		try {
			for (Task task : plan) {
				if (waitingOn.get(task.name) == 0) {
					completion.submit(() -> execute(task));
					running++;
				}
			}

			while (running > 0) {
				Future<Result> done = completion.take();
				running--;

				Result result;
				try {
					result = done.get();
				} catch (java.util.concurrent.ExecutionException e) {
					throw new IllegalStateException(e.getCause());
				}
				record(result);

				if (result.outcome == Outcome.FAILED) {
					failed = true;
					if (!options.keepGoing) continue;
				}
				if (result.outcome == Outcome.FAILED || (failed && !options.keepGoing)) continue;

				for (Task next : dependents.getOrDefault(result.task.name, List.of())) {
					int remaining = waitingOn.merge(next.name, -1, Integer::sum);
					if (remaining == 0) {
						completion.submit(() -> execute(next));
						running++;
					}
				}
			}
		} finally {
			pool.shutdownNow();
		}
		return !failed;
	}

	private synchronized void record(Result result) {
		results.add(result);
		switch (result.outcome) {
			case EXECUTED, NO_ACTION -> Log.line("> Task :" + result.task.name);
			case UP_TO_DATE -> Log.line("> Task :" + result.task.name + " UP-TO-DATE");
			case FROM_CACHE -> Log.line("> Task :" + result.task.name + " FROM-CACHE");
			case FAILED -> Log.line("> Task :" + result.task.name + " FAILED");
		}
	}

	private Result execute(Task task) {
		long start = System.nanoTime();
		try {
			if (task.action == null) return new Result(task, Outcome.NO_ACTION, 0, null);

			String fingerprint = null;
			Path stateFile = project.state().resolve("state").resolve(task.name + ".txt");

			if (task.trackable()) {
				fingerprint = fingerprint(task);

				if (!options.rerun) {
					if (Files.exists(stateFile) && Files.readString(stateFile).equals(fingerprint + "\n" + hash(task.outputs))) {
						return new Result(task, Outcome.UP_TO_DATE, elapsed(start), null);
					}

					Path cached = cacheEntry(fingerprint);
					if (task.cacheable && !options.noCache && Files.exists(cached)) {
						for (Path output : task.outputs) Files2.deleteTree(output);
						Files2.unzip(cached, project.root);
						writeState(stateFile, fingerprint, task);
						return new Result(task, Outcome.FROM_CACHE, elapsed(start), null);
					}
				}
			}

			task.action.run(task);

			if (task.trackable()) {
				// An action may generate files that are also its inputs' siblings; hash after running.
				writeState(stateFile, fingerprint, task);
				if (task.cacheable && !options.noCache) {
					Files2.zipOutputs(task.outputs, project.root, cacheEntry(fingerprint));
				}
			}
			return new Result(task, Outcome.EXECUTED, elapsed(start), null);
		} catch (Throwable t) {
			return new Result(task, Outcome.FAILED, elapsed(start), t);
		}
	}

	private static long elapsed(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	private Path cacheEntry(String fingerprint) {
		return project.userHome.resolve("build-cache").resolve(fingerprint.substring(0, 2)).resolve(fingerprint + ".zip");
	}

	private void writeState(Path stateFile, String fingerprint, Task task) throws IOException {
		Files2.writeAtomic(stateFile, (fingerprint + "\n" + hash(task.outputs)).getBytes(StandardCharsets.UTF_8));
	}

	/** Includes the task's name and project-relative paths, so cache hits work across checkouts. */
	String fingerprint(Task task) throws IOException {
		MessageDigest digest = Files2.sha256();
		update(digest, "task:" + task.name);

		for (Map.Entry<String, String> property : task.properties.entrySet()) {
			update(digest, "prop:" + property.getKey() + "=" + property.getValue());
		}
		for (Path input : task.inputs) {
			for (Path file : Files2.walk(input)) {
				update(digest, "in:" + relative(file) + "=" + Files2.hashFile(file));
			}
		}
		return Files2.hex(digest.digest());
	}

	private String hash(List<Path> paths) throws IOException {
		MessageDigest digest = Files2.sha256();
		for (Path path : paths) {
			for (Path file : Files2.walk(path)) {
				update(digest, relative(file) + "=" + Files2.hashFile(file));
			}
		}
		return Files2.hex(digest.digest());
	}

	private String relative(Path file) {
		return file.startsWith(project.root) ? project.root.relativize(file).toString().replace('\\', '/') : file.toString();
	}

	private static void update(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) 0);
	}
}
