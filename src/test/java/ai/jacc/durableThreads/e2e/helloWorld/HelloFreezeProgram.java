package ai.jacc.durableThreads.e2e.helloWorld;

import ai.jacc.durableThreads.Durable;

/**
 * Quick Start example: loop 0..10, freeze at i == 5.
 *
 * <p>Usage: HelloFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class HelloFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: HelloFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "hello-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        for (int i = 0; i <= 10; i++) {
            System.out.println("i=" + i);
            System.out.flush();

            if (i == 5) {
                Durable.freeze(snapshotFile);
                // Only runs after restore
                System.out.println("RESUMED");
                System.out.flush();
            }
        }
        System.out.println("DONE");
        System.out.flush();
    }
}
