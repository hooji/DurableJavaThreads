package com.u1.durableThreads.e2e;

import com.u1.durableThreads.Durable;
import com.u1.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: freeze inside a try block.
 * Verifies that exception handling frames survive freeze/restore and
 * the finally block executes correctly after restore.
 *
 * <p>Usage: TryCatchFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class TryCatchFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: TryCatchFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "trycatch-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        String result = freezeInTry(snapshotFile);
        // Only in RESTORED thread
        System.out.println("TRYCATCH_RESULT=" + result);
        System.out.flush();
    }

    public static String freezeInTry(String snapshotFile) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("before-");
            System.out.println("TRY_BEFORE");
            System.out.flush();

            Durable.freeze(snapshot -> {
                try {
                    byte[] bytes = serialize(snapshot);
                    Files.write(Path.of(snapshotFile), bytes);
                    System.out.println("FREEZE_COMPLETE");
                    System.out.flush();
                } catch (Exception e) {
                    System.err.println("FREEZE_ERROR=" + e.getMessage());
                    e.printStackTrace(System.err);
                }
            });

            // Only in RESTORED thread
            sb.append("after-");
            System.out.println("TRY_AFTER");
            System.out.flush();
            sb.append("end");
        } catch (RuntimeException e) {
            sb.append("caught-").append(e.getMessage());
        } finally {
            sb.append("-finally");
            System.out.println("FINALLY_EXECUTED");
            System.out.flush();
        }
        return sb.toString();
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
