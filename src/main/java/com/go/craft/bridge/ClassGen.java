package com.go.craft.bridge;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.go.craft.generated.Anchor;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Generates real subclasses whose overrides call Go. Each override also gets a public
 * {@code super$name} twin, so Go can call the inherited behaviour the way Java's {@code super.x()} does.
 */
public final class ClassGen {
	private static final String RUNTIME = "com/go/craft/bridge/ClassGen";
	private static final String GENERATED_PACKAGE = Anchor.class.getPackageName().replace('.', '/') + "/";
	private static final AtomicInteger NEXT_ID = new AtomicInteger();
	private static final ConcurrentHashMap<Integer, Definition> DEFINITIONS = new ConcurrentHashMap<>();

	private record Override(GenericMember member, Class<?>[] params, Class<?> ret) { }

	private record Definition(NativeMod owner, List<Override> overrides) { }

	private ClassGen() { }

	static Class<?> define(NativeMod owner, Class<?> superclass, String name, List<GenericMember> members) throws ReflectiveOperationException {
		int id = NEXT_ID.getAndIncrement();
		String internal = GENERATED_PACKAGE + sanitize(owner.id()) + "$" + sanitize(name) + "$" + id;
		List<Override> overrides = new ArrayList<>();

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		boolean isInterface = superclass.isInterface();
		String superName = isInterface ? "java/lang/Object" : Type.getInternalName(superclass);
		String[] interfaces = isInterface ? new String[] {Type.getInternalName(superclass)} : null;
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internal, null, superName, interfaces);

		if (isInterface) {
			constructor(cw, superName, "()V", Modifier.PUBLIC);
		} else {
			for (Constructor<?> ctor : superclass.getDeclaredConstructors()) {
				if (Modifier.isPrivate(ctor.getModifiers())) continue;
				constructor(cw, superName, Type.getConstructorDescriptor(ctor), ctor.getModifiers());
			}
		}

		for (int i = 0; i < members.size(); i++) {
			GenericMember member = members.get(i);
			Method method = findMethod(superclass, member);
			Override override = new Override(member, method.getParameterTypes(), method.getReturnType());
			overrides.add(override);

			int visibility = Modifier.isPublic(method.getModifiers()) ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED;
			forward(cw, id, i, member, method, visibility);
			if (!Modifier.isAbstract(method.getModifiers())) superBridge(cw, member, method, superName);
		}

