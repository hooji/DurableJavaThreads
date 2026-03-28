package ai.jacc.durableThreads;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OperandStackSimulator} — the operand stack type tracker
 * used during bytecode buffering to determine sub-stack shapes at invoke sites.
 */
class OperandStackSimulatorTest {

    private OperandStackSimulator sim;

    @BeforeEach
    void setUp() {
        sim = new OperandStackSimulator();
    }

    // ===== Basic push/pop =====

    @Test
    void emptyStackPeekReturnsUnknown() {
        assertEquals('?', sim.peek());
    }

    @Test
    void emptyStackPopReturnsUnknown() {
        assertEquals('?', sim.pop());
    }

    @Test
    void pushAndPop() {
        sim.push('I');
        assertEquals('I', sim.peek());
        assertEquals('I', sim.pop());
        assertEquals('?', sim.peek()); // empty again
    }

    @Test
    void pushMultiplePopInOrder() {
        sim.push('I');
        sim.push('J');
        sim.push('A');
        assertEquals('A', sim.pop());
        assertEquals('J', sim.pop());
        assertEquals('I', sim.pop());
    }

    // ===== Lost state =====

    @Test
    void markLostClearsStackAndIgnoresPush() {
        sim.push('I');
        sim.push('A');
        sim.markLost();
        assertTrue(sim.isLost());
        assertEquals('?', sim.peek());
        sim.push('F'); // should be ignored
        assertEquals('?', sim.peek());
    }

    @Test
    void popWhileLostReturnsUnknown() {
        sim.push('I');
        sim.markLost();
        assertEquals('?', sim.pop());
    }

    // ===== Label save/restore =====

    @Test
    void saveLabelAndRecoverAfterLost() {
        sim.push('I');
        sim.push('A');
        Label target = new Label();
        sim.saveLabel(target);

        sim.markLost(); // e.g., GOTO
        assertTrue(sim.isLost());

        sim.visitLabel(target); // recover
        assertFalse(sim.isLost());
        assertEquals('A', sim.pop());
        assertEquals('I', sim.pop());
    }

    @Test
    void visitUnknownLabelRecoverWithEmptyStack() {
        sim.push('I');
        sim.markLost();
        sim.visitLabel(new Label()); // no saved state
        assertFalse(sim.isLost());
        assertEquals('?', sim.peek()); // empty stack
    }

    @Test
    void saveLabelDoesNotOverwriteExistingSave() {
        Label target = new Label();
        sim.push('I');
        sim.saveLabel(target); // saved [I]

        sim.push('A');
        sim.saveLabel(target); // should NOT overwrite

        sim.markLost();
        sim.visitLabel(target);
        // Should recover to [I], not [I, A]
        assertEquals('I', sim.pop());
        assertEquals('?', sim.pop());
    }

    @Test
    void visitLabelWhileNotLostDoesNothing() {
        sim.push('I');
        sim.push('A');
        Label target = new Label();
        sim.visitLabel(target); // not lost — no effect
        assertFalse(sim.isLost());
        assertEquals('A', sim.pop());
        assertEquals('I', sim.pop());
    }

    // ===== isWide =====

    @Test
    void wideTypes() {
        assertTrue(OperandStackSimulator.isWide('J'));
        assertTrue(OperandStackSimulator.isWide('D'));
        assertFalse(OperandStackSimulator.isWide('I'));
        assertFalse(OperandStackSimulator.isWide('F'));
        assertFalse(OperandStackSimulator.isWide('A'));
    }

    // ===== typeCategory =====

    @Test
    void typeCategoryMappings() {
        assertEquals('I', OperandStackSimulator.typeCategory(Type.BOOLEAN_TYPE));
        assertEquals('I', OperandStackSimulator.typeCategory(Type.BYTE_TYPE));
        assertEquals('I', OperandStackSimulator.typeCategory(Type.CHAR_TYPE));
        assertEquals('I', OperandStackSimulator.typeCategory(Type.SHORT_TYPE));
        assertEquals('I', OperandStackSimulator.typeCategory(Type.INT_TYPE));
        assertEquals('J', OperandStackSimulator.typeCategory(Type.LONG_TYPE));
        assertEquals('F', OperandStackSimulator.typeCategory(Type.FLOAT_TYPE));
        assertEquals('D', OperandStackSimulator.typeCategory(Type.DOUBLE_TYPE));
        assertEquals('A', OperandStackSimulator.typeCategory(Type.getType("Ljava/lang/String;")));
        assertEquals('A', OperandStackSimulator.typeCategory(Type.getType("[I")));
    }

