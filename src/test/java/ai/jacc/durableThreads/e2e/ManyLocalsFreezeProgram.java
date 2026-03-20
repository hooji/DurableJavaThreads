package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: freeze with many local variables of different types.
 * Verifies that the __skip local variable injected by PrologueInjector
 * doesn't collide with original locals, and that all typed locals
 * survive freeze/restore via JDI.
 *
 * <p>Tests: int, long, double, float, boolean, String locals.</p>
 *
 * <p>Usage: ManyLocalsFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class ManyLocalsFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ManyLocalsFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "many-locals-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        long result = manyLocals(10, snapshotFile);
        // Only in RESTORED thread
        System.out.println("MANY_LOCALS_RESULT=" + result);
        System.out.flush();
    }

    public static long manyLocals(int seed, String snapshotFile) {
        int a = seed;
        int b = seed + 1;
        int c = seed + 2;
        long d = seed * 3L;
        double e = seed * 1.5;
        float f = seed * 0.5f;
        boolean g = seed > 0;
        int h = a + b + c;
        long j = d + (long) e;

        System.out.println("BEFORE_FREEZE a=" + a + " b=" + b + " c=" + c
                + " d=" + d + " e=" + e + " f=" + f + " g=" + g + " h=" + h + " j=" + j);
        System.out.flush();

        Durable.freeze(snapshot -> {
            try {
                byte[] bytes = serialize(snapshot);
                Files.write(Path.of(snapshotFile), bytes);
                System.out.println("FREEZE_COMPLETE");
                System.out.flush();
            } catch (Exception ex) {
                System.err.println("FREEZE_ERROR=" + ex.getMessage());
                ex.printStackTrace(System.err);
            }
        });

        // Only in RESTORED thread — use all locals to verify they survived
        long result = a + b + c + d + (long) e + (long) f + (g ? 1 : 0) + h + j;
        System.out.println("AFTER_FREEZE a=" + a + " b=" + b + " c=" + c
                + " d=" + d + " g=" + g + " h=" + h + " j=" + j);
        System.out.flush();
        return result;
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
