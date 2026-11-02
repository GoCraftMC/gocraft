package com.go.craft.gojar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** Downloads with integrity checks and a content-addressed feel: a file on disk is never refetched. */
final class Http {
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(30))
			.build();
	private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

	private Http() { }

	static String text(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "go.jar (GoCraft)").timeout(Duration.ofMinutes(2)).build();
		HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new BuildException("GET " + url + " returned HTTP " + response.statusCode());
		return response.body();
	}

	/** Fetches url to target unless it is already there. sha may be null, "sha1:..." or "sha256:...". */
	static Path download(String url, Path target, String sha) throws IOException, InterruptedException {
		synchronized (LOCKS.computeIfAbsent(target, k -> new Object())) {
			if (Files.exists(target) && (sha == null || matches(target, sha))) return target;

			Files.createDirectories(target.getParent());
			Path tmp = Files2.tempFile(target.getParent());

			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "go.jar (GoCraft)").timeout(Duration.ofMinutes(20)).build();
				HttpResponse<Path> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
				if (response.statusCode() != 200) throw new BuildException("GET " + url + " returned HTTP " + response.statusCode());
				if (sha != null && !matches(tmp, sha)) throw new BuildException("Checksum mismatch for " + url);

				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				return target;
			} finally {
				Files.deleteIfExists(tmp);
			}
		}
	}

	private static boolean matches(Path file, String sha) throws IOException {
		int colon = sha.indexOf(':');
		String algorithm = sha.substring(0, colon);
		String expected = sha.substring(colon + 1);

		if (algorithm.equals("sha256")) return Files2.hashFile(file).equalsIgnoreCase(expected);

		try {
			var digest = java.security.MessageDigest.getInstance("SHA-1");
			digest.update(Files.readAllBytes(file));
			return Files2.hex(digest.digest()).equalsIgnoreCase(expected);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
