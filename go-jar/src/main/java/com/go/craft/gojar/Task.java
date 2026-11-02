package com.go.craft.gojar;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A unit of work. Declaring inputs and outputs is what buys up-to-date checks and the build cache:
 * a task with outputs and an unchanged fingerprint is skipped, and a cacheable one restored.
 */
final class Task {
	interface Action {
		void run(Task task) throws Exception;
	}

	final String name;
	String description = "";
	String group = "other";
	final List<String> dependsOn = new ArrayList<>();
	final List<Path> inputs = new ArrayList<>();
	final List<Path> outputs = new ArrayList<>();
	final Map<String, String> properties = new LinkedHashMap<>();
	boolean cacheable;
	/** Runs on the main thread with the terminal attached, after everything else. */
	boolean interactive;
	Action action;

	Task(String name) {
		this.name = name;
	}

	Task describe(String group, String description) {
		this.group = group;
		this.description = description;
		return this;
	}

	Task depends(String... names) {
		dependsOn.addAll(List.of(names));
		return this;
	}

	Task input(Path... paths) {
		inputs.addAll(List.of(paths));
		return this;
	}

	Task output(Path... paths) {
		outputs.addAll(List.of(paths));
		return this;
	}

	Task property(String key, String value) {
		properties.put(key, String.valueOf(value));
		return this;
	}

	Task does(Action action) {
		this.action = action;
		return this;
	}

	boolean trackable() {
		return !outputs.isEmpty();
	}
}
