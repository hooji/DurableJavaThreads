package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

/**
 * E2E program: freeze and restore in the SAME JVM process.
 *
 * <p>A worker thread counts from 2 to 12, freezing at i==5.
 * The main thread waits for the freeze, then calls
 * {@code Durable.restore(file)} to restore and
 * wait for the thread in the same process.</p>
 *
 * <p>Expected output (stdout only, no exceptions on stderr):</p>
 * <pre>
 * i=2
 * i=3
 * i=4
 * i=5
 * About to freeze!
 * Thread frozen...
 * Resumed!
 * i=6
 * i=7
 * i=8
 * i=9
 * i=10
 * i=11
 * i=12
 * Done!
 * RESTORE_COMPLETE
 * </pre>
 *
 * <p>Usage: SameJvmFreezeRestoreProgram &lt;snapshotFile&gt;</p>
 */
public class SameJvmFreezeRestoreProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SameJvmFreezeRestoreProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "same-jvm-worker");
        worker.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
                System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace(System.err);
            }
        });
        worker.start();
        worker.join(30_000);

        System.out.println("Thread frozen...");
        System.out.flush();

        // Now restore in the SAME JVM
        try {
            Durable.restore(snapshotFile);
            System.out.println("RESTORE_COMPLETE");
        } catch (Exception e) {
            System.out.println("RESTORE_FAILED=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }
        System.out.flush();
    }

    static void doWork(String snapshotFile) {
        for (int i = 2; i <= 12; i++) {
            System.out.println("i=" + i);
            if (i == 5) {
                System.out.println("About to freeze!");
                System.out.flush();
                Durable.freeze(snapshotFile);
                // Everything below only runs after restore
                System.out.println("Resumed!");
            }
        }
        System.out.println("Done!");
        System.out.flush();
    }
}