    // ===== Instruction simulation =====

    @Test
    void simulateIntConstants() {
        sim.simulateInsn(Opcodes.ICONST_0);
        assertEquals('I', sim.peek());
        sim.simulateInsn(Opcodes.ICONST_M1);
        assertEquals('I', sim.pop());
        assertEquals('I', sim.pop());
    }

    @Test
    void simulateLongConstants() {
        sim.simulateInsn(Opcodes.LCONST_0);
        assertEquals('J', sim.pop());
        sim.simulateInsn(Opcodes.LCONST_1);
        assertEquals('J', sim.pop());
    }

    @Test
    void simulateFloatAndDoubleConstants() {
        sim.simulateInsn(Opcodes.FCONST_0);
        assertEquals('F', sim.pop());
        sim.simulateInsn(Opcodes.DCONST_0);
        assertEquals('D', sim.pop());
    }

    @Test
    void simulateAconstNull() {
        sim.simulateInsn(Opcodes.ACONST_NULL);
        assertEquals('A', sim.pop());
    }

    @Test
    void simulateArithmetic() {
        // IADD: pop I, I; push I
        sim.push('I');
        sim.push('I');
        sim.simulateInsn(Opcodes.IADD);
        assertEquals('I', sim.pop());
        assertEquals('?', sim.peek());

        // LADD: pop J, J; push J
        sim.push('J');
        sim.push('J');
        sim.simulateInsn(Opcodes.LADD);
        assertEquals('J', sim.pop());
    }

    @Test
    void simulateConversions() {
        sim.push('I');
        sim.simulateInsn(Opcodes.I2L);
        assertEquals('J', sim.pop());

        sim.push('J');
        sim.simulateInsn(Opcodes.L2F);
        assertEquals('F', sim.pop());

        sim.push('F');
        sim.simulateInsn(Opcodes.F2D);
        assertEquals('D', sim.pop());

        sim.push('D');
        sim.simulateInsn(Opcodes.D2I);
        assertEquals('I', sim.pop());
    }

    @Test
    void simulateArrayLoad() {
        // IALOAD: pop I (index), A (array); push I
        sim.push('A'); // array ref
        sim.push('I'); // index
        sim.simulateInsn(Opcodes.IALOAD);
        assertEquals('I', sim.pop());

        // AALOAD: pop I, A; push A
        sim.push('A');
        sim.push('I');
        sim.simulateInsn(Opcodes.AALOAD);
        assertEquals('A', sim.pop());
    }

    @Test
    void simulateArrayStore() {
        // IASTORE: pop I (value), I (index), A (array)
        sim.push('A');
        sim.push('I');
        sim.push('I');
        sim.simulateInsn(Opcodes.IASTORE);
        assertEquals('?', sim.peek()); // empty
    }

    @Test
    void simulateDup() {
        sim.push('I');
        sim.simulateInsn(Opcodes.DUP);
        assertEquals('I', sim.pop());
        assertEquals('I', sim.pop());
    }

    @Test
    void simulateDupX1() {
        sim.push('A');
        sim.push('I');
        sim.simulateInsn(Opcodes.DUP_X1);
        // Stack: I, A, I (top)
        assertEquals('I', sim.pop());
        assertEquals('A', sim.pop());
        assertEquals('I', sim.pop());
    }

    @Test
    void simulateDupX2Form1() {
        // Form 1: v3, v2, v1 → v1, v3, v2, v1 (all category 1)
        sim.push('A'); // v3
        sim.push('I'); // v2
        sim.push('F'); // v1
        sim.simulateInsn(Opcodes.DUP_X2);
        assertEquals('F', sim.pop());
        assertEquals('I', sim.pop());
        assertEquals('A', sim.pop());
        assertEquals('F', sim.pop());
    }

    @Test
    void simulateDupX2Form2() {
        // Form 2: v2(wide), v1 → v1, v2, v1
        sim.push('J'); // v2 (wide)
        sim.push('I'); // v1
        sim.simulateInsn(Opcodes.DUP_X2);
        assertEquals('I', sim.pop());
        assertEquals('J', sim.pop());
        assertEquals('I', sim.pop());
    }

