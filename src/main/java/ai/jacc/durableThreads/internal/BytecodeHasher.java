package ai.jacc.durableThreads.internal;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes SHA-256 hashes of method bytecode for integrity checking between
 * freeze and restore.
 */
public final class BytecodeHasher {

    private BytecodeHasher() {}

    /**
     * Compute the SHA-256 hash of an entire class file.
     *
     * @param classBytecode the full class file bytes
     * @return SHA-256 hash
     */
    public static byte[] hashClass(byte[] classBytecode) {
        MessageDigest digest = createSha256();
        digest.update(classBytecode);
        return digest.digest();
    }

    /**
     * Compute the SHA-256 hash of a specific method's bytecode.
     *
     * @param classBytecode the full class file bytes
     * @param methodName    the method name
     * @param methodDesc    the method descriptor
     * @return SHA-256 hash, or null if method not found
     */
    public static byte[] hash(byte[] classBytecode, String methodName, String methodDesc) {
        byte[][] result = new byte[1][];
        ClassReader cr = new ClassReader(classBytecode);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (name.equals(methodName) && descriptor.equals(methodDesc)) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        private final MessageDigest digest = createSha256();

                        @Override
                        public void visitInsn(int opcode) {
                            digest.update((byte) opcode);
                        }

                        @Override
                        public void visitIntInsn(int opcode, int operand) {
                            digest.update((byte) opcode);
                            digestInt(digest, operand);
                        }

                        @Override
                        public void visitVarInsn(int opcode, int varIndex) {
                            digest.update((byte) opcode);
                            digestInt(digest, varIndex);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            digest.update((byte) opcode);
                            digestString(digest, type);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name,
                                                   String descriptor) {
                            digest.update((byte) opcode);
                            digestString(digest, owner);
                            digestString(digest, name);
                            digestString(digest, descriptor);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            digest.update((byte) opcode);
                            digestString(digest, owner);
                            digestString(digest, name);
                            digestString(digest, descriptor);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            digestString(digest, String.valueOf(value));
                        }

                        @Override
                        public void visitIincInsn(int varIndex, int increment) {
                            digest.update((byte) Opcodes.IINC);
                            digestInt(digest, varIndex);
                            digestInt(digest, increment);
                        }

                        @Override
                        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                            digestString(digest, descriptor);
                            digestInt(digest, numDimensions);
                        }

                        @Override
                        public void visitEnd() {
                            result[0] = digest.digest();
                        }
                    };
                }
                return null;
            }
        }, 0);
        return result[0];
    }

    private static MessageDigest createSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static void digestInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void digestString(MessageDigest digest, String s) {
        digest.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
