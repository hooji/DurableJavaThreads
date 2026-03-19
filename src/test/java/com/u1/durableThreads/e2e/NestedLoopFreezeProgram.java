package com.u1.durableThreads.e2e;

import com.u1.durableThreads.Durable;
import com.u1.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: freeze inside nested loops.
 * Verifies that multiple loop counters and accumulators survive freeze/restore.
 *
 * <p>Runs 5x5 nested loop, freezes at (i=2, j=3). After restore, both loops
 * should continue and complete correctly.</p>
 *
 * <p>Usage: NestedLoopFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class NestedLoopFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: NestedLoopFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "nested-loop-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        int[] result = freezeInNestedLoop(5, 5, 2, 3, snapshotFile);
        // Only in RESTORED thread
        if (result != null) {
            System.out.println("TOTAL_ITERATIONS=" + result[0]);
            System.out.println("FROZE_AT_TOTAL=" + result[1]);
        } else {
            System.out.println("NESTED_RESULT=null");
        }
        System.out.flush();
    }

    public static int[] freezeInNestedLoop(int outerSize, int innerSize,
                                            int freezeI, int freezeJ,
                                            String snapshotFile) {
        int totalIterations = 0;
        int frozeAtTotal = -1;
        for (int i = 0; i < outerSize; i++) {
            for (int j = 0; j < innerSize; j++) {
                if (i == freezeI && j == freezeJ) {
                    frozeAtTotal = totalIterations;
                    System.out.println("BEFORE_FREEZE i=" + i + " j=" + j + " total=" + totalIterations);
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

                    System.out.println("AFTER_FREEZE i=" + i + " j=" + j + " total=" + totalIterations);
                    System.out.flush();
                }
                totalIterations++;
            }
        }
        return new int[]{totalIterations, frozeAtTotal};
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