    @Test
    void simulateDup2Category1() {
        // DUP2 form 1: two category-1 values
        sim.push('I');
        sim.push('A');
        sim.simulateInsn(Opcodes.DUP2);
        // Stack: I, A, I, A
        assertEquals('A', sim.pop());
        assertEquals('I', sim.pop());
        assertEquals('A', sim.pop());
        assertEquals('I', sim.pop());
    }

    @Test
    void simulateDup2Category2() {
        // DUP2 form 2: one category-2 value
        sim.push('J');
        sim.simulateInsn(Opcodes.DUP2);
        assertEquals('J', sim.pop());
        assertEquals('J', sim.pop());
    }

    @Test
    void simulateSwap() {
        sim.push('I');
        sim.push('A');
        sim.simulateInsn(Opcodes.SWAP);
        assertEquals('I', sim.pop());
        assertEquals('A', sim.pop());
    }

    @Test
    void simulatePopAndPop2() {
        sim.push('I');
        sim.push('A');
        sim.simulateInsn(Opcodes.POP);
        assertEquals('I', sim.peek());

        sim.push('J');
        sim.simulateInsn(Opcodes.POP2); // wide value
        assertEquals('I', sim.peek());

        sim.push('A');
        sim.push('F');
        sim.simulateInsn(Opcodes.POP2); // two category-1
        assertEquals('I', sim.peek());
    }

    @Test
    void simulateReturnMarkLost() {
        sim.push('I');
        sim.simulateInsn(Opcodes.IRETURN);
        assertTrue(sim.isLost());
    }

    @Test
    void simulateAthrowMarkLost() {
        sim.push('A');
        sim.simulateInsn(Opcodes.ATHROW);
        assertTrue(sim.isLost());
    }

    @Test
    void simulateMonitorEnterExit() {
        sim.push('A');
        sim.simulateInsn(Opcodes.MONITORENTER);
        assertEquals('?', sim.peek());

        sim.push('A');
        sim.simulateInsn(Opcodes.MONITOREXIT);
        assertEquals('?', sim.peek());
    }

    @Test
    void simulateArrayLength() {
        sim.push('A');
        sim.simulateInsn(Opcodes.ARRAYLENGTH);
        assertEquals('I', sim.pop());
    }

    @Test
    void simulateCompare() {
        sim.push('J');
        sim.push('J');
        sim.simulateInsn(Opcodes.LCMP);
        assertEquals('I', sim.pop());

        sim.push('F');
        sim.push('F');
        sim.simulateInsn(Opcodes.FCMPL);
        assertEquals('I', sim.pop());

        sim.push('D');
        sim.push('D');
        sim.simulateInsn(Opcodes.DCMPG);
        assertEquals('I', sim.pop());
    }

    // ===== visitIntInsn =====

    @Test
    void simulateBipushSipush() {
        sim.simulateIntInsn(Opcodes.BIPUSH);
        assertEquals('I', sim.pop());
        sim.simulateIntInsn(Opcodes.SIPUSH);
        assertEquals('I', sim.pop());
    }

    @Test
    void simulateNewarray() {
        sim.push('I'); // count
        sim.simulateIntInsn(Opcodes.NEWARRAY);
        assertEquals('A', sim.pop());
    }

    // ===== visitVarInsn =====

    @Test
    void simulateLoads() {
        sim.simulateVarInsn(Opcodes.ILOAD);
        assertEquals('I', sim.pop());
        sim.simulateVarInsn(Opcodes.LLOAD);
        assertEquals('J', sim.pop());
        sim.simulateVarInsn(Opcodes.FLOAD);
        assertEquals('F', sim.pop());
        sim.simulateVarInsn(Opcodes.DLOAD);
        assertEquals('D', sim.pop());
        sim.simulateVarInsn(Opcodes.ALOAD);
        assertEquals('A', sim.pop());
    }

    @Test
    void simulateStores() {
        sim.push('I');
        sim.simulateVarInsn(Opcodes.ISTORE);
        assertEquals('?', sim.peek());

        sim.push('A');
        sim.simulateVarInsn(Opcodes.ASTORE);
        assertEquals('?', sim.peek());

        sim.push('J');
        sim.simulateVarInsn(Opcodes.LSTORE);
        assertEquals('?', sim.peek());
    }

