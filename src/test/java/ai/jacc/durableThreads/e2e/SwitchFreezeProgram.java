package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * E2E program: freeze inside a method that uses switch statements.
 *
 * <p>Switch instructions (tableswitch and lookupswitch) have alignment-dependent
 * padding in the bytecode. This test verifies that RawBytecodeScanner correctly
 * computes instruction sizes and BCPs when switch instructions are present,
 * which could throw off the invoke offset mapping if padding is miscalculated.</p>
 *
 * <p>Usage: SwitchFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class SwitchFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SwitchFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "switch-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        int result = computeWithSwitch(3, snapshotFile);
        // Only in RESTORED thread
        System.out.println("SWITCH_RESULT=" + result);
        System.out.flush();
    }

    /**
     * Method with both tableswitch (dense case values) and lookupswitch (sparse)
     * interleaved with method calls, then a freeze point after both switches.
     */
    public static int computeWithSwitch(int mode, String snapshotFile) {
        int accumulator = 0;

        // --- tableswitch (dense cases 0-4) ---
        // This creates a tableswitch instruction in bytecode
        switch (mode) {
            case 0: accumulator += computeStep(10); break;
            case 1: accumulator += computeStep(20); break;
            case 2: accumulator += computeStep(30); break;
            case 3: accumulator += computeStep(40); break;
            case 4: accumulator += computeStep(50); break;
            default: accumulator += computeStep(0); break;
        }

        // Some intermediate computation
        int intermediate = transform(accumulator);

        // --- lookupswitch (sparse case values) ---
        // Sparse values force the compiler to use lookupswitch
        switch (intermediate) {
            case 100: accumulator += computeStep(1); break;
            case 500: accumulator += computeStep(2); break;
            case 1000: accumulator += computeStep(3); break;
            case 9999: accumulator += computeStep(4); break;
            default: accumulator += computeStep(5); break;
        }

        // More invokes after both switches — these BCPs must be computed correctly
        int postSwitch = finalStep(accumulator);
        System.out.println("BEFORE_FREEZE acc=" + accumulator + " post=" + postSwitch);
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
        int afterFreeze = postSwitch + accumulator + 1000;
        System.out.println("AFTER_FREEZE=" + afterFreeze);
        System.out.flush();
        return afterFreeze;
    }

    public static int computeStep(int x) {
        return x * 2;
    }

    public static int transform(int x) {
        // Returns a value that will NOT match any lookupswitch case
        // (mode=3 → accumulator=80 → transform=160)
        return x * 2;
    }

    public static int finalStep(int x) {
        return x + 7;
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
