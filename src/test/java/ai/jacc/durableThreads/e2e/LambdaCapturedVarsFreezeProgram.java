package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

/**
 * E2E program: freeze inside a lambda that captures variables from
 * the enclosing scope.
 *
 * <p>The lambda captures {@code snapshotFile} (String) and {@code counter}
 * (int array, used for mutability). The freeze happens inside the lambda
 * body. After restore, the captured variables must retain their values.</p>
 *
 * <p>Expected output after lambda support is implemented:</p>
 * <pre>
 * Before freeze: counter=42
 * About to freeze inside lambda!
 * Restored inside lambda! counter=42
 * After lambda: counter=42
 * RESTORE_COMPLETE
 * </pre>
 *
 * <p>Usage: LambdaCapturedVarsFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class LambdaCapturedVarsFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: LambdaCapturedVarsFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];

        // Use anonymous Runnable for the thread itself (to isolate the test
        // to just the inner lambda, not the thread dispatch)
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "lambda-captured-worker");
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
        final int[] counter = {42};  // mutable via array — captured by lambda
        final String label = "test-label";

        System.out.println("Before freeze: counter=" + counter[0]);
        System.out.flush();

        // Lambda that captures snapshotFile, counter, and label
        Runnable freezeAction = () -> {
            System.out.println("About to freeze inside lambda!");
            System.out.flush();
            Durable.freeze(snapshotFile);
            // After restore, captured variables should be intact
            System.out.println("Restored inside lambda! counter=" + counter[0]);
            System.out.flush();
        };

        freezeAction.run();

        System.out.println("After lambda: counter=" + counter[0]);
        System.out.flush();
    }
}
