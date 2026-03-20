package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: freeze inside a loop.
 * Verifies that after restore, the loop counter and accumulator
 * have the correct values and the loop continues from the right iteration.
 *
 * <p>Runs a loop 0..9, freezes at iteration 4. After restore, the loop
 * should continue from iteration 4 and complete all remaining iterations.</p>
 *
 * <p>Usage: LoopFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class LoopFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: LoopFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];
        // Use anonymous Runnable — lambda frames cause LambdaFrameException
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "loop-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        int result = computeWithFreeze(snapshotFile);
        // Only in RESTORED thread
        System.out.println("LOOP_RESULT=" + result);
        System.out.flush();
    }

    public static int computeWithFreeze(String snapshotFile) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += i;
            System.out.println("ITERATION i=" + i + " sum=" + sum);
            System.out.flush();

            if (i == 4) {
                // sum = 0+1+2+3+4 = 10 at this point
                System.out.println("BEFORE_FREEZE sum=" + sum);
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

                // Only in RESTORED thread
                System.out.println("AFTER_FREEZE i=" + i + " sum=" + sum);
                System.out.flush();
            }
        }
        // sum = 0+1+2+3+4+5+6+7+8+9 = 45
        System.out.println("FINAL_SUM=" + sum);
        System.out.flush();
        return sum;
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
