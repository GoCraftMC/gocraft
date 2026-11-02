package com.go.craft.gojar;

/** Console output. Task lines from parallel workers must not interleave mid-line. */
final class Log {
	private Log() { }

	static synchronized void line(String message) {
		System.out.println(message);
	}

	static synchronized void error(String message) {
		System.err.println(message);
	}
}
