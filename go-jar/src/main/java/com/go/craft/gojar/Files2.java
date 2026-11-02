package com.go.craft.gojar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** File helpers shared by the engine and tasks. */
final class Files2 {
	private Files2() { }

	static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

	static String hashFile(Path file) throws IOException {
		MessageDigest digest = sha256();
		byte[] buffer = new byte[65536];
		try (InputStream in = Files.newInputStream(file)) {
			int read;
			while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
		}
		return hex(digest.digest());
	}

	/** All regular files under path, sorted so hashes and archives are reproducible. */
	static List<Path> walk(Path path) throws IOException {
		if (!Files.exists(path)) return List.of();
		if (Files.isRegularFile(path)) return List.of(path);

		List<Path> files = new ArrayList<>();
		Files.walkFileTree(path, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
				boolean skip = !dir.equals(path) && (name.equals(".git") || name.equals(".gocraft"));
				return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile()) files.add(file);
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(files);
		return files;
	}

	static void deleteTree(Path path) throws IOException {
		if (!Files.exists(path)) return;
		Files.walkFileTree(path, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	static void copyTree(Path from, Path to) throws IOException {
		for (Path file : walk(from)) {
			Path target = to.resolve(from.relativize(file).toString());
			Files.createDirectories(target.getParent());
			Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** A sibling temp file with normal permissions: createTempFile alone leaves results owner-only. */
	static Path tempFile(Path dir) throws IOException {
		Path tmp = Files.createTempFile(dir, ".tmp", null);
		try {
			Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
		} catch (UnsupportedOperationException ignored) {
			// Windows permissions are inherited from the directory.
		}
		return tmp;
	}

	static void writeAtomic(Path target, byte[] bytes) throws IOException {
		Files.createDirectories(target.getParent());
		Path tmp = tempFile(target.getParent());
		Files.write(tmp, bytes);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Packs paths (stored relative to root) into a zip with fixed timestamps. */
	static void zipOutputs(List<Path> paths, Path root, Path zip) throws IOException {
		Files.createDirectories(zip.getParent());
		Path tmp = tempFile(zip.getParent());
		try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(out)) {
			for (Path path : paths) {
				for (Path file : walk(path)) {
					ZipEntry entry = new ZipEntry(root.relativize(file).toString().replace('\\', '/'));
					entry.setTime(0);
					z.putNextEntry(entry);
					Files.copy(file, z);
					z.closeEntry();
				}
			}
		}
		Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	static void unzip(Path zip, Path into) throws IOException {
		Path base = into.toAbsolutePath().normalize();
		try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
			ZipEntry entry;
			while ((entry = z.getNextEntry()) != null) {
				Path target = base.resolve(entry.getName()).normalize();
				if (!target.startsWith(base)) throw new IOException("Unsafe archive entry: " + entry.getName());
				if (entry.isDirectory()) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(z, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}
