package ai.jacc.durableThreads.e2e.helloWorld;

import ai.jacc.durableThreads.Durable;

/**
 * Quick Start example: restore a frozen thread from a snapshot file.
 *
 * <p>Usage: HelloRestoreProgram &lt;snapshotFile&gt;</p>
 */
public class HelloRestoreProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: HelloRestoreProgram <snapshotFile>");
            System.exit(1);
        }

        try {
            Durable.restore(args[0]);
            System.out.println("RESTORE_COMPLETE");
        } catch (Exception e) {
            System.out.println("RESTORE_FAILED=" + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
        System.out.flush();
    }
}
