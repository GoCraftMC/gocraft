package com.go.craft.bridge;

import java.lang.invoke.MethodType;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Implements a Java interface with Go callbacks: how Go closures become listeners and lambdas. */
final class GoProxy {
	private static final Cleaner CLEANER = Cleaner.create();

	private GoProxy() { }

	static Object create(NativeMod owner, Class<?> iface, List<GenericMember> members) {
		if (!iface.isInterface()) throw new IllegalArgumentException(iface.getName() + " is not an interface");

		Map<String, Long> callbacks = new HashMap<>();
		for (GenericMember member : members) callbacks.put(member.name() + member.desc(), member.callback());

		InvocationHandler handler = (proxy, method, args) -> {
			Long callback = callbacks.get(method.getName() + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());

			if (callback != null) {
				Object[] values = args == null ? new Object[0] : args;
				try {
					return owner.call(callback, method.getParameterTypes(), values, method.getReturnType());
				} finally {
					// The Cleaner must not free the callback while this call is still running it.
					Reference.reachabilityFence(proxy);
				}
			}

			if (method.getDeclaringClass() == Object.class) return objectMethod(owner, iface, proxy, method, args);
			if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
			throw new AbstractMethodError(iface.getName() + "." + method.getName() + " is not implemented by Go mod " + owner.id());
		};

		Object proxy = Proxy.newProxyInstance(Members.loader(), new Class<?>[] {iface}, handler);
		CLEANER.register(proxy, new Release(owner, members.stream().mapToLong(GenericMember::callback).toArray()));
		return proxy;
	}

	/** A separate class because a Cleaner action that could reach its proxy would keep it alive. */
	private record Release(NativeMod owner, long[] callbacks) implements Runnable {
		@Override
		public void run() {
			try {
				for (long callback : callbacks) owner.releaseCallback(callback);
			} catch (Throwable ignored) {
				// There is no one to report to on the Cleaner thread, and a leaked entry is harmless.
			}
		}
	}

	private static Object objectMethod(NativeMod owner, Class<?> iface, Object proxy, Method method, Object[] args) {
		return switch (method.getName()) {
			case "toString" -> "GoProxy[" + owner.id() + ":" + iface.getSimpleName() + "]";
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			default -> throw new UnsupportedOperationException(method.getName());
		};
	}
}
