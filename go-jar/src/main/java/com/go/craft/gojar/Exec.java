package com.go.craft.gojar;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Runs external commands, failing the task with the tool's own output when they fail. */
final class Exec {
	private Exec() { }

	/** Captures output, showing it only on failure, so parallel tasks do not interleave. */
	static String capture(Path dir, Map<String, String> env, List<String> command) throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
		builder.environment().putAll(env);
		Process process = builder.start();
		process.getOutputStream().close();
		String output = new String(process.getInputStream().readAllBytes());

		int code = process.waitFor();
		if (code != 0) throw new BuildException(String.join(" ", command) + " failed with exit code " + code + ":\n" + output);
		return output;
	}

	/** Attaches the terminal, for commands that talk to the user, and returns the exit code. */
	static int attached(Path dir, Map<String, String> env, List<String> command) throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).inheritIO();
		builder.environment().putAll(env);
		Process process = builder.start();

		// The child is the game: if go.jar is told to stop, it must not leave the game running.
		Thread stop = new Thread(process::destroy);
		Runtime.getRuntime().addShutdownHook(stop);
		try {
			return process.waitFor();
		} finally {
			Runtime.getRuntime().removeShutdownHook(stop);
		}
	}
}
