package com.u1.durableThreads.internal;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Checks the operand stack depth at invoke instructions within a method.
 * Used at freeze time to verify that each frame in the call stack has an
 * empty operand stack (excluding the invoke's own arguments/receiver).
 *
 * <p>This is a hard requirement: the operand stack is not exposed via JDI,
 * so values on the stack cannot be captured or restored. If any frame has
 * extra values on the stack beyond the invoke's arguments, the freeze must
 * be rejected.</p>
 */
public final class OperandStackChecker {

    private OperandStackChecker() {}

    /**
     * Check if the operand stack is empty at a specific invoke instruction,
     * excluding the invoke's own arguments and receiver.
     *
     * @param classBytecode the class file bytes (instrumented)
     * @param methodName    the method name
     * @param methodDesc    the method descriptor
     * @param bytecodeIndex the bytecode index of the invoke instruction
     * @return null if the stack is clean, or an error message describing the problem
     */
    public static String checkStackAtInvoke(byte[] classBytecode,
                                            String methodName, String methodDesc,
                                            int bytecodeIndex) {
        try {
            ClassReader cr = new ClassReader(classBytecode);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                if (!method.name.equals(methodName) || !method.desc.equals(methodDesc)) {
                    continue;
                }

                return analyzeMethod(classNode.name, method, bytecodeIndex);
            }

            return "Method not found: " + methodName + methodDesc;
        } catch (Exception e) {
            return "Operand stack analysis failed for " + methodName + methodDesc
                    + ": " + e.getMessage()
                    + ". Cannot verify stack is empty at freeze point.";
        }
    }

    private static String analyzeMethod(String owner, MethodNode method, int targetBci) {
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            Frame<BasicValue>[] frames = analyzer.analyze(owner, method);

            // Find the instruction at the target BCI
            int currentBci = 0;
            for (int i = 0; i < method.instructions.size(); i++) {
                AbstractInsnNode insn = method.instructions.get(i);
                if (insn instanceof LabelNode || insn instanceof LineNumberNode
                        || insn instanceof FrameNode) {
                    continue; // pseudo-instructions don't have BCIs
                }

                if (currentBci == targetBci) {
                    return checkInstructionStack(frames[i], insn, method.name);
                }

                // Approximate BCI tracking (not perfect but close enough for matching)
                currentBci += insnSize(insn);
            }

            return "Could not find instruction at bytecode index " + targetBci
                    + " in method " + method.name
                    + ". Cannot verify operand stack is empty at freeze point.";
        } catch (AnalyzerException e) {
            return "Bytecode analysis failed for method " + method.name
                    + ": " + e.getMessage()
                    + ". Cannot verify operand stack is empty at freeze point.";
        }
    }

    private static String checkInstructionStack(Frame<BasicValue> frame,
                                                 AbstractInsnNode insn,
                                                 String methodName) {
        if (frame == null) return null; // unreachable code

        if (insn instanceof MethodInsnNode methodInsn) {
            int expectedStackDepth = computeInvokeConsumedSlots(
                    methodInsn.getOpcode(), methodInsn.desc);
            int actualStackDepth = frame.getStackSize();
            int extraSlots = actualStackDepth - expectedStackDepth;

            if (extraSlots > 0) {
                return String.format(
                        "Non-empty operand stack in %s at invoke %s.%s%s: " +
                        "%d extra slot(s) on stack that cannot be captured/restored. " +
                        "Durable.freeze() must be called when all frames have a clean operand stack.",
                        methodName, methodInsn.owner, methodInsn.name, methodInsn.desc,
                        extraSlots);
            }
        } else if (insn instanceof InvokeDynamicInsnNode indyInsn) {
            int expectedStackDepth = computeInvokeConsumedSlots(
                    Opcodes.INVOKESTATIC, indyInsn.desc); // invokedynamic has no receiver
            int actualStackDepth = frame.getStackSize();
            int extraSlots = actualStackDepth - expectedStackDepth;

            if (extraSlots > 0) {
                return String.format(
                        "Non-empty operand stack in %s at invokedynamic %s%s: " +
                        "%d extra slot(s) on stack.",
                        methodName, indyInsn.name, indyInsn.desc, extraSlots);
            }
        }

        return null; // clean
    }

    /**
     * Compute how many stack slots an invoke instruction consumes
     * (receiver + arguments).
     */
    private static int computeInvokeConsumedSlots(int opcode, String desc) {
        int slots = 0;

        // Receiver for non-static
        if (opcode != Opcodes.INVOKESTATIC) {
            slots += 1;
        }

        // Arguments
        for (Type argType : Type.getArgumentTypes(desc)) {
            slots += argType.getSize();
        }

        return slots;
    }

    /**
     * Rough approximation of instruction size in bytes for BCI tracking.
     * ASM abstracts away LDC_W/GOTO_W/WIDE, so we only handle the opcodes
     * that ASM exposes.
     */
    private static int insnSize(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == -1) return 0; // pseudo-instruction (label, line number, frame)

        return switch (opcode) {
            case Opcodes.BIPUSH, Opcodes.NEWARRAY, Opcodes.LDC -> 2;
            case Opcodes.SIPUSH, Opcodes.IINC -> 3;
            case Opcodes.GOTO, Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
                 Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                 Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT,
                 Opcodes.IFLE, Opcodes.IFNULL, Opcodes.IFNONNULL,
                 Opcodes.JSR, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
                 Opcodes.GETSTATIC, Opcodes.PUTSTATIC, Opcodes.GETFIELD, Opcodes.PUTFIELD,
                 Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC,
                 Opcodes.NEW, Opcodes.ANEWARRAY, Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> 3;
            case Opcodes.INVOKEINTERFACE, Opcodes.INVOKEDYNAMIC, Opcodes.MULTIANEWARRAY -> 5;
            case Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> 16; // variable size, rough approx
            default -> 1;
        };
    }
}
