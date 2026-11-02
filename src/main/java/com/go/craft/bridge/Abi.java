package com.go.craft.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.LoggerFactory;

/**
 * The function table handed to every Go mod. Order and signatures here must match
 * {@code gocraft_table} in gocraft-sdk/jvm/bridge.h; bump {@code GoCraft.ABI_VERSION} if they change.
 *
 * <p>An exception escaping an upcall aborts the JVM, so everything that can throw catches
 * {@code Throwable} and hands it to Go as a status plus handle.
 */
final class Abi {
	private static final int OK = 0;
	private static final int THROWN = 1;
	private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;

	private static MemorySegment table;

	private Abi() { }

	static synchronized MemorySegment table() {
		if (table != null) return table;

		// name, arity, returnsVoid. Every parameter is a long; pointers travel as addresses.
		record Entry(String name, int arity, Class<?> ret) { }
		List<Entry> entries = List.of(
				new Entry("findClass", 3, int.class),
				new Entry("findMethod", 7, int.class),
				new Entry("findField", 7, int.class),
				new Entry("invoke", 5, int.class),
				new Entry("fieldGet", 3, int.class),
				new Entry("fieldSet", 4, int.class),
				new Entry("stringNew", 2, long.class),
				new Entry("stringRead", 3, long.class),
				new Entry("release", 1, void.class),
				new Entry("dup", 1, long.class),
				new Entry("same", 2, long.class),
				new Entry("instanceOf", 2, long.class),
				new Entry("classOf", 1, long.class),
				new Entry("arrayLen", 1, long.class),
				new Entry("bytesNew", 2, long.class),
				new Entry("bytesRead", 3, long.class),
				new Entry("proxy", 6, int.class),
				new Entry("extend", 8, int.class),
				new Entry("log", 4, void.class),
				new Entry("hookBind", 4, void.class));

		Linker linker = Linker.nativeLinker();
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MemorySegment segment = Arena.global().allocate(MemoryLayout.sequenceLayout(entries.size(), ValueLayout.ADDRESS));

		try {
			for (int i = 0; i < entries.size(); i++) {
				Entry entry = entries.get(i);
				List<Class<?>> params = new ArrayList<>();
				for (int p = 0; p < entry.arity; p++) params.add(long.class);

				MethodHandle target = lookup.findStatic(Abi.class, entry.name, MethodType.methodType(entry.ret, params));
				MemoryLayout[] layouts = new MemoryLayout[entry.arity];
				java.util.Arrays.fill(layouts, ValueLayout.JAVA_LONG);

				FunctionDescriptor descriptor = entry.ret == void.class ? FunctionDescriptor.ofVoid(layouts)
						: FunctionDescriptor.of(entry.ret == int.class ? ValueLayout.JAVA_INT : ValueLayout.JAVA_LONG, layouts);

				segment.setAtIndex(ValueLayout.ADDRESS, i, linker.upcallStub(target, descriptor, Arena.global()));
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}

		table = segment;
		return table;
	}

	private static MemorySegment memory(long address, long length) {
		return MemorySegment.ofAddress(address).reinterpret(length);
	}

	private static String string(long address, long length) {
		if (length == 0) return "";
		return new String(memory(address, length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
	}

	private static void store(long out, long value) {
		memory(out, 8).set(LONG, 0, value);
	}

	private static int thrown(long out, Throwable t) {
		store(out, Handles.put(t));
		return THROWN;
	}

	static int findClass(long name, long length, long out) {
		try {
			store(out, Handles.put(Members.findClass(string(name, length))));
			return OK;
		} catch (Throwable t) {
			return thrown(out, t);
		}
	}

	static int findMethod(long cls, long name, long nameLength, long desc, long descLength, long isStatic, long out) {
		try {
			store(out, Members.findMethod((Class<?>) Handles.get(cls), string(name, nameLength), string(desc, descLength), isStatic != 0));
			return OK;
		} catch (Throwable t) {
			return thrown(out, t);
		}
	}

	static int findField(long cls, long name, long nameLength, long desc, long descLength, long isStatic, long out) {
		try {
			store(out, Members.findField((Class<?>) Handles.get(cls), string(name, nameLength), string(desc, descLength), isStatic != 0));
			return OK;
		} catch (Throwable t) {
			return thrown(out, t);
		}
	}

	static int invoke(long method, long receiver, long args, long count, long out) {
		try {
			Members.Bound bound = Members.method(method);
			char[] kinds = bound.kinds();
			if (count != kinds.length) throw new IllegalArgumentException("Expected " + kinds.length + " arguments, got " + count);

			int offset = bound.hasReceiver() ? 1 : 0;
			Object[] values = new Object[kinds.length + offset];
			if (offset == 1) values[0] = Handles.get(receiver);

			if (count > 0) {
				MemorySegment slots = memory(args, count * 8);
				for (int i = 0; i < kinds.length; i++) {
					values[i + offset] = Slots.decode(slots.get(LONG, i * 8L), kinds[i]);
				}
			}

			Object result = (Object) bound.handle().invokeExact(values);
			store(out, Slots.encode(result, bound.returnKind()));
			return OK;
		} catch (Throwable t) {
			return thrown(out, t);
		}
	}

	static int fieldGet(long field, long receiver, long out) {
		try {
			Members.BoundField bound = Members.field(field);
			Object value = bound.hasReceiver() ? bound.getter().invoke(Handles.get(receiver)) : bound.getter().invoke();
			store(out, Slots.encode(value, bound.kind()));
			return OK;
		} catch (Throwable t) {
			return thrown(out, t);
		}
	}

	static int fieldSet(long field, long receiver, long value, long out) {
		try {
			Members.BoundField bound = Members.field(field);
			if (bound.setter() == null) throw new IllegalAccessException("Field is final");

			Object decoded = Slots.decode(value, bound.kind());
			if (bound.hasReceiver()) bound.setter().invoke(Handles.get(receiver), decoded);
			else bound.setter().invoke(decoded);
			return OK;
		} catch (Throwable t) {
			return thrown(out, t);
		}
	}

	static long stringNew(long data, long length) {
		try {
			return Handles.put(string(data, length));
		} catch (Throwable t) {
			return 0;
		}
	}

	/** Returns the UTF-8 length, copying only if it fits; -1 for a non-string. */
	static long stringRead(long handle, long buffer, long capacity) {
		try {
			Object value = Handles.get(handle);
			if (value == null) return -1;

			byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
			if (bytes.length <= capacity && bytes.length > 0) {
				MemorySegment.copy(bytes, 0, memory(buffer, capacity), ValueLayout.JAVA_BYTE, 0, bytes.length);
			}
			return bytes.length;
		} catch (Throwable t) {
			return -1;
		}
	}

	static void release(long handle) {
		try {
			Handles.release(handle);
		} catch (Throwable ignored) {
			// Called from Go's cleanup goroutine, where there is nobody to tell.
		}
	}

	static long dup(long handle) {
		try {
			return Handles.put(Handles.get(handle));
		} catch (Throwable t) {
			return 0;
		}
	}

	static long same(long a, long b) {
		try {
			return Handles.get(a) == Handles.get(b) ? 1 : 0;
		} catch (Throwable t) {
			return 0;
		}
	}

	static long instanceOf(long handle, long cls) {
		try {
			return ((Class<?>) Handles.get(cls)).isInstance(Handles.get(handle)) ? 1 : 0;
		} catch (Throwable t) {
			return 0;
		}
	}

	static long classOf(long handle) {
		try {
			Object value = Handles.get(handle);
			return value == null ? 0 : Handles.put(value.getClass());
		} catch (Throwable t) {
			return 0;
		}
	}

	static long arrayLen(long handle) {
		try {
			return Array.getLength(Handles.get(handle));
		} catch (Throwable t) {
			return -1;
		}
	}

	static long bytesNew(long data, long length) {
		try {
			return Handles.put(length == 0 ? new byte[0] : memory(data, length).toArray(ValueLayout.JAVA_BYTE));
		} catch (Throwable t) {
			return 0;
		}
	}

	static long bytesRead(long handle, long buffer, long capacity) {
		try {
			byte[] bytes = (byte[]) Handles.get(handle);
			long count = Math.min(bytes.length, capacity);
			if (count > 0) MemorySegment.copy(bytes, 0, memory(buffer, capacity), ValueLayout.JAVA_BYTE, 0, (int) count);
			return bytes.length;
		} catch (Throwable t) {
			return -1;
		}
	}

	/** {@code spec} is newline separated {@code name+descriptor}; {@code callbacks} is parallel to it. */
	static int proxy(long token, long iface, long spec, long specLength, long callbacks, long out) {
		try {
			List<GenericMember> members = GenericMember.parse(string(spec, specLength), callbacks);
			store(out, Handles.put(GoProxy.create(NativeMod.byToken(token), (Class<?>) Handles.get(iface), members)));
			return OK;
		} catch (Throwable t) {
			return thrown(out, t);
		}
	}

	/** Defines a subclass of {@code superclass} and stores the new Class. Returned class is initialized lazily. */
	static int extend(long token, long superclass, long name, long nameLength, long spec, long specLength, long callbacks, long out) {
		try {
			List<GenericMember> members = GenericMember.parse(string(spec, specLength), callbacks);
			store(out, Handles.put(ClassGen.define(NativeMod.byToken(token), (Class<?>) Handles.get(superclass), string(name, nameLength), members)));
			return OK;
		} catch (Throwable t) {
			return thrown(out, t);
		}
	}

	static void log(long token, long level, long data, long length) {
		try {
			var logger = LoggerFactory.getLogger(NativeMod.byToken(token).id());
			String message = string(data, length);

			switch ((int) level) {
				case 0 -> logger.debug(message);
				case 2 -> logger.warn(message);
				case 3 -> logger.error(message);
				default -> logger.info(message);
			}
		} catch (Throwable ignored) {
			// Logging must never be the thing that takes the game down.
		}
	}

	static void hookBind(long token, long id, long idLength, long callback) {
		try {
			NativeMod mod = NativeMod.byToken(token);
			HookRuntime.bind(mod.id() + ":" + string(id, idLength), mod, callback);
		} catch (Throwable ignored) {
			// An unbound hook is a no-op, so a failure here degrades rather than crashes.
		}
	}
}
