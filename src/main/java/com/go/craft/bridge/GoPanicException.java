package com.go.craft.bridge;

/** A Go panic that reached the bridge. The message carries the goroutine's stack trace. */
public final class GoPanicException extends RuntimeException {
	public GoPanicException(String modId, String message) {
		super("Go mod '" + modId + "' panicked: " + message);
	}
}
