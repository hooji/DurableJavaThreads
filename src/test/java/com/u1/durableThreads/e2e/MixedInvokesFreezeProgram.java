package com.u1.durableThreads.e2e;

import com.u1.durableThreads.Durable;
import com.u1.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;
import java.util.List;

/**
 * E2E program: freeze in a method that uses all invoke types.
 *
 * <p>Exercises INVOKEVIRTUAL, INVOKESTATIC, INVOKEINTERFACE, and
 * INVOKEDYNAMIC (string concatenation) in a single method, with the
 * freeze point after all of them. This verifies that the invoke offset
 * mapping handles mixed invoke types correctly — especially since
 * INVOKEINTERFACE is 5 bytes (not 3) and INVOKEDYNAMIC resume stubs
 * don't re-invoke (contributing 0 scanner entries).</p>
 *
 * <p>Usage: MixedInvokesFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class MixedInvokesFreezeProgram {

    /** Interface for INVOKEINTERFACE testing */
    public interface Processor {
        int process(int value);
    }

    /** Concrete implementation */
    public static class Doubler implements Processor {
        @Override
        public int process(int value) {
            return value * 2;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: MixedInvokesFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "mixed-invokes-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        int result = mixedInvokes(10, snapshotFile);
        // Only in RESTORED thread
        System.out.println("MIXED_RESULT=" + result);
        System.out.flush();
    }

    /**
     * Uses all invoke types in sequence before freezing.
     */
    public static int mixedInvokes(int input, String snapshotFile) {
        // INVOKESTATIC
        int a = staticCompute(input);       // a = 15

        // INVOKEVIRTUAL — StringBuilder.append, toString
        StringBuilder sb = new StringBuilder();
        sb.append("val=");                  // INVOKEVIRTUAL
        sb.append(a);                       // INVOKEVIRTUAL

        // INVOKEINTERFACE
        Processor proc = new Doubler();
        int b = proc.process(a);            // b = 30 (INVOKEINTERFACE)

        // INVOKEDYNAMIC — string concatenation with +
        // The Java compiler uses makeConcatWithConstants for string +
        String label = "result-" + b + "-end";  // INVOKEDYNAMIC (makeConcatWithConstants)

        // More INVOKESTATIC
        int c = staticCompute(b);           // c = 35

        // INVOKEVIRTUAL — String.length()
        int d = label.length();             // INVOKEVIRTUAL

        System.out.println("BEFORE_FREEZE a=" + a + " b=" + b + " c=" + c + " d=" + d);
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

        // Only in RESTORED thread:
        // Verify all locals survived
        int total = a + b + c + d;
        System.out.println("AFTER_FREEZE a=" + a + " b=" + b + " c=" + c + " d=" + d);
        System.out.println("LABEL=" + label);
        System.out.println("SB=" + sb.toString());
        System.out.flush();
        return total;
    }

    public static int staticCompute(int x) {
        return x + 5;
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
