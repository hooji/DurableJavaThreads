package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

/**
 * E2E program: freeze inside a SECOND for-loop that reuses the same loop
 * variable name ('i') and slot as a first for-loop.
 *
 * <p>This tests the critical scenario where the compiler reuses the slot
 * for 'int i' across two sequential for-loops. If emitLocalVariables()
 * deduplicates by (name, desc, slot), only the first loop's scope entry
 * survives, making 'i' invisible to JDI at the freeze point in the second
 * loop — causing "not in scope at current BCP" restore failures.</p>
 *
 * <p>Expected output:</p>
 * <pre>
 * FIRST_LOOP_SUM=10
 * SECOND_LOOP i=2
 * About to freeze!
 * Thread frozen...
 * Resumed! i=2
 * SECOND_LOOP i=3
 * SECOND_LOOP i=4
 * SECOND_LOOP_RESULT=14
 * RESTORE_COMPLETE
 * </pre>
 *
 * <p>Usage: DuplicateLoopVarFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class DuplicateLoopVarFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: DuplicateLoopVarFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "duploop-worker");
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

        // Restore in the same JVM
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
        int result = computeWithDuplicateLoopVars(snapshotFile);
        System.out.println("SECOND_LOOP_RESULT=" + result);
        System.out.flush();
    }

    /**
     * Two sequential for-loops, both declaring 'int i'. The compiler reuses
     * the slot for 'i'. Freeze happens in the SECOND loop at i==2.
     */
    public static int computeWithDuplicateLoopVars(String snapshotFile) {
        // First loop: sum 0..4 (uses slot N for 'i')
        int sum = 0;
        for (int i = 0; i < 5; i++) {
            sum += i;
        }
        System.out.println("FIRST_LOOP_SUM=" + sum);
        System.out.flush();

        // Second loop: same slot N reused for 'i'
        int result = sum;
        for (int i = 0; i < 5; i++) {
            System.out.println("SECOND_LOOP i=" + i);
            System.out.flush();

            if (i == 2) {
                System.out.println("About to freeze!");
                System.out.flush();

                Durable.freeze(snapshotFile);

                // Only in restored thread
                System.out.println("Resumed! i=" + i);
                System.out.flush();
            }
            result += i;
        }
        // result = 10 + 0+1+2+3+4 = 20
        return result;
    }
}
