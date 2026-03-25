package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

import java.util.HashMap;
import java.util.Map;

/**
 * E2E program: multi-cycle freeze/restore with named objects on an instance
 * method. Verifies that field mutations (++nFreezes, ++nResumptions) in the
 * method body are not duplicated during the skip pass.
 *
 * <p>The worker thread counts from 1 to 20, freezing every 5 iterations.
 * After restore, the caller provides itself as the replacement "this".
 * At the end, nFreezes and nResumptions must be equal.</p>
 */
public class MultiCycleNamedObjectProgram implements Runnable {

    boolean running = false;
    int nFreezes = 0;
    int nResumptions = 0;
    String snapshotFile;

    public MultiCycleNamedObjectProgram() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: MultiCycleNamedObjectProgram <snapshotFile>");
            System.exit(1);
        }

        MultiCycleNamedObjectProgram self = new MultiCycleNamedObjectProgram();
        self.snapshotFile = args[0];

        Thread worker = new Thread(self, "multi-cycle-named");
        worker.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
                System.err.println("UNCAUGHT=" + e.getClass().getName()
                        + ": " + e.getMessage());
                e.printStackTrace(System.err);
            }
        });
        worker.start();
        worker.join(30_000);

        Map<String, Object> replacements = new HashMap<String, Object>();
        replacements.put("this", self);

        while (self.running) {
            System.out.println("RESTORE_CYCLE nFreezes=" + self.nFreezes
                    + " nResumptions=" + self.nResumptions);
            System.out.flush();
            try {
                Durable.restore(self.snapshotFile, replacements);
            } catch (Exception e) {
                System.out.println("RESTORE_FAILED=" + e.getClass().getName()
                        + ": " + e.getMessage());
                e.printStackTrace(System.err);
                System.exit(1);
            }
        }

        System.out.println("FINAL nFreezes=" + self.nFreezes
                + " nResumptions=" + self.nResumptions);
        if (self.nFreezes == self.nResumptions) {
            System.out.println("COUNTS_MATCH");
        } else {
            System.out.println("COUNTS_MISMATCH");
        }
        System.out.flush();
    }

    @Override
    public void run() {
        running = true;
        for (int i = 1; i <= 20; i++) {
            System.out.println("i=" + i);
            if (i % 5 == 0) {
                ++nFreezes;
                Durable.freeze(snapshotFile);
                ++nResumptions;
            }
        }
        System.out.println("Done!");
        running = false;
    }
}