    // ===== visitTypeInsn =====

    @Test
    void simulateNew() {
        sim.simulateTypeInsn(Opcodes.NEW);
        assertEquals('U', sim.pop()); // uninitialized
    }

    @Test
    void simulateAnewarray() {
        sim.push('I'); // count
        sim.simulateTypeInsn(Opcodes.ANEWARRAY);
        assertEquals('A', sim.pop());
    }

    @Test
    void simulateCheckcast() {
        sim.push('A');
        sim.simulateTypeInsn(Opcodes.CHECKCAST);
        assertEquals('A', sim.pop()); // no net change
    }

    @Test
    void simulateInstanceof() {
        sim.push('A');
        sim.simulateTypeInsn(Opcodes.INSTANCEOF);
        assertEquals('I', sim.pop());
    }

    // ===== visitFieldInsn =====

    @Test
    void simulateGetstatic() {
        sim.simulateFieldInsn(Opcodes.GETSTATIC, "I");
        assertEquals('I', sim.pop());
        sim.simulateFieldInsn(Opcodes.GETSTATIC, "Ljava/lang/String;");
        assertEquals('A', sim.pop());
    }

    @Test
    void simulateGetfield() {
        sim.push('A'); // receiver
        sim.simulateFieldInsn(Opcodes.GETFIELD, "J");
        assertEquals('J', sim.pop());
    }

    @Test
    void simulatePutstatic() {
        sim.push('I');
        sim.simulateFieldInsn(Opcodes.PUTSTATIC, "I");
        assertEquals('?', sim.peek());
    }

    @Test
    void simulatePutfield() {
        sim.push('A'); // receiver
        sim.push('I'); // value
        sim.simulateFieldInsn(Opcodes.PUTFIELD, "I");
        assertEquals('?', sim.peek());
    }

    // ===== Sub-stack capture =====

    @Test
    void captureSubStackEmpty() {
        // No items below args
        sim.push('A'); // receiver
        sim.push('I'); // arg
        sim.captureSubStack(0, 2);
        assertTrue(sim.getSubStack(0).isEmpty());
    }

    @Test
    void captureSubStackWithItems() {
        sim.push('I'); // sub-stack item
        sim.push('A'); // sub-stack item
        sim.push('A'); // receiver
        sim.push('I'); // arg
        sim.captureSubStack(0, 2);
        List<Character> sub = sim.getSubStack(0);
        assertEquals(2, sub.size());
        assertEquals('I', sub.get(0));
        assertEquals('A', sub.get(1));
    }

    @Test
    void getSubStackForUncapturedIndexReturnsEmpty() {
        assertTrue(sim.getSubStack(999).isEmpty());
    }

    @Test
    void captureSubStackWhileLostDoesNotCapture() {
        sim.push('I');
        sim.markLost();
        sim.captureSubStack(0, 0);
        assertTrue(sim.getSubStack(0).isEmpty());
    }

    // ===== simulateMethodInsn =====

    @Test
    void simulateStaticMethodVoidReturn() {
        sim.push('I'); // arg
        sim.simulateMethodInsn(0, Opcodes.INVOKESTATIC, "(I)V", false);
        assertEquals('?', sim.peek()); // void return, arg consumed
    }

    @Test
    void simulateVirtualMethodWithReturn() {
        sim.push('A'); // receiver
        sim.push('I'); // arg
        sim.simulateMethodInsn(0, Opcodes.INVOKEVIRTUAL, "(I)J", false);
        assertEquals('J', sim.pop()); // long return
    }

    @Test
    void simulateConstructorDoesNotCaptureSubStack() {
        sim.push('A'); // sub-stack
        sim.push('U'); // uninitialized receiver
        sim.push('I'); // arg
        sim.simulateMethodInsn(-1, Opcodes.INVOKESPECIAL, "(I)V", true);
        // Sub-stack should NOT be captured (constructor)
        assertTrue(sim.getSubStack(-1).isEmpty());
    }

    @Test
    void simulateMethodCapturesSubStack() {
        sim.push('I'); // sub-stack
        sim.push('A'); // receiver
        sim.simulateMethodInsn(0, Opcodes.INVOKEVIRTUAL, "()V", false);
        List<Character> sub = sim.getSubStack(0);
        assertEquals(1, sub.size());
        assertEquals('I', sub.get(0));
    }

