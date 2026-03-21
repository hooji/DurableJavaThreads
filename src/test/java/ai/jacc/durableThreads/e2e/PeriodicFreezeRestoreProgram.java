package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: loop 0 to 100, freeze and restore every time i % 5 == 1.
 * Each freeze writes a snapshot. The test verifies the first freeze succeeds
 * and produces a valid snapshot.
 *
 * <p>This exercises the periodic checkpoint pattern: long-running computation
 * that periodically saves its state so it can resume after failure.</p>
 *
 * <p>Usage: PeriodicFreezeRestoreProgram &lt;snapshotDir&gt;</p>
 */
public class PeriodicFreezeRestoreProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: PeriodicFreezeRestoreProgram <snapshotDir>");
            System.exit(1);
        }
        String snapshotDir = args[0];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runWorkflow(snapshotDir);
            }
        }, "periodic-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(90_000);
    }

    static void runWorkflow(String snapshotDir) {
        int sum = 0;
        int freezeCount = 0;

        for (int i = 0; i <= 100; i++) {
            sum += i;

            if (i % 5 == 1) {
                final int capturedI = i;
                final int capturedSum = sum;
                freezeCount++;
                final int capturedFreezeCount = freezeCount;

                Durable.freeze(snapshot -> {
                    try {
                        byte[] bytes = serialize(snapshot);
                        Path file = Paths.get(snapshotDir, "checkpoint-" + capturedI + ".bin");
                        Files.write(file, bytes);
                        System.out.println("FREEZE i=" + capturedI
                                + " sum=" + capturedSum
                                + " size=" + bytes.length
                                + " freezeNum=" + capturedFreezeCount);
                        System.out.flush();
                    } catch (Exception e) {
                        System.err.println("FREEZE_ERROR at i=" + capturedI + ": " + e.getMessage());
                        e.printStackTrace(System.err);
                    }
                });

                // Only in RESTORED thread
                System.out.println("RESTORED i=" + i + " sum=" + sum);
                System.out.flush();
            }
        }

        System.out.println("FINAL_SUM=" + sum);
        System.out.println("FREEZE_COUNT=" + freezeCount);
        System.out.println("ALL_ITERATIONS_COMPLETE");
        System.out.flush();
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
