package ai.jacc.durableThreads.e2e.helloWorld;

import ai.jacc.durableThreads.Durable;

import java.nio.file.Path;

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

        Thread restored = Durable.restore(args[0]);

        Throwable[] error = new Throwable[1];
        restored.setUncaughtExceptionHandler((t, e) -> {
            error[0] = e;
            System.err.println("RESTORED_THREAD_ERROR=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });

        restored.start();
        restored.join(30_000);

        if (error[0] != null) {
            System.out.println("RESTORE_FAILED=" + error[0].getMessage());
            System.out.flush();
            System.exit(1);
        }

        System.out.println("RESTORE_COMPLETE");
        System.out.flush();
    }
}
