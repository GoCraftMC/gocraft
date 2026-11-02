package com.go.craft.bridge;

import java.util.Arrays;

/**
 * Maps Java objects to the opaque 64-bit handles Go holds. Go releases them when its wrapper is
 * garbage collected. The generation in the high bits turns a use-after-release into an exception
 * instead of silently reading whichever object reused the slot.
 */
final class Handles {
	private static Object[] objects = new Object[4096];
	private static int[] generations = new int[4096];
	private static int[] free = new int[4096];
	private static int freeCount;
	private static int next = 1; // slot 0 is reserved so that handle 0 can mean null

	private Handles() { }

	static synchronized long put(Object value) {
		if (value == null) return 0;

		int slot;
		if (freeCount > 0) {
			slot = free[--freeCount];
		} else {
			if (next == objects.length) {
				objects = Arrays.copyOf(objects, next * 2);
				generations = Arrays.copyOf(generations, next * 2);
				free = Arrays.copyOf(free, next * 2);
			}
			slot = next++;
		}

		objects[slot] = value;
		return ((long) generations[slot] << 32) | slot;
	}

	static synchronized Object get(long handle) {
		if (handle == 0) return null;

		int slot = (int) handle;
		int generation = (int) (handle >>> 32);
		if (slot <= 0 || slot >= next || generations[slot] != generation || objects[slot] == null) {
			throw new IllegalStateException("Stale or invalid Go handle 0x" + Long.toHexString(handle));
		}
		return objects[slot];
	}

	static synchronized void release(long handle) {
		if (handle == 0) return;

		int slot = (int) handle;
		int generation = (int) (handle >>> 32);
		if (slot <= 0 || slot >= next || generations[slot] != generation || objects[slot] == null) return;

		objects[slot] = null;
		generations[slot]++;
		free[freeCount++] = slot;
	}

	static synchronized int liveCount() {
		return next - 1 - freeCount;
	}
}
