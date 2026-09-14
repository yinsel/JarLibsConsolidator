package org.le1a.jarlibsconsolidator;

import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassVisitor;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.MethodVisitor;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.Type;

import java.util.HashSet;
import java.util.Set;

/** A retry-only input workaround for IDEA's PPandMMHelper merging a parameter into a local.
 * Never writes a class file or modifies the caller's byte array.
 */
public final class ParameterIncrementNormalizer {
    private ParameterIncrementNormalizer() {}

    public static byte[] normalize(byte[] original, Set<String> failedMethods, Runnable checkCanceled) {
        if (failedMethods.isEmpty()) return null;
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                checkCanceled.run();
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!failedMethods.contains(name + descriptor)) return delegate;
                Set<Integer> intParameters = new HashSet<>();
                int slot = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                for (Type argument : Type.getArgumentTypes(descriptor)) {
                    if (argument.getSort() == Type.INT) intParameters.add(slot);
                    slot += argument.getSize();
                }
                if (intParameters.isEmpty()) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitIincInsn(int variable, int increment) {
                        checkCanceled.run();
                        if (intParameters.contains(variable) && (increment == 1 || increment == -1)) {
                            // int arithmetic wraps modulo 2^32, including MIN/MAX_VALUE.
                            // x++ == x - (-1); x-- == x + (-1). Both leave the operand stack unchanged.
                            // A negative constant avoids PPandMMHelper's hasValueOne() rewrite.
                            super.visitVarInsn(Opcodes.ILOAD, variable);
                            super.visitInsn(Opcodes.ICONST_M1);
                            super.visitInsn(increment == 1 ? Opcodes.ISUB : Opcodes.IADD);
                            super.visitVarInsn(Opcodes.ISTORE, variable);
                            changed[0] = true;
                        } else {
                            super.visitIincInsn(variable, increment);
                        }
                    }
                };
            }
        }, 0);
        checkCanceled.run();
        return changed[0] ? writer.toByteArray() : null;
    }
}
