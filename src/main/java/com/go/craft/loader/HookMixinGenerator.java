package com.go.craft.loader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Turns gomod.json hooks into real mixin classes plus a mixin config. The generated injector boxes
 * its arguments and calls {@code HookRuntime.fire}, which forwards to Go.
 */
final class HookMixinGenerator {
	private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
	private static final String CALLBACK_INFO_RETURNABLE = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	private static final String FIRE_DESC = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;L" + CALLBACK_INFO + ";)V";

	private HookMixinGenerator() { }

	static String configName(String modId) {
		return "gocraft-hooks." + modId + ".mixins.json";
	}

	private static String packageName(String modId) {
		return "com.go.craft.generated.hooks." + modId.replace('-', '_');
	}

	static void write(String modId, List<HookSpec> hooks, ZipOutputStream jar) throws IOException {
		if (hooks.isEmpty()) return;

		String pkg = packageName(modId);
		JsonArray common = new JsonArray();
		JsonArray client = new JsonArray();
		JsonArray server = new JsonArray();
		List<HookSpec> ordered = new ArrayList<>(hooks);

		for (int i = 0; i < ordered.size(); i++) {
			HookSpec hook = ordered.get(i);
			String simpleName = "Hook" + i;
			String internal = pkg.replace('.', '/') + "/" + simpleName;

			jar.putNextEntry(new ZipEntry(internal + ".class"));
			jar.write(generate(internal, modId, hook));
			jar.closeEntry();

			switch (hook.environment()) {
				case "client" -> client.add(simpleName);
				case "server" -> server.add(simpleName);
				default -> common.add(simpleName);
			}
		}

		JsonObject injectors = new JsonObject();
		injectors.addProperty("defaultRequire", 1);

		JsonObject config = new JsonObject();
		config.addProperty("required", true);
		config.addProperty("package", pkg);
		config.addProperty("compatibilityLevel", "JAVA_25");
		config.add("mixins", common);
		config.add("client", client);
		config.add("server", server);
		config.add("injectors", injectors);

		jar.putNextEntry(new ZipEntry(configName(modId)));
		jar.write(new Gson().toJson(config).getBytes(StandardCharsets.UTF_8));
		jar.closeEntry();
	}

	private static byte[] generate(String internal, String modId, HookSpec hook) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, internal, null, "java/lang/Object", null);

		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, hook.target());
		targets.visitEnd();
		mixin.visit("remap", false);
		mixin.visitEnd();

		Type[] args = Type.getArgumentTypes(hook.desc());
		String callback = hook.returnsVoid() ? CALLBACK_INFO : CALLBACK_INFO_RETURNABLE;

		StringBuilder desc = new StringBuilder("(");
		for (Type arg : args) desc.append(arg.getDescriptor());
		desc.append('L').append(callback).append(";)V");

		int access = Opcodes.ACC_PRIVATE | (hook.isStatic() ? Opcodes.ACC_STATIC : 0);
		MethodVisitor mv = cw.visitMethod(access, "gocraft$hook", desc.toString(), null, null);

		AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		AnnotationVisitor method = inject.visitArray("method");
		method.visit(null, hook.method() + hook.desc());
		method.visitEnd();
		AnnotationVisitor atArray = inject.visitArray("at");
		AnnotationVisitor at = atArray.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", hook.at());
		at.visitEnd();
		atArray.visitEnd();
		inject.visit("cancellable", !hook.at().equals("TAIL"));
		inject.visit("remap", false);
		inject.visitEnd();

		mv.visitCode();
		mv.visitLdcInsn(modId + ":" + hook.id());

		int local = hook.isStatic() ? 0 : 1;
		if (hook.isStatic()) mv.visitInsn(Opcodes.ACONST_NULL);
		else mv.visitVarInsn(Opcodes.ALOAD, 0);

		pushInt(mv, args.length);
		mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

		for (int i = 0; i < args.length; i++) {
			mv.visitInsn(Opcodes.DUP);
			pushInt(mv, i);
			mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), local);
			box(mv, args[i]);
			mv.visitInsn(Opcodes.AASTORE);
			local += args[i].getSize();
		}

		mv.visitVarInsn(Opcodes.ALOAD, local);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/go/craft/bridge/HookRuntime", "fire", FIRE_DESC, false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	static void pushInt(MethodVisitor mv, int value) {
		if (value <= 5) mv.visitInsn(Opcodes.ICONST_0 + value);
		else mv.visitIntInsn(Opcodes.SIPUSH, value);
	}

	static void box(MethodVisitor mv, Type type) {
		String wrapper = switch (type.getSort()) {
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

		if (wrapper != null) {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, "valueOf", "(" + type.getDescriptor() + ")L" + wrapper + ";", false);
		}
	}
}
