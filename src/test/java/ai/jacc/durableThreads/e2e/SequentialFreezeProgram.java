package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * Child-JVM program that runs a loop 0–100, freezing every time i % 5 == 0.
 * Each freeze serializes the snapshot and records its size.
 * Verifies that all restores receive the correct i value and that snapshot
 * sizes are stable.
 *
 * <p>Usage: SequentialFreezeProgram &lt;snapshotDir&gt;</p>
 *
 * <p>Prints structured output for the test harness to parse.</p>
 */
public class SequentialFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SequentialFreezeProgram <snapshotDir>");
            System.exit(1);
        }
        String snapshotDir = args[0];

        // Use anonymous Runnable — lambda frames cause LambdaFrameException
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runWorkflow(snapshotDir);
            }
        }, "seq-freeze-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e);
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(90_000);
    }

    static void runWorkflow(String snapshotDir) {
        int sum = 0;
        int freezeCount = 0;
        int minSnapshotSize = Integer.MAX_VALUE;
        int maxSnapshotSize = 0;

        for (int i = 0; i <= 100; i++) {
            sum += i;

            if (i % 5 == 0) {
                final int capturedI = i;
                final int capturedSum = sum;
                final int capturedFreezeCount = ++freezeCount;

                Durable.freeze(snapshot -> {
                    try {
                        byte[] bytes = serialize(snapshot);
                        Path file = Paths.get(snapshotDir, "snapshot-" + capturedI + ".bin");
                        Files.write(file, bytes);
                        System.out.println("FREEZE i=" + capturedI
                                + " sum=" + capturedSum
                                + " size=" + bytes.length
                                + " frames=" + snapshot.frameCount()
                                + " freezeNum=" + capturedFreezeCount);
                        System.out.flush();
                    } catch (Exception e) {
                        System.err.println("FREEZE_ERROR at i=" + capturedI + ": " + e.getMessage());
                        e.printStackTrace(System.err);
                    }
                });

                // This line executes in the RESTORED thread.
                // In a real system, we'd restore from the snapshot file.
                // For this single-process test, freeze() returns normally
                // (in the restored thread) and continues the loop.
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
