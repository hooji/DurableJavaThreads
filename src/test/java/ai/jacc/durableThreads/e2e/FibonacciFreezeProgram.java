package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: freeze inside a recursive fibonacci computation.
 * Verifies that recursive call frames and local variables survive freeze/restore.
 *
 * <p>Computes fib(10) = 55. Freezes when n == freezeAtN (default 5).
 * After restore, the recursion should continue and produce the correct result.</p>
 *
 * <p>Usage: FibonacciFreezeProgram &lt;snapshotFile&gt; [freezeAtN]</p>
 */
public class FibonacciFreezeProgram {

    private static volatile boolean frozen = false;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: FibonacciFreezeProgram <snapshotFile> [freezeAtN]");
            System.exit(1);
        }
        String snapshotFile = args[0];
        int freezeAtN = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile, freezeAtN);
            }
        }, "fib-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile, int freezeAtN) {
        System.out.println("COMPUTING fib(10) freezeAt=" + freezeAtN);
        System.out.flush();

        int result = fib(10, freezeAtN, snapshotFile);

        // Only in RESTORED thread
        System.out.println("FIB_RESULT=" + result);
        System.out.flush();
    }

    /**
     * Recursive fibonacci. Freezes once when n == freezeAtN.
     * Stores intermediate results in local variables (not on operand stack).
     */
    public static int fib(int n, int freezeAtN, String snapshotFile) {
        if (n <= 1) {
            return n;
        }

        // Freeze once at the target recursion depth
        if (n == freezeAtN && !frozen) {
            frozen = true;
            System.out.println("FREEZING at n=" + n);
            System.out.flush();

            Durable.freeze(snapshot -> {
                try {
                    byte[] bytes = serialize(snapshot);
                    Files.write(Paths.get(snapshotFile), bytes);
                    System.out.println("FREEZE_COMPLETE");
                    System.out.println("FRAME_COUNT=" + snapshot.frameCount());
                    System.out.flush();
                } catch (Exception e) {
                    System.err.println("FREEZE_ERROR=" + e.getMessage());
                    e.printStackTrace(System.err);
                }
            });

            // In the restore JVM, the static 'frozen' flag starts as false.
            // Re-set it here so recursive branches that re-enter fib(freezeAtN)
            // don't attempt a second freeze (which would fail because the JDWP
            // connection from the restore process is still active).
            frozen = true;
            System.out.println("RESTORED at n=" + n);
            System.out.flush();
        }

        int a = fib(n - 1, freezeAtN, snapshotFile);
        int b = fib(n - 2, freezeAtN, snapshotFile);
        int result = a + b;
        return result;
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
