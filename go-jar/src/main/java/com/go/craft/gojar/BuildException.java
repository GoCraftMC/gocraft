package com.go.craft.gojar;

/** A build failure with a message meant for the user; no stack trace unless --stacktrace. */
final class BuildException extends RuntimeException {
	BuildException(String message) {
		super(message);
	}

	BuildException(String message, Throwable cause) {
		super(message, cause);
	}
}