		cw.visitEnd();
		DEFINITIONS.put(id, new Definition(owner, overrides));
		return MethodHandles.privateLookupIn(Anchor.class, MethodHandles.lookup()).defineClass(cw.toByteArray());
	}

	/** Called by generated code. */
	public static Object dispatch(int classId, int index, Object self, Object[] args) throws Throwable {
		Definition definition = DEFINITIONS.get(classId);
		Override override = definition.overrides.get(index);

		Class<?>[] types = new Class<?>[args.length + 1];
		Object[] values = new Object[args.length + 1];
		types[0] = Object.class;
		values[0] = self;
		System.arraycopy(override.params, 0, types, 1, args.length);
		System.arraycopy(args, 0, values, 1, args.length);

		return definition.owner.call(override.member.callback(), types, values, override.ret);
	}

	private static String sanitize(String value) {
		return value.replaceAll("[^A-Za-z0-9_]", "_");
	}

	private static Method findMethod(Class<?> type, GenericMember member) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			Method found = matching(c, member);
			if (found != null) return found;
		}

		Method fromInterface = findInInterfaces(type, member);
		if (fromInterface != null) return fromInterface;

		throw new IllegalArgumentException("No overridable method " + member.name() + member.desc() + " in " + type.getName());
	}

	private static Method findInInterfaces(Class<?> type, GenericMember member) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (Class<?> iface : c.getInterfaces()) {
				Method found = matching(iface, member);
				if (found == null) found = findInInterfaces(iface, member);
				if (found != null) return found;
			}
		}
		return null;
	}

	private static Method matching(Class<?> type, GenericMember member) {
		for (Method method : type.getDeclaredMethods()) {
			if (!method.getName().equals(member.name())) continue;
			if (!MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString().equals(member.desc())) continue;

			int modifiers = method.getModifiers();
			if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers) || Modifier.isFinal(modifiers)) {
				throw new IllegalArgumentException(type.getName() + "." + method.getName() + " cannot be overridden");
			}
			return method;
		}
		return null;
	}

	private static void constructor(ClassWriter cw, String superName, String desc, int modifiers) {
		int visibility = Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers) ? Opcodes.ACC_PUBLIC : 0;
		MethodVisitor mv = cw.visitMethod(visibility, "<init>", desc, null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		loadArguments(mv, Type.getArgumentTypes(desc));
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", desc, false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void forward(ClassWriter cw, int classId, int index, GenericMember member, Method method, int visibility) {
		MethodVisitor mv = cw.visitMethod(visibility, member.name(), member.desc(), null, null);
		Type[] args = Type.getArgumentTypes(member.desc());
		Type ret = Type.getReturnType(member.desc());

		mv.visitCode();
		mv.visitLdcInsn(classId);
		mv.visitLdcInsn(index);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitLdcInsn(args.length);
		mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

		int local = 1;
		for (int i = 0; i < args.length; i++) {
			mv.visitInsn(Opcodes.DUP);
			mv.visitLdcInsn(i);
			mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), local);
			box(mv, args[i]);
			mv.visitInsn(Opcodes.AASTORE);
			local += args[i].getSize();
		}

		mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "dispatch", "(IILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
		unboxAndReturn(mv, ret);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void superBridge(ClassWriter cw, GenericMember member, Method method, String superName) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "super$" + member.name(), member.desc(), null, null);
		Type[] args = Type.getArgumentTypes(member.desc());
		boolean onInterface = method.getDeclaringClass().isInterface() && superName.equals("java/lang/Object");
		String owner = onInterface ? Type.getInternalName(method.getDeclaringClass()) : superName;

		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		loadArguments(mv, args);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, member.name(), member.desc(), onInterface);
		mv.visitInsn(Type.getReturnType(member.desc()).getOpcode(Opcodes.IRETURN));
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void loadArguments(MethodVisitor mv, Type[] args) {
		int local = 1;
		for (Type arg : args) {
			mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), local);
			local += arg.getSize();
		}
	}

	private static String wrapper(Type type) {
		return switch (type.getSort()) {
			case Type.BOOLEAN -> "java/lang/Boolean";
			case Type.BYTE -> "java/lang/Byte";
			case Type.CHAR -> "java/lang/Character";
			case Type.SHORT -> "java/lang/Short";
			case Type.INT -> "java/lang/Integer";
			case Type.LONG -> "java/lang/Long";
			case Type.FLOAT -> "java/lang/Float";
			case Type.DOUBLE -> "java/lang/Double";
			default -> null;
		};
	}

	private static void box(MethodVisitor mv, Type type) {
		String wrapper = wrapper(type);
		if (wrapper != null) {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, "valueOf", "(" + type.getDescriptor() + ")L" + wrapper + ";", false);
		}
	}

	private static void unboxAndReturn(MethodVisitor mv, Type ret) {
		if (ret.getSort() == Type.VOID) {
			mv.visitInsn(Opcodes.POP);
			mv.visitInsn(Opcodes.RETURN);
			return;
		}

		String wrapper = wrapper(ret);
		if (wrapper == null) {
			mv.visitTypeInsn(Opcodes.CHECKCAST, ret.getInternalName());
		} else {
			mv.visitTypeInsn(Opcodes.CHECKCAST, wrapper);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapper, ret.getClassName() + "Value", "()" + ret.getDescriptor(), false);
		}
		mv.visitInsn(ret.getOpcode(Opcodes.IRETURN));
	}
}
