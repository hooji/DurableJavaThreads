package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: freeze a thread and restore it in the SAME JVM without
 * restarting. This exercises the lifecycle where the JDI connection from
 * freeze must be reused during restore (JDWP only allows one debugger).
 *
 * <p>Usage: SameJvmFreezeRestoreProgram &lt;snapshotFile&gt;</p>
 *
 * <p>Expected output on success:</p>
 * <pre>
 *   i=2 .. i=5
 *   ABOUT_TO_FREEZE
 *   FREEZE_COMPLETE
 *   RESTORING_SAME_JVM
 *   RESUMED
 *   i=6 .. i=12
 *   DONE
 *   SAME_JVM_RESTORE_COMPLETE
 * </pre>
 */
public class SameJvmFreezeRestoreProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SameJvmFreezeRestoreProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];

        // Freeze in a worker thread
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "same-jvm-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);

        // Now restore in the same JVM
        System.out.println("RESTORING_SAME_JVM");
        System.out.flush();

        Thread restored = Durable.restore(snapshotFile);

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

        System.out.println("SAME_JVM_RESTORE_COMPLETE");
        System.out.flush();
    }

    static void doWork(String snapshotFile) {
        for (int i = 2; i <= 12; i++) {
            System.out.println("i=" + i);
            if (i == 5) {
                System.out.println("ABOUT_TO_FREEZE");
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
                // Only runs after restore
                System.out.println("RESUMED");
                System.out.flush();
            }
        }
        System.out.println("DONE");
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
