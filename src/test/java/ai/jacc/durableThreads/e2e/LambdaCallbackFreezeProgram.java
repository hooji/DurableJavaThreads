package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

/**
 * E2E program: freeze inside a lambda passed as a callback to a user method.
 *
 * <p>This tests the case where a lambda is passed to a user-defined method
 * (not a JDK method like forEach). The call stack has:</p>
 * <pre>
 *   doWork() → processItems() → callback.accept() → [$$Lambda] → lambda body → freeze()
 * </pre>
 *
 * <p>Expected output after lambda support is implemented:</p>
 * <pre>
 * Processing item: alpha
 * Processing item: beta
 * Processing item: gamma
 * Freezing at item: gamma
 * Restored at item: gamma
 * Processing item: delta
 * ITEMS_PROCESSED=4
 * RESTORE_COMPLETE
 * </pre>
 *
 * <p>Usage: LambdaCallbackFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class LambdaCallbackFreezeProgram {

    public interface ItemProcessor {
        void process(String item);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: LambdaCallbackFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "lambda-callback-worker");
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
        String[] items = {"alpha", "beta", "gamma", "delta"};
        int[] count = {0};

        // Pass a lambda callback to a user method.
        // The lambda captures snapshotFile and count.
        processItems(items, (item) -> {
            System.out.println("Processing item: " + item);
            System.out.flush();
            count[0]++;
            if ("gamma".equals(item)) {
                System.out.println("Freezing at item: " + item);
                System.out.flush();
                Durable.freeze(snapshotFile);
                System.out.println("Restored at item: " + item);
                System.out.flush();
            }
        });

        System.out.println("ITEMS_PROCESSED=" + count[0]);
        System.out.flush();
    }

    /**
     * User-defined method that accepts a callback. NOT a JDK method.
     * This creates the call chain: doWork → processItems → callback → lambda.
     */
    public static void processItems(String[] items, ItemProcessor processor) {
        for (String item : items) {
            processor.process(item);
        }
    }
}
