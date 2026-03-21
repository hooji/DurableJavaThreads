package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

import java.util.HashMap;
import java.util.Map;

/**
 * E2E program: freeze with auto-named "this", restore with replacement.
 *
 * <p>The worker thread directly runs doWork() as an instance method.
 * The "this" object is auto-named during freeze. On restore, the caller
 * provides a replacement "this" object.</p>
 *
 * <p>Usage: NamedHeapObjectsProgram &lt;snapshotFile&gt;</p>
 */
public class NamedHeapObjectsProgram implements Runnable {

    String label;
    int counter;
    String snapshotFile;

    public NamedHeapObjectsProgram() {}

    public NamedHeapObjectsProgram(String label, int counter) {
        this.label = label;
        this.counter = counter;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: NamedHeapObjectsProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];

        // Phase 1: Create original object, freeze inside its run() method
        final NamedHeapObjectsProgram original = new NamedHeapObjectsProgram("original", 42);
        original.snapshotFile = snapshotFile;

        Thread worker = new Thread(original, "named-heap-worker");
        worker.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
                System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace(System.err);
            }
        });
        worker.start();
        worker.join(30_000);

        System.out.println("FREEZE_DONE");
        System.out.flush();

        // Phase 2: Restore with a replacement "this" object
        NamedHeapObjectsProgram replacement = new NamedHeapObjectsProgram("replaced", 99);
        Map<String, Object> replacements = new HashMap<String, Object>();
        replacements.put("this", replacement);

        try {
            Durable.restore(snapshotFile, replacements, true, true);
            System.out.println("RESTORE_COMPLETE");
        } catch (Exception e) {
            System.out.println("RESTORE_FAILED=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }
        System.out.flush();
    }

    @Override
    public void run() {
        System.out.println("BEFORE label=" + label + " counter=" + counter);
        System.out.flush();

        Durable.freeze(snapshotFile);

        // Only runs in restored thread
        System.out.println("AFTER label=" + label + " counter=" + counter);
        System.out.flush();
    }
}