    // ===== simulateInvokeDynamic =====

    @Test
    void simulateInvokeDynamic() {
        sim.push('F'); // sub-stack
        sim.push('A'); // arg (invokedynamic has no receiver)
        sim.simulateInvokeDynamic(0, "(Ljava/lang/Object;)I");
        assertEquals('I', sim.pop()); // return
        assertEquals('F', sim.pop()); // sub-stack preserved
    }

    // ===== simulateJumpInsn =====

    @Test
    void simulateConditionalBranch() {
        sim.push('I');
        Label target = new Label();
        sim.simulateJumpInsn(Opcodes.IFEQ, target);
        assertEquals('?', sim.peek()); // consumed
        assertFalse(sim.isLost());
    }

    @Test
    void simulateComparisonBranch() {
        sim.push('I');
        sim.push('I');
        Label target = new Label();
        sim.simulateJumpInsn(Opcodes.IF_ICMPEQ, target);
        assertEquals('?', sim.peek()); // both consumed
    }

    @Test
    void simulateGotoMarksLost() {
        sim.push('I');
        Label target = new Label();
        sim.simulateJumpInsn(Opcodes.GOTO, target);
        assertTrue(sim.isLost());
    }

    @Test
    void simulateGotoSavesLabelBeforeLost() {
        sim.push('I');
        Label target = new Label();
        sim.simulateJumpInsn(Opcodes.GOTO, target);
        assertTrue(sim.isLost());

        // Recover at target
        sim.visitLabel(target);
        assertFalse(sim.isLost());
        assertEquals('I', sim.pop());
    }

    // ===== simulateLdc =====

    @Test
    void simulateLdcTypes() {
        sim.simulateLdc(42);
        assertEquals('I', sim.pop());
        sim.simulateLdc(42L);
        assertEquals('J', sim.pop());
        sim.simulateLdc(3.14f);
        assertEquals('F', sim.pop());
        sim.simulateLdc(3.14);
        assertEquals('D', sim.pop());
        sim.simulateLdc("hello");
        assertEquals('A', sim.pop());
    }

    // ===== simulateTableSwitch / simulateLookupSwitch =====

    @Test
    void simulateTableSwitchMarksLost() {
        sim.push('I'); // key
        Label dflt = new Label();
        Label[] labels = { new Label(), new Label() };
        sim.simulateTableSwitch(dflt, labels);
        assertTrue(sim.isLost());
    }

    @Test
    void simulateTableSwitchSavesLabels() {
        sim.push('A'); // below key
        sim.push('I'); // key
        Label dflt = new Label();
        Label case0 = new Label();
        sim.simulateTableSwitch(dflt, new Label[]{ case0 });
        assertTrue(sim.isLost());

        // Recover at case label — should have stack state after pop(key)
        sim.visitLabel(case0);
        assertFalse(sim.isLost());
        assertEquals('A', sim.pop());
    }

    @Test
    void simulateLookupSwitchMarksLost() {
        sim.push('I');
        sim.simulateLookupSwitch(new Label(), new Label[0]);
        assertTrue(sim.isLost());
    }

    // ===== simulateMultiANewArray =====

    @Test
    void simulateMultiANewArray() {
        sim.push('I'); // dim1
        sim.push('I'); // dim2
        sim.simulateMultiANewArray(2);
        assertEquals('A', sim.pop());
        assertEquals('?', sim.peek()); // both dims consumed
    }

    // ===== simulateTryCatchBlock =====

    @Test
    void simulateTryCatchBlockSetsHandlerStack() {
        Label handler = new Label();
        sim.simulateTryCatchBlock(handler);

        // Simulate entering the handler after a throw
        sim.markLost();
        sim.visitLabel(handler);
        assertFalse(sim.isLost());
        assertEquals('A', sim.pop()); // exception reference
    }

    // ===== Lost state resilience =====

    @Test
    void allSimulateMethodsNoOpWhenLost() {
        sim.markLost();
        // None of these should throw or change state
        sim.simulateInsn(Opcodes.IADD);
        sim.simulateIntInsn(Opcodes.BIPUSH);
        sim.simulateVarInsn(Opcodes.ILOAD);
        sim.simulateTypeInsn(Opcodes.NEW);
        sim.simulateFieldInsn(Opcodes.GETSTATIC, "I");
        sim.simulateMethodInsn(0, Opcodes.INVOKESTATIC, "()V", false);
        sim.simulateInvokeDynamic(0, "()V");
        sim.simulateJumpInsn(Opcodes.IFEQ, new Label());
        sim.simulateLdc(42);
        sim.simulateMultiANewArray(1);
        assertTrue(sim.isLost());
    }

