package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

/**
 * E2E program: freeze inside a method reference used as a functional interface.
 *
 * <p>Uses {@code this::processItem} as an {@code ItemProcessor} method reference.
 * Method references create $$Lambda classes just like lambdas. The freeze
 * happens inside the referenced instance method.</p>
 *
 * <p>Expected output after lambda support is implemented:</p>
 * <pre>
 * Processing: first (total=1)
 * Processing: second (total=2)
 * Processing: third (total=3)
 * Freezing at item: third
 * Restored at item: third (total=3)
 * Processing: fourth (total=4)
 * TOTAL=4
 * RESTORE_COMPLETE
 * </pre>
 *
 * <p>Usage: MethodRefFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class MethodRefFreezeProgram {

    public interface ItemProcessor {
        void process(String item);
    }

    private String snapshotFile;
    private int total = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: MethodRefFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];
        final MethodRefFreezeProgram instance = new MethodRefFreezeProgram();
        instance.snapshotFile = snapshotFile;

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                instance.doWork();
            }
        }, "methodref-worker");
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

    void doWork() {
        String[] items = {"first", "second", "third", "fourth"};

        // Method reference: this::processItem creates a $$Lambda class
        processAll(items, this::processItem);

        System.out.println("TOTAL=" + total);
        System.out.flush();
    }

    /**
     * Instance method used as a method reference. Freeze happens here.
     */
    void processItem(String item) {
        total++;
        System.out.println("Processing: " + item + " (total=" + total + ")");
        System.out.flush();

        if ("third".equals(item)) {
            System.out.println("Freezing at item: " + item);
            System.out.flush();
            Durable.freeze(snapshotFile);
            System.out.println("Restored at item: " + item + " (total=" + total + ")");
            System.out.flush();
        }
    }

    public static void processAll(String[] items, ItemProcessor processor) {
        for (String item : items) {
            processor.process(item);
        }
    }
}
