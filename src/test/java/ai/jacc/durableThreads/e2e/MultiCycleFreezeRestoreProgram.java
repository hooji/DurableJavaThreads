package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

/**
 * E2E program: multiple freeze/restore cycles in the SAME JVM.
 *
 * <p>A worker thread counts from 1 to 20, freezing every 5 iterations.
 * The main thread restores the thread each time, driving multiple
 * freeze → restore → re-freeze cycles all within one process.</p>
 *
 * <p>Usage: MultiCycleFreezeRestoreProgram &lt;snapshotFile&gt;</p>
 */
public class MultiCycleFreezeRestoreProgram {

    static volatile boolean running = false;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: MultiCycleFreezeRestoreProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];

        // First run: start the worker thread
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "multi-cycle-worker");
        worker.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
                System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace(System.err);
            }
        });
        running = true;
        worker.start();
        worker.join(30_000);

        // Restore loop: each restore may freeze again
        int restoreCount = 0;
        while (running) {
            restoreCount++;
            System.out.println("RESTORE_CYCLE=" + restoreCount);
            System.out.flush();
            try {
                Durable.restore(snapshotFile, true, true);
            } catch (Exception e) {
                System.out.println("RESTORE_FAILED=" + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace(System.err);
                System.out.flush();
                System.exit(1);
            }
        }

        System.out.println("RESTORE_COUNT=" + restoreCount);
        System.out.println("ALL_COMPLETE");
        System.out.flush();
    }

    static void doWork(String snapshotFile) {
        running = true;
        for (int i = 1; i <= 20; i++) {
            System.out.println("i=" + i);
            if (i % 5 == 0) {
                System.out.println("About to freeze!");
                System.out.flush();
                Durable.freeze(snapshotFile);
                System.out.println("Resumed!");
            }
        }
        System.out.println("Done!");
        System.out.flush();
        running = false;
    }
}
