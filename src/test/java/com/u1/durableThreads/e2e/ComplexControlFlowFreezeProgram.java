package com.u1.durableThreads.e2e;

import com.u1.durableThreads.Durable;
import com.u1.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: freeze in a method with complex control flow and many invokes.
 *
 * <p>Combines if/else branching, nested loops, try-catch-finally, switch,
 * and many method calls at various nesting levels. The freeze occurs deep
 * inside a helper called from within a loop inside a try block. This is
 * designed to stress-test that the invoke index computation handles complex
 * bytecode structures where many invoke instructions precede the freeze point
 * across different control flow constructs.</p>
 *
 * <p>Usage: ComplexControlFlowFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class ComplexControlFlowFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ComplexControlFlowFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "complex-flow-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        int result = complexMethod(snapshotFile);
        // Only in RESTORED thread
        System.out.println("COMPLEX_RESULT=" + result);
        System.out.flush();
    }

    /**
     * A method with complex control flow and many invokes before the freeze.
     * Each invoke that executes before freeze must be correctly indexed.
     */
    public static int complexMethod(String snapshotFile) {
        int total = 0;

        // --- Phase 1: if/else with method calls in both branches ---
        int x = compute(5);       // invoke 0: x=10
        if (x > 5) {
            total += transform(x);   // invoke 1: total=20 (this branch taken)
        } else {
            total += transform(0);   // invoke 2: not taken
        }

        // --- Phase 2: loop with method calls ---
        for (int i = 0; i < 3; i++) {
            total += increment(i);   // invoke 3 (loop body, called 3 times): total += 1+2+3 = 26
        }

        // --- Phase 3: try-catch with method calls ---
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("try");        // invoke 4
            total += safeCompute(total);  // invoke 5: total += 52 = 78
        } catch (Exception e) {
            sb.append("catch");      // not reached
        }

        // --- Phase 4: switch with method calls ---
        int mode = total % 4;  // 78 % 4 = 2
        switch (mode) {
            case 0: total += compute(1); break;
            case 1: total += compute(2); break;
            case 2: total += compute(3); break;    // invoke 6: total += 6 = 84 (this case taken)
            case 3: total += compute(4); break;
        }

        // --- Phase 5: nested call into helper where freeze happens ---
        int helperResult = freezeHelper(total, sb, snapshotFile);

        // Only in RESTORED thread:
        total += helperResult;
        System.out.println("AFTER_FREEZE total=" + total);
        System.out.flush();
        return total;
    }

    /**
     * Helper method that does more work then freezes.
     * The invoke index for this method's frame must also be correct.
     */
    public static int freezeHelper(int value, StringBuilder sb, String snapshotFile) {
        int a = compute(value);      // a = value * 2 = 168
        sb.append("-helper");        // invoke: append

        System.out.println("BEFORE_FREEZE value=" + value + " a=" + a + " sb=" + sb.toString());
        System.out.flush();

        Durable.freeze(snapshot -> {
            try {
                byte[] bytes = serialize(snapshot);
                Files.write(Path.of(snapshotFile), bytes);
                System.out.println("FREEZE_COMPLETE");
                System.out.flush();
            } catch (Exception e) {
                System.err.println("FREEZE_ERROR=" + e.getMessage());
                e.printStackTrace(System.err);
            }
        });

        // Only in RESTORED thread:
        int afterFreeze = a + 100;
        System.out.println("HELPER_AFTER a=" + a + " afterFreeze=" + afterFreeze);
        System.out.println("SB_AFTER=" + sb.toString());
        System.out.flush();
        return afterFreeze;
    }

    public static int compute(int x) { return x * 2; }
    public static int transform(int x) { return x * 2; }
    public static int increment(int x) { return x + 1; }
    public static int safeCompute(int x) { return x * 2; }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
