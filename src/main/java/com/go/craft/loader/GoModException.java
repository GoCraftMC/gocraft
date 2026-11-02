package com.go.craft.loader;

/** A .gomod could not be loaded. The message is shown to players, so it should say what to do. */
public final class GoModException extends RuntimeException {
	public GoModException(String message) {
		super(message);
	}

	public GoModException(String message, Throwable cause) {
		super(message, cause);
	}
}
