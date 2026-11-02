package com.go.craft.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.go.craft.GoCraft;
import com.go.craft.loader.GoModCandidate;
import com.go.craft.loader.GoModException;

/** A loaded Go runtime: one per .gomod. Owns the downcalls into that library. */
public final class NativeMod {
	private static final int OK = 0;
	private static final int THROWN = 1;
	private static final int PANICKED = 2;
	private static final int FRAME_SIZE = 64 * 1024;

	private static final Map<String, NativeMod> BY_ID = new ConcurrentHashMap<>();
	private static final List<NativeMod> BY_TOKEN = new ArrayList<>();

	/** Per-thread native scratch space for arguments; nested Java/Go calls stack within it. */
	private static final ThreadLocal<Frame> FRAMES = ThreadLocal.withInitial(Frame::new);

	private static final class Frame {
		final MemorySegment memory = Arena.ofAuto().allocate(FRAME_SIZE, 8);
		long top;
	}

	private final String id;
	private final long token;
	private final MethodHandle dispatch;
	private final MethodHandle entry;
	private final MethodHandle releaseCallback;

	public static NativeMod of(GoModCandidate candidate) {
		return BY_ID.computeIfAbsent(candidate.id(), key -> load(candidate));
	}

	static NativeMod byToken(long token) {
		synchronized (BY_TOKEN) {
			return BY_TOKEN.get((int) token);
		}
	}

	private static NativeMod load(GoModCandidate candidate) {
		try {
			SymbolLookup symbols = SymbolLookup.libraryLookup(candidate.library(), Arena.global());
			return new NativeMod(candidate.id(), symbols);
		} catch (GoModException e) {
			throw e;
		} catch (Throwable t) {
			throw new GoModException("Could not load the native library of " + candidate.describe() + ": " + t, t);
		}
	}

	private NativeMod(String id, SymbolLookup symbols) throws Throwable {
		this.id = id;

		Linker linker = Linker.nativeLinker();
		MethodHandle abi = linker.downcallHandle(require(symbols, "gocraft_abi"), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
		long version = (long) abi.invokeExact();
		if (version != GoCraft.ABI_VERSION) {
			throw new GoModException(id + " was compiled against SDK ABI " + version + " but GoCraft uses ABI " + GoCraft.ABI_VERSION);
		}

		synchronized (BY_TOKEN) {
			token = BY_TOKEN.size();
			BY_TOKEN.add(this);
		}

		FunctionDescriptor boot = FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
		MethodHandle bootHandle = linker.downcallHandle(require(symbols, "gocraft_boot"), boot);
		this.entry = linker.downcallHandle(require(symbols, "gocraft_entry"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		this.dispatch = linker.downcallHandle(require(symbols, "gocraft_dispatch"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

		this.releaseCallback = linker.downcallHandle(require(symbols, "gocraft_release_callback"), FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG));

		try (Arena arena = Arena.ofConfined()) {
			byte[] name = id.getBytes(StandardCharsets.UTF_8);
			MemorySegment nameSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, name);
			bootHandle.invokeExact(Abi.table().address(), token, nameSegment.address(), (long) name.length);
		}
	}

	private static MemorySegment require(SymbolLookup symbols, String name) {
		return symbols.find(name).orElseThrow(() -> new GoModException("Native library does not export " + name + ". Was it built with go.jar?"));
	}

	public String id() {
		return id;
	}

	/** Runs the Go handlers registered for an entrypoint key. */
	public void enter(String key) throws Throwable {
		Frame frame = FRAMES.get();
		long base = frame.top;
		byte[] name = key.getBytes(StandardCharsets.UTF_8);
		long needed = (name.length + 15L) & ~7L;

		if (base + needed > FRAME_SIZE) throw new IllegalStateException("Go call stack too deep");

		frame.top += needed;
		try {
			MemorySegment ret = frame.memory.asSlice(base, 8);
			MemorySegment.copy(name, 0, frame.memory, ValueLayout.JAVA_BYTE, base + 8, name.length);
			int status = (int) entry.invokeExact(frame.memory.address() + base + 8, (long) name.length, ret.address());
			if (status != OK) throwResult(status, ret.get(ValueLayout.JAVA_LONG, 0));
		} finally {
			frame.top = base;
		}
	}

	/**
	 * Calls a Go callback with Java values. Reference slots stay owned by this frame unless Go zeroes
	 * them to take ownership, so an ignored argument costs no extra native call.
	 */
	Object call(long callback, Class<?>[] types, Object[] values, Class<?> returnType) throws Throwable {
		Frame frame = FRAMES.get();
		long base = frame.top;
		long needed = (types.length + 1) * 8L;

		if (base + needed > FRAME_SIZE) throw new StackOverflowError("Go call stack too deep");

		frame.top += needed;
		MemorySegment slots = frame.memory.asSlice(base, needed);
		boolean[] refs = new boolean[types.length];

		try {
			for (int i = 0; i < types.length; i++) {
				char kind = Slots.kind(types[i]);
				refs[i] = kind == 'L';
				slots.setAtIndex(ValueLayout.JAVA_LONG, i, Slots.encode(values[i], kind));
			}
			slots.setAtIndex(ValueLayout.JAVA_LONG, types.length, 0);

			long address = slots.address();
			int status = (int) dispatch.invokeExact(callback, address, (long) types.length, address + types.length * 8L);
			long result = slots.getAtIndex(ValueLayout.JAVA_LONG, types.length);

			if (status != OK) throwResult(status, result);

			char kind = Slots.kind(returnType);
			Object value = Slots.decode(result, kind);
			if (kind == 'L') Handles.release(result);
			return value;
		} finally {
			for (int i = 0; i < types.length; i++) {
				if (refs[i]) Handles.release(slots.getAtIndex(ValueLayout.JAVA_LONG, i));
			}
			frame.top = base;
		}
	}

	void releaseCallback(long callback) throws Throwable {
		releaseCallback.invokeExact(callback);
	}

	private void throwResult(int status, long handle) throws Throwable {
		Object value = Handles.get(handle);
		Handles.release(handle);

		if (status == THROWN && value instanceof Throwable throwable) throw throwable;
		if (status == PANICKED) throw new GoPanicException(id, String.valueOf(value));
		throw new GoPanicException(id, "Unknown status " + status + " from Go");
	}
}
