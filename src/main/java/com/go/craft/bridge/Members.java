package com.go.craft.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves classes, methods and fields by name and descriptor, and remembers them by id so Go only
 * pays for the lookup once. Lookups are private: Go mods get the reach Java mods need an access
 * widener for, because a name in a Go string is all there is to widen.
 */
final class Members {
	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

	record Bound(MethodHandle handle, char[] kinds, boolean hasReceiver, char returnKind) { }

	record BoundField(MethodHandle getter, MethodHandle setter, char kind, boolean hasReceiver) { }

	private record Key(Class<?> owner, String name, String desc, boolean isStatic) { }

	private static final Map<Key, Long> METHOD_IDS = new HashMap<>();
	private static final Map<Key, Long> FIELD_IDS = new HashMap<>();
	// Replaced, never mutated, so that every invoke can read them without taking the lookup lock.
	private static volatile Bound[] methods = new Bound[0];
	private static volatile BoundField[] fields = new BoundField[0];

	private Members() { }

	static ClassLoader loader() {
		return Members.class.getClassLoader();
	}

	static Class<?> findClass(String name) throws ClassNotFoundException {
		return Class.forName(name.replace('/', '.'), false, loader());
	}

	private static MethodHandles.Lookup lookupIn(Class<?> type) {
		try {
			return MethodHandles.privateLookupIn(type, LOOKUP);
		} catch (IllegalAccessException e) {
			return LOOKUP; // JDK internals are not open to us; public members still resolve.
		}
	}

	static synchronized long findMethod(Class<?> owner, String name, String desc, boolean isStatic) throws ReflectiveOperationException {
		Key key = new Key(owner, name, desc, isStatic);
		Long known = METHOD_IDS.get(key);
		if (known != null) return known;

		MethodType type = MethodType.fromMethodDescriptorString(desc, loader());
		MethodHandles.Lookup lookup = lookupIn(owner);
		MethodHandle handle;
		boolean hasReceiver = false;

		if (name.equals("<init>")) {
			handle = lookup.findConstructor(owner, type);
		} else if (isStatic) {
			handle = lookup.findStatic(owner, name, type);
		} else {
			handle = lookup.findVirtual(owner, name, type);
			hasReceiver = true;
		}

		char[] kinds = new char[type.parameterCount()];
		for (int i = 0; i < kinds.length; i++) kinds[i] = Slots.kind(type.parameterType(i));

		char returnKind = name.equals("<init>") ? 'L' : Slots.kind(type.returnType());
		int arity = handle.type().parameterCount();
		MethodHandle spread = handle.asFixedArity().asSpreader(Object[].class, arity)
				.asType(MethodType.methodType(Object.class, Object[].class));

		Bound[] grown = Arrays.copyOf(methods, methods.length + 1);
		grown[methods.length] = new Bound(spread, kinds, hasReceiver, returnKind);
		methods = grown;
		METHOD_IDS.put(key, (long) grown.length);
		return grown.length;
	}

	static Bound method(long id) {
		return methods[(int) id - 1];
	}

	static synchronized long findField(Class<?> owner, String name, String desc, boolean isStatic) throws ReflectiveOperationException {
		Key key = new Key(owner, name, desc, isStatic);
		Long known = FIELD_IDS.get(key);
		if (known != null) return known;

		Class<?> type = MethodType.fromMethodDescriptorString("(" + desc + ")V", loader()).parameterType(0);
		MethodHandles.Lookup lookup = lookupIn(owner);
		MethodHandle getter = isStatic ? lookup.findStaticGetter(owner, name, type) : lookup.findGetter(owner, name, type);
		MethodHandle setter = null;

		try {
			setter = isStatic ? lookup.findStaticSetter(owner, name, type) : lookup.findSetter(owner, name, type);
		} catch (IllegalAccessException ignored) {
			// A final field: reads work, and writes report a clear error from fieldSet.
		}

		BoundField[] grown = Arrays.copyOf(fields, fields.length + 1);
		grown[fields.length] = new BoundField(getter, setter, Slots.kind(type), !isStatic);
		fields = grown;
		FIELD_IDS.put(key, (long) grown.length);
		return grown.length;
	}

	static BoundField field(long id) {
		return fields[(int) id - 1];
	}
}
