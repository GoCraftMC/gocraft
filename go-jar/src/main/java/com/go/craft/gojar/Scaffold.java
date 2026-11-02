package com.go.craft.gojar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Creates new projects and the wrapper scripts, the way 'gradle init' and 'gradle wrapper' do. */
final class Scaffold {
	private Scaffold() { }

	static void init(Path root, String id) throws IOException {
		if (!id.matches("[a-z][a-z0-9_-]{1,63}")) {
			throw new BuildException("Mod id must be lowercase letters, digits, - or _, starting with a letter: " + id);
		}
		if (Files.exists(root.resolve("build.toml"))) throw new BuildException("build.toml already exists here.");

		String pkg = id.replace("-", "").replace("_", "");
		write(root, "build.toml", """
				[project]
				id = "%s"
				version = "0.1.0"

				[minecraft]
				version = "26.3"
				loader = "0.20.1"
				fabric_api = "0.165.0+26.3"
				# Path to the GoCraft mod jar that runServer and runClient install.
				gocraft_jar = "libs/gocraft.jar"

				[go]
				# "system" uses Go from PATH. A version such as "1.26.1" is downloaded automatically.
				version = "system"
				# Each target becomes natives/<target>/ in the .gomod. Anything other than this machine's
				# platform is cross-compiled with an automatically downloaded Zig.
				targets = ["%s"]
				""".formatted(id, Project.hostPlatform()));
		write(root, "go.mod", """
				module example.com/%s

				go 1.26

				require github.com/GoCraftMC/gocraft-sdk v0.1.1
				""".formatted(pkg));
		write(root, "src/main/resources/gomod.json", """
				{
				  "id": "${id}",
				  "version": "${version}",
				  "name": "%s",
				  "description": "A Minecraft mod written in Go.",
				  "authors": [],
				  "license": "BSD-3-Clause",
				  "environment": "*",
				  "depends": {
				    "minecraft": ">=${minecraft_version}",
				    "gocraft": "*"
				  }
				}
				""".formatted(id));
		write(root, "src/main/resources/assets/" + id + "/lang/en_us.json", "{}\n");
		write(root, "src/main/go/mod/mod.go", """
				// Package mod is the entrypoint of %s. go.jar links in every package under src/main/go.
				package mod

				import "github.com/GoCraftMC/gocraft-sdk/gocraft"

				func init() {
					gocraft.OnInitialize(func() {
						gocraft.Log().Info("Hello from Go!")
					})
				}
				""".formatted(id));
		write(root, ".gitignore", "build/\n.gocraft/\nrun/\n");
		writeWrapper(root);

		Log.line("Created project '" + id + "' in " + root);
		Log.line("Next: put the GoCraft jar at libs/gocraft.jar, run './gow tidy', then './gow build'.");
	}

	static void writeWrapper(Path root) throws IOException {
		try {
			Path self = Path.of(Scaffold.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			Path target = root.resolve("go.jar");
			if (Files.isRegularFile(self) && !self.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
				Files.copy(self, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (java.net.URISyntaxException e) {
			throw new IOException(e);
		}

		Path sh = root.resolve("gow");
		Files.writeString(sh, """
				#!/bin/sh
				# go.jar wrapper: the one command a Go mod project needs. Requires Java 25.
				cd "$(dirname "$0")" || exit 1
				if [ -n "$JAVA_HOME" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
				exec "$JAVA" -jar go.jar "$@"
				""");
		try {
			Files.setPosixFilePermissions(sh, PosixFilePermissions.fromString("rwxr-xr-x"));
		} catch (UnsupportedOperationException ignored) {
			// Windows has no execute bit, and uses gow.bat.
		}

		Files.writeString(root.resolve("gow.bat"), """
				@echo off
				rem go.jar wrapper: the one command a Go mod project needs. Requires Java 25.
				cd /d "%~dp0"
				if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\\bin\\java") else (set "JAVA=java")
				"%JAVA%" -jar go.jar %*
				""");
	}

	private static void write(Path root, String relative, String content) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}
}