    // ===== Complex sequences =====

    @Test
    void realWorldSequence_stringConcatenation() {
        // Simulates: new StringBuilder().append(a).append(b).toString()
        sim.simulateTypeInsn(Opcodes.NEW); // U (StringBuilder)
        sim.simulateInsn(Opcodes.DUP);     // U, U
        // <init> is a constructor call — handled outside simulator
        // After <init>, the 'U' becomes a real 'A'
        // Simulate post-init: pop receiver, result is initialized ref
        sim.pop(); sim.pop(); sim.push('A'); // simplified
        sim.simulateVarInsn(Opcodes.ALOAD); // A, A (load arg)
        // append(String) — virtual call: pop A, A; push A
        sim.simulateMethodInsn(0, Opcodes.INVOKEVIRTUAL,
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        assertEquals('A', sim.peek());
    }

    @Test
    void realWorldSequence_loopWithCounter() {
        // Simulates a loop: for (int i = 0; i < n; i++) { list.get(i); }
        // ICONST_0; ISTORE 2; (loop start) ILOAD 2; ILOAD 1; IF_ICMPGE exit;
        // ALOAD 0; ILOAD 2; INVOKEVIRTUAL get; POP; IINC 2,1; GOTO loop;

        sim.simulateInsn(Opcodes.ICONST_0);  // I
        sim.simulateVarInsn(Opcodes.ISTORE);  // (empty)
        Label loopStart = new Label();
        sim.saveLabel(loopStart);

        sim.simulateVarInsn(Opcodes.ILOAD);   // I
        sim.simulateVarInsn(Opcodes.ILOAD);   // I, I
        Label exit = new Label();
        sim.simulateJumpInsn(Opcodes.IF_ICMPGE, exit);  // (empty)

        sim.simulateVarInsn(Opcodes.ALOAD);   // A (list)
        sim.simulateVarInsn(Opcodes.ILOAD);   // A, I (index)
        // Invoke list.get(int) — should capture sub-stack (empty)
        sim.simulateMethodInsn(0, Opcodes.INVOKEVIRTUAL, "(I)Ljava/lang/Object;", false);
        assertEquals('A', sim.pop()); // return value
        assertTrue(sim.getSubStack(0).isEmpty()); // no sub-stack items

        // GOTO loopStart
        sim.simulateJumpInsn(Opcodes.GOTO, loopStart);
        assertTrue(sim.isLost());

        // Recover at exit
        sim.visitLabel(exit);
        assertFalse(sim.isLost());
    }

    // ===== Obscure edge cases =====

    @Test
    void dup2x2PuntsToLost() {
        // DUP2_X2 is extremely rare and has 4 forms depending on category
        // combinations. The simulator punts by marking lost rather than
        // attempting to track it. Verify the punt is clean.
        sim.push('I');
        sim.push('I');
        sim.push('I');
        sim.push('I');
        sim.simulateInsn(Opcodes.DUP2_X2);
        assertTrue(sim.isLost());
    }

    @Test
    void dup2x2RecoveryViaLabel() {
        // After DUP2_X2 punts, the simulator should recover at the next
        // label that has a saved state.
        sim.push('A');
        Label recovery = new Label();
        sim.saveLabel(recovery);

        sim.push('I');
        sim.push('I');
        sim.push('I');
        sim.push('I');
        sim.simulateInsn(Opcodes.DUP2_X2);
        assertTrue(sim.isLost());

        sim.visitLabel(recovery);
        assertFalse(sim.isLost());
        assertEquals('A', sim.pop());
    }

    @Test
    void captureSubStackWithMoreArgsThanStackItems() {
        // If the invoke consumes more args than are on the stack (shouldn't
        // happen with valid bytecode, but test defensive behavior).
        sim.push('I');
        sim.captureSubStack(0, 5); // claims 5 args but only 1 on stack
        // subSize = max(0, 1 - 5) = 0, so sub-stack should be empty
        assertTrue(sim.getSubStack(0).isEmpty());
    }

    @Test
    void captureSubStackWithZeroArgs() {
        // Static void method with no args — entire stack is sub-stack
        sim.push('I');
        sim.push('A');
        sim.captureSubStack(0, 0);
        List<Character> sub = sim.getSubStack(0);
        assertEquals(2, sub.size());
        assertEquals('I', sub.get(0));
        assertEquals('A', sub.get(1));
    }

    @Test
    void multipleSubStackCaptures() {
        // Two invokes at different stack depths
        sim.push('I'); // sub-stack for invoke 0
        sim.captureSubStack(0, 0);
        sim.pop(); // consume I

        sim.push('A');
        sim.push('J'); // sub-stack for invoke 1
        sim.captureSubStack(1, 0);

        assertEquals(1, sim.getSubStack(0).size());
        assertEquals('I', sim.getSubStack(0).get(0));
        assertEquals(2, sim.getSubStack(1).size());
        assertEquals('A', sim.getSubStack(1).get(0));
        assertEquals('J', sim.getSubStack(1).get(1));
    }

    @Test
    void subStackCaptureIsSnapshot() {
        // Verify captured sub-stack is a snapshot, not a live reference.
        // Subsequent stack operations should NOT affect previously captured sub-stacks.
        sim.push('I');
        sim.captureSubStack(0, 0);
        sim.push('A'); // modify stack after capture
        sim.captureSubStack(1, 0);

        assertEquals(1, sim.getSubStack(0).size()); // should still be [I]
        assertEquals(2, sim.getSubStack(1).size()); // should be [I, A]
    }

    @Test
    void jsrPushesReturnAddress() {
        // JSR (pre-Java 6, still valid bytecode) pushes a return address
        sim.simulateJumpInsn(Opcodes.JSR, new Label());
        assertEquals('A', sim.pop()); // return address is category-1
        assertFalse(sim.isLost()); // JSR does NOT mark lost
    }

    @Test
    void ifnullAndIfnonnull() {
        sim.push('A');
        sim.simulateJumpInsn(Opcodes.IFNULL, new Label());
        assertEquals('?', sim.peek()); // consumed

        sim.push('A');
        sim.simulateJumpInsn(Opcodes.IFNONNULL, new Label());
        assertEquals('?', sim.peek()); // consumed
    }

    @Test
    void negationOperations() {
        sim.push('I');
        sim.simulateInsn(Opcodes.INEG);
        assertEquals('I', sim.pop());

        sim.push('J');
        sim.simulateInsn(Opcodes.LNEG);
        assertEquals('J', sim.pop());

        sim.push('F');
        sim.simulateInsn(Opcodes.FNEG);
        assertEquals('F', sim.pop());

        sim.push('D');
        sim.simulateInsn(Opcodes.DNEG);
        assertEquals('D', sim.pop());
    }

    @Test
    void narrowingConversions() {
        sim.push('I');
        sim.simulateInsn(Opcodes.I2B);
        assertEquals('I', sim.pop()); // still I (JVM category)

        sim.push('I');
        sim.simulateInsn(Opcodes.I2C);
        assertEquals('I', sim.pop());

        sim.push('I');
        sim.simulateInsn(Opcodes.I2S);
        assertEquals('I', sim.pop());
    }

    @Test
    void nopDoesNotAffectStack() {
        sim.push('I');
        sim.simulateInsn(Opcodes.NOP);
        assertEquals('I', sim.pop());
    }

    @Test
    void dup2x1Form1() {
        // v3, v2, v1 (all cat-1) → v2, v1, v3, v2, v1
        sim.push('A'); // v3
        sim.push('I'); // v2
        sim.push('F'); // v1
        sim.simulateInsn(Opcodes.DUP2_X1);
        assertEquals('F', sim.pop()); // v1
        assertEquals('I', sim.pop()); // v2
        assertEquals('A', sim.pop()); // v3
        assertEquals('F', sim.pop()); // v1
        assertEquals('I', sim.pop()); // v2
    }

    @Test
    void dup2x1Form2() {
        // v2(cat-1), v1(cat-2) → v1, v2, v1
        sim.push('I'); // v2
        sim.push('J'); // v1 (wide)
        sim.simulateInsn(Opcodes.DUP2_X1);
        assertEquals('J', sim.pop());
        assertEquals('I', sim.pop());
        assertEquals('J', sim.pop());
    }

    @Test
    void complexBranchingWithMultipleLabelSaves() {
        // if (cond) { path1 } else { path2 } — both paths reach a merge
        Label elseLabel = new Label();
        Label mergeLabel = new Label();

        sim.push('I'); // value on stack before branch
        sim.simulateJumpInsn(Opcodes.IFEQ, elseLabel); // pops I, saves at else

        // if-path: push A, then goto merge
        sim.push('A');
        sim.simulateJumpInsn(Opcodes.GOTO, mergeLabel);
        assertTrue(sim.isLost());

        // else-path: recover, push A
        sim.visitLabel(elseLabel);
        assertFalse(sim.isLost());
        // Stack was saved AFTER popping I, so it's empty here
        assertEquals('?', sim.peek());
        sim.push('A');
        // Fall through to merge

        sim.visitLabel(mergeLabel);
        // mergeLabel was saved from GOTO with [A] on stack
        // But visitLabel recovers only if lost — we're not lost here
        // So the current stack (from else-path) continues: [A]
        assertEquals('A', sim.pop());
    }

    @Test
    void tryCatchWithNestedHandlers() {
        Label handler1 = new Label();
        Label handler2 = new Label();
        sim.simulateTryCatchBlock(handler1);
        sim.simulateTryCatchBlock(handler2);

        sim.push('I'); // some work
        sim.simulateInsn(Opcodes.ATHROW); // throw
        assertTrue(sim.isLost());

        // Enter handler1
        sim.visitLabel(handler1);
        assertEquals('A', sim.pop()); // exception

        sim.markLost();
        // Enter handler2
        sim.visitLabel(handler2);
        assertEquals('A', sim.pop()); // exception
    }

    @Test
    void deepStackSubStackCapture() {
        // 10 items on stack, invoke consumes 3 (receiver + 2 args)
        for (int i = 0; i < 10; i++) {
            sim.push(i % 2 == 0 ? 'I' : 'A');
        }
        sim.captureSubStack(0, 3);
        List<Character> sub = sim.getSubStack(0);
        assertEquals(7, sub.size()); // 10 - 3 = 7 sub-stack items
    }

    @Test
    void fieldInsnWithAllFieldTypes() {
        // Verify all JVM field types are mapped correctly
        for (String[] pair : new String[][]{
                {"Z", "I"}, {"B", "I"}, {"C", "I"}, {"S", "I"}, {"I", "I"},
                {"J", "J"}, {"F", "F"}, {"D", "D"},
                {"Ljava/lang/Object;", "A"}, {"[I", "A"}
        }) {
            sim.simulateFieldInsn(Opcodes.GETSTATIC, pair[0]);
            assertEquals(pair[1].charAt(0), sim.pop(),
                    "Field type " + pair[0] + " should produce category " + pair[1]);
        }
    }

    @Test
    void methodInsnWithComplexDescriptor() {
        // Method with mixed arg types: (ILjava/lang/String;DJ)Z
        // Receiver + 4 args (I=1, A=1, D=1, J=1) + receiver = 5 entries
        sim.push('F'); // sub-stack item
        sim.push('A'); // receiver
        sim.push('I'); // arg1
        sim.push('A'); // arg2 (String)
        sim.push('D'); // arg3 (double)
        sim.push('J'); // arg4 (long)
        sim.simulateMethodInsn(0, Opcodes.INVOKEVIRTUAL,
                "(ILjava/lang/String;DJ)Z", false);
        assertEquals('I', sim.pop()); // boolean returns as I
        assertEquals('F', sim.pop()); // sub-stack preserved

        List<Character> sub = sim.getSubStack(0);
        assertEquals(1, sub.size());
        assertEquals('F', sub.get(0));
    }

    @Test
    void saveAndRecoverMultipleLabelsFromDifferentStates() {
        Label a = new Label();
        Label b = new Label();

        sim.push('I');
        sim.saveLabel(a); // save [I]

        sim.push('A');
        sim.saveLabel(b); // save [I, A]

        sim.markLost();

        sim.visitLabel(a);
        assertEquals('I', sim.pop());
        assertEquals('?', sim.peek()); // just [I] was saved

        sim.markLost();
        sim.visitLabel(b);
        assertEquals('A', sim.pop());
        assertEquals('I', sim.pop());
    }
}
