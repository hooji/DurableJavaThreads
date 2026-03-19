package com.u1.durableThreads.e2e;

import com.u1.durableThreads.Durable;
import com.u1.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: freeze 3 levels deep in a call chain.
 * Verifies that multi-frame replay reconstructs the full call stack
 * and returns the correct computed value.
 *
 * <p>Call chain: main → outerMethod → middleMethod → innerMethod (freeze here)</p>
 *
 * <p>Usage: DeepCallChainFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class DeepCallChainFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: DeepCallChainFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];
        // Use anonymous Runnable — lambda frames cause LambdaFrameException
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "deep-chain-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        int result = outerMethod(5, snapshotFile);
        // This only executes in the RESTORED thread
        System.out.println("DEEP_RESULT=" + result);
        System.out.flush();
    }

    public static int outerMethod(int value, String snapshotFile) {
        int a = value + 10; // a = 15
        System.out.println("OUTER_BEFORE=" + a);
        System.out.flush();
        int b = middleMethod(a, snapshotFile);
        System.out.println("OUTER_AFTER=" + b);
        System.out.flush();
        return b + 1000; // 1217
    }

    public static int middleMethod(int value, String snapshotFile) {
        int x = value * 2; // x = 30
        System.out.println("MIDDLE_BEFORE=" + x);
        System.out.flush();
        int y = innerMethod(x, snapshotFile);
        System.out.println("MIDDLE_AFTER=" + y);
        System.out.flush();
        return y + 100; // 217
    }

    public static int innerMethod(int value, String snapshotFile) {
        int computed = value + 7; // computed = 37
        System.out.println("INNER_BEFORE=" + computed);
        System.out.flush();

        Durable.freeze(snapshot -> {
            try {
                byte[] bytes = serialize(snapshot);
                Files.write(Path.of(snapshotFile), bytes);
                System.out.println("FREEZE_COMPLETE");
                System.out.println("FRAME_COUNT=" + snapshot.frameCount());
                System.out.flush();
            } catch (Exception e) {
                System.err.println("FREEZE_ERROR=" + e.getMessage());
                e.printStackTrace(System.err);
            }
        });

        // Only in RESTORED thread:
        int afterFreeze = computed + 80; // 117
        System.out.println("INNER_AFTER=" + afterFreeze);
        System.out.flush();
        return afterFreeze;
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
