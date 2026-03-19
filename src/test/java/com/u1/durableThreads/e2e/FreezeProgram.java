package com.u1.durableThreads.e2e;

import com.u1.durableThreads.Durable;
import com.u1.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * Child-JVM program that performs a freeze operation.
 *
 * <p>Usage: FreezeProgram &lt;snapshotFile&gt;</p>
 *
 * <p>The program does work, calls Durable.freeze() to persist the snapshot
 * to the given file, and the original thread terminates. Output lines:</p>
 * <ul>
 *   <li>BEFORE_FREEZE=&lt;value&gt; — state before freezing</li>
 *   <li>FREEZE_COMPLETE — snapshot written successfully</li>
 *   <li>AFTER_FREEZE=&lt;value&gt; — only prints in restored thread</li>
 * </ul>
 */
public class FreezeProgram {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: FreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];

        // Use anonymous Runnable instead of lambda — lambda frames ($$Lambda)
        // are not portable and would cause LambdaFrameException during freeze.
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "freeze-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) {
                // Expected — thread was frozen
                return;
            }
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        int counter = 42;
        String message = "hello-from-freeze";

        System.out.println("BEFORE_FREEZE=" + counter);
        System.out.flush();

        Durable.freeze(snapshot -> {
            try {
                byte[] bytes = serialize(snapshot);
                Files.write(Path.of(snapshotFile), bytes);
                System.out.println("FREEZE_COMPLETE");
                System.out.println("SNAPSHOT_SIZE=" + bytes.length);
                System.out.println("FRAME_COUNT=" + snapshot.frameCount());
                System.out.flush();
            } catch (Exception e) {
                System.err.println("FREEZE_ERROR=" + e.getMessage());
                e.printStackTrace(System.err);
            }
        });

        // This line only executes in a RESTORED thread
        System.out.println("AFTER_FREEZE=" + counter);
        System.out.println("MESSAGE=" + message);
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
