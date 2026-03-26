package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

/**
 * E2E program: freeze inside a lambda Runnable.
 *
 * <p>The simplest lambda case: a {@code Runnable} lambda that calls freeze().
 * The call stack at freeze time includes a $$Lambda frame between the
 * user code and the synthetic method.</p>
 *
 * <p>Expected output after lambda support is implemented:</p>
 * <pre>
 * i=0
 * i=1
 * i=2
 * About to freeze at i=2
 * Restored! i=2
 * i=3
 * i=4
 * FINAL_SUM=10
 * RESTORE_COMPLETE
 * </pre>
 *
 * <p>Usage: LambdaRunnableFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class LambdaRunnableFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: LambdaRunnableFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];

        // THIS IS THE KEY: using a lambda instead of anonymous Runnable.
        // The lambda creates a $$Lambda frame on the call stack.
        Thread worker = new Thread(() -> doWork(snapshotFile), "lambda-runnable-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
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
        int sum = 0;
        for (int i = 0; i < 5; i++) {
            System.out.println("i=" + i);
            if (i == 2) {
                System.out.println("About to freeze at i=" + i);
                System.out.flush();
                Durable.freeze(snapshotFile);
                System.out.println("Restored! i=" + i);
            }
            sum += i;
        }
        System.out.println("FINAL_SUM=" + sum);
        System.out.flush();
    }
}
