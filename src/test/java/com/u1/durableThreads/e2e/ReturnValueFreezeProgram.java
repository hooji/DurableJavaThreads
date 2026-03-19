package com.u1.durableThreads.e2e;

import com.u1.durableThreads.Durable;
import com.u1.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: freeze between method calls that return values.
 * Verifies that return values from calls before the freeze are preserved,
 * and calls after the freeze execute correctly in the restored thread.
 *
 * <p>Computation: step1 → step2 → FREEZE → step3 → step4 → result</p>
 *
 * <p>Usage: ReturnValueFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class ReturnValueFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ReturnValueFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];
        Durable.installExceptionHandler();

        Thread worker = new Thread(() -> {
            int result = chainedComputation(5, snapshotFile);
            // Only in RESTORED thread
            System.out.println("CHAIN_RESULT=" + result);
            System.out.flush();
        }, "return-value-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    public static int chainedComputation(int input, String snapshotFile) {
        // Before freeze: compute intermediate values
        int v1 = step1(input);   // 5 + 10 = 15
        int v2 = step2(v1);      // 15 * 3 = 45
        int v3 = step3(v2);      // 45 - 2 = 43

        System.out.println("BEFORE_FREEZE v1=" + v1 + " v2=" + v2 + " v3=" + v3);
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

        // After freeze (only in RESTORED thread):
        // v1, v2, v3 must still have their pre-freeze values
        System.out.println("AFTER_FREEZE v1=" + v1 + " v2=" + v2 + " v3=" + v3);
        System.out.flush();

        int v4 = step4(v3);      // 43 / 2 = 21 (integer division)
        int v5 = step5(v4);      // 21 + 100 = 121
        int finalResult = v1 + v2 + v3 + v4 + v5; // 15 + 45 + 43 + 21 + 121 = 245

        System.out.println("AFTER_COMPUTE v4=" + v4 + " v5=" + v5);
        System.out.flush();

        return finalResult;
    }

    static int step1(int x) { return x + 10; }
    static int step2(int x) { return x * 3; }
    static int step3(int x) { return x - 2; }
    static int step4(int x) { return x / 2; }
    static int step5(int x) { return x + 100; }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
