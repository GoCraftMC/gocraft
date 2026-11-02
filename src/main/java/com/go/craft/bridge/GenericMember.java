package com.go.craft.bridge;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/** One Go-implemented method: name, descriptor and the Go callback id behind it. */
record GenericMember(String name, String desc, long callback) {
	static List<GenericMember> parse(String spec, long callbacksAddress) {
		List<GenericMember> members = new ArrayList<>();
		if (spec.isEmpty()) return members;

		String[] lines = spec.split("\n");
		MemorySegment callbacks = MemorySegment.ofAddress(callbacksAddress).reinterpret(lines.length * 8L);

		for (int i = 0; i < lines.length; i++) {
			int paren = lines[i].indexOf('(');
			if (paren <= 0) throw new IllegalArgumentException("Malformed method spec: " + lines[i]);
			members.add(new GenericMember(lines[i].substring(0, paren), lines[i].substring(paren),
					callbacks.get(ValueLayout.JAVA_LONG_UNALIGNED, i * 8L)));
		}
		return members;
	}
}
