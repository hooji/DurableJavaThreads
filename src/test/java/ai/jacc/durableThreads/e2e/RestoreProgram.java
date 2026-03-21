package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * Child-JVM program that restores a previously frozen thread.
 *
 * <p>Usage: RestoreProgram &lt;snapshotFile&gt;</p>
 *
 * <p>Reads the snapshot from the file, calls Durable.restore(), and starts
 * the restored thread. The restored thread continues from after the freeze
 * point, printing its state.</p>
 *
 * <p>If the restored thread throws an exception, prints RESTORE_FAILED
 * and exits with code 1.</p>
 */
public class RestoreProgram {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: RestoreProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];

        byte[] bytes = Files.readAllBytes(Paths.get(snapshotFile));
        ThreadSnapshot snapshot = deserialize(bytes);

        System.out.println("SNAPSHOT_LOADED=true");
        System.out.println("FRAME_COUNT=" + snapshot.frameCount());
        System.out.flush();

        Thread restored = Durable.restore(snapshot);

        // Capture any uncaught exception from the restored thread
        Throwable[] threadError = new Throwable[1];
        restored.setUncaughtExceptionHandler((t, e) -> {
            threadError[0] = e;
            System.err.println("RESTORED_THREAD_ERROR=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.err.flush();
        });

        restored.start();
        restored.join(30_000);

        if (threadError[0] != null) {
            System.out.println("RESTORE_FAILED=" + threadError[0].getMessage());
            System.out.flush();
            System.exit(1);
        }

        System.out.println("RESTORE_COMPLETE");
        System.out.flush();
    }

    private static ThreadSnapshot deserialize(byte[] bytes) throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (ThreadSnapshot) ois.readObject();
        }
    }
}
