package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

import java.util.HashMap;
import java.util.Map;

/**
 * E2E program: exercises the wrapper pattern where freeze() is called from a
 * method on the named object, but the bottom frame is a wrapper class.
 *
 * <p>Call stack at freeze:
 * <pre>
 *   Durable.freeze()          ← infrastructure
 *   target.doWork(snapshotFile) ← topmost user frame, "this" = target (auto-named)
 *   Worker.run()              ← bottom frame, receiver = Worker
 * </pre>
 *
 * <p>On restore, Worker is recreated via Objenesis, its {@code target} field
 * populated from the heap (resolving to the replacement). The prologue in
 * Worker.run() replays {@code target.doWork()}, which should use the
 * replacement as {@code this}.</p>
 *
 * <p>Usage: NamedHeapWrapperProgram &lt;snapshotFile&gt;</p>
 */
public class NamedHeapWrapperProgram {

    String label;
    int counter;

    public NamedHeapWrapperProgram() {}

    public NamedHeapWrapperProgram(String label, int counter) {
        this.label = label;
        this.counter = counter;
    }

    /**
     * The method that calls freeze(). This is the topmost user frame, so
     * auto-naming picks "this" = this NamedHeapWrapperProgram instance.
     */
    public void doWork(String snapshotFile) {
        System.out.println("BEFORE label=" + label + " counter=" + counter);
        System.out.flush();

        Durable.freeze(snapshotFile);

        // Only runs in restored thread
        System.out.println("AFTER label=" + label + " counter=" + counter);
        System.out.flush();
    }

    /**
     * Wrapper class that holds a final reference to the named object.
     * This is the bottom frame — its run() calls target.doWork().
     */
    static class Worker implements Runnable {
        final NamedHeapWrapperProgram target;
        final String snapshotFile;

        Worker(NamedHeapWrapperProgram target, String snapshotFile) {
            this.target = target;
            this.snapshotFile = snapshotFile;
        }

        @Override
        public void run() {
            target.doWork(snapshotFile);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: NamedHeapWrapperProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];

        // Phase 1: Create original object, freeze via Worker wrapper
        final NamedHeapWrapperProgram original =
                new NamedHeapWrapperProgram("original", 42);

        Thread worker = new Thread(
                new Worker(original, snapshotFile), "wrapper-worker");
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

        System.out.println("FREEZE_DONE");
        System.out.flush();

        // Phase 2: Restore with a replacement "this" object
        NamedHeapWrapperProgram replacement =
                new NamedHeapWrapperProgram("replaced", 99);
        Map<String, Object> replacements = new HashMap<String, Object>();
        replacements.put("this", replacement);

        try {
            Durable.restore(snapshotFile, replacements);
            System.out.println("RESTORE_COMPLETE");
        } catch (Exception e) {
            System.out.println("RESTORE_FAILED=" + e.getClass().getName()
                    + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }
        System.out.flush();
    }
}
