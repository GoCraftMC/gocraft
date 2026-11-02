package com.go.craft.bridge;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** What a Go hook sees: the receiver, the boxed arguments and control over the target method. */
public final class HookContext {
	private final Object self;
	private final Object[] args;
	private final CallbackInfo info;

	HookContext(Object self, Object[] args, CallbackInfo info) {
		this.self = self;
		this.args = args;
		this.info = info;
	}

	public Object self() {
		return self;
	}

	public int argCount() {
		return args.length;
	}

	public Object arg(int index) {
		return args[index];
	}

	public void cancel() {
		info.cancel();
	}

	/** Reads the return value at a RETURN hook. */
	public Object returnValue() {
		return info instanceof CallbackInfoReturnable<?> returnable ? returnable.getReturnValue() : null;
	}

	/** Replaces the return value and leaves the target method. */
	@SuppressWarnings("unchecked")
	public void setReturn(Object value) {
		if (!(info instanceof CallbackInfoReturnable<?> returnable)) {
			throw new IllegalStateException("This hook targets a void method");
		}
		((CallbackInfoReturnable<Object>) returnable).setReturnValue(value);
	}
}
