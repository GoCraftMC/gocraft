package com.go.craft.bridge;

/** Converts between Java values and the raw 64-bit slots exchanged with Go. */
final class Slots {
	private Slots() { }

	static char kind(Class<?> type) {
		if (!type.isPrimitive()) return 'L';
		if (type == int.class) return 'I';
		if (type == long.class) return 'J';
		if (type == boolean.class) return 'Z';
		if (type == float.class) return 'F';
		if (type == double.class) return 'D';
		if (type == byte.class) return 'B';
		if (type == short.class) return 'S';
		if (type == char.class) return 'C';
		return 'V';
	}

	static long encode(Object value, char kind) {
		return switch (kind) {
			case 'L' -> Handles.put(value);
			case 'Z' -> (Boolean) value ? 1 : 0;
			case 'C' -> (Character) value;
			case 'F' -> Float.floatToRawIntBits((Float) value);
			case 'D' -> Double.doubleToRawLongBits((Double) value);
			case 'V' -> 0;
			default -> ((Number) value).longValue();
		};
	}

	static Object decode(long slot, char kind) {
		return switch (kind) {
			case 'L' -> Handles.get(slot);
			case 'I' -> (int) slot;
			case 'J' -> slot;
			case 'Z' -> slot != 0;
			case 'F' -> Float.intBitsToFloat((int) slot);
			case 'D' -> Double.longBitsToDouble(slot);
			case 'B' -> (byte) slot;
			case 'S' -> (short) slot;
			case 'C' -> (char) slot;
			default -> null;
		};
	}
}
