package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;
import java.util.Optional;

/**
 * E2E program: attempt to freeze with an unsupported type (Optional).
 * This should FAIL with an UncapturableTypeException, not produce a snapshot.
 *
 * <p>The test verifies that the library detects types it cannot restore
 * correctly and fails fast rather than producing a corrupt snapshot.</p>
 *
 * <p>Usage: UnsupportedTypeFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class UnsupportedTypeFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: UnsupportedTypeFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "unsupported-type-worker");
        worker.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
                // Print the full exception chain for the test to inspect
                Throwable cause = e;
                while (cause != null) {
                    System.out.println("EXCEPTION=" + cause.getClass().getName()
                            + ": " + cause.getMessage());
                    cause = cause.getCause();
                }
                System.out.flush();
            }
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        // This type is NOT supported — should cause freeze to fail
        Optional<String> maybeValue = Optional.of("hello");

        int marker = 42;

        System.out.println("BEFORE_FREEZE");
        System.out.flush();

        Durable.freeze(new java.util.function.Consumer<ThreadSnapshot>() {
            @Override
            public void accept(ThreadSnapshot snapshot) {
                try {
                    byte[] bytes = serialize(snapshot);
                    Files.write(Paths.get(snapshotFile), bytes);
                    System.out.println("FREEZE_COMPLETE");
                    System.out.flush();
                } catch (Exception ex) {
                    System.err.println("FREEZE_ERROR=" + ex.getMessage());
                    ex.printStackTrace(System.err);
                }
            }
        });

        // Should never reach here — freeze should have failed
        System.out.println("AFTER_FREEZE marker=" + marker);
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
