package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * E2E program: freeze deep in a call chain with complex state at every level.
 *
 * <p>Each stack frame holds a mix of primitive locals, strings, arrays, lists,
 * and a shared mutable accumulator object. After restore, every frame verifies
 * its own state is intact by printing checksums. This catches any corruption
 * of locals, heap references, or operand-stack values across the restore.</p>
 *
 * <p>Call chain (8 levels): main → doWork → level1 → level2 → ... → level7 (freeze)</p>
 *
 * <p>Usage: DeepStateFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class DeepStateFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: DeepStateFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "deep-state-worker");
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
    }

    static void doWork(String snapshotFile) {
        // Accumulator shared across all levels via parameter passing
        List<String> accumulator = new ArrayList<String>();
        accumulator.add("root");

        int result = level1(1, "hello", snapshotFile, accumulator);

        // Only in RESTORED thread
        System.out.println("FINAL_RESULT=" + result);
        System.out.println("ACCUMULATOR=" + accumulator);
        System.out.flush();
    }

    public static int level1(int depth, String tag, String snapshotFile, List<String> acc) {
        int localInt = depth * 10;          // 10
        double localDouble = depth * 3.14;  // 3.14
        String localStr = tag + "-L1";      // "hello-L1"
        int[] localArr = new int[]{depth, depth + 1, depth + 2}; // [1,2,3]
        acc.add("L1");

        int child = level2(depth + 1, localStr, snapshotFile, acc);

        // Verify state after restore
        System.out.println("L1_INT=" + localInt);
        System.out.println("L1_DOUBLE=" + localDouble);
        System.out.println("L1_STR=" + localStr);
        System.out.println("L1_ARR=" + Arrays.toString(localArr));
        System.out.flush();
        return localInt + child;
    }

    public static int level2(int depth, String tag, String snapshotFile, List<String> acc) {
        long localLong = depth * 100_000L;  // 200000
        float localFloat = depth * 2.5f;    // 5.0
        boolean localBool = (depth % 2 == 0); // true
        String localStr = tag + "-L2";
        acc.add("L2");

        int child = level3(depth + 1, localStr, snapshotFile, acc);

        System.out.println("L2_LONG=" + localLong);
        System.out.println("L2_FLOAT=" + localFloat);
        System.out.println("L2_BOOL=" + localBool);
        System.out.println("L2_STR=" + localStr);
        System.out.flush();
        return (int) (localLong % 1000) + child;
    }

    public static int level3(int depth, String tag, String snapshotFile, List<String> acc) {
        int a = depth * 7;      // 21
        int b = depth + 100;    // 103
        int c = a + b;          // 124
        String localStr = tag + "-L3";
        Map<String, Integer> localMap = new HashMap<String, Integer>();
        localMap.put("a", a);
        localMap.put("b", b);
        localMap.put("c", c);
        acc.add("L3");

        int child = level4(depth + 1, localStr, snapshotFile, acc);

        System.out.println("L3_ABC=" + a + "," + b + "," + c);
        System.out.println("L3_MAP=" + localMap.get("a") + "," + localMap.get("b") + "," + localMap.get("c"));
        System.out.println("L3_STR=" + localStr);
        System.out.flush();
        return c + child;
    }

    public static int level4(int depth, String tag, String snapshotFile, List<String> acc) {
        char localChar = (char) ('A' + depth); // 'E'
        short localShort = (short) (depth * 11); // 44
        byte localByte = (byte) (depth & 0x7F); // 4
        String localStr = tag + "-L4";
        acc.add("L4");

        int child = level5(depth + 1, localStr, snapshotFile, acc);

        System.out.println("L4_CHAR=" + localChar);
        System.out.println("L4_SHORT=" + localShort);
        System.out.println("L4_BYTE=" + localByte);
        System.out.println("L4_STR=" + localStr);
        System.out.flush();
        return localChar + child;
    }

    public static int level5(int depth, String tag, String snapshotFile, List<String> acc) {
        int[] primes = new int[]{2, 3, 5, 7, 11, 13};
        String localStr = tag + "-L5";
        int sum = 0;
        for (int p : primes) {
            sum += p;
        }
        acc.add("L5");

        int child = level6(depth + 1, localStr, snapshotFile, acc);

        System.out.println("L5_PRIMES=" + Arrays.toString(primes));
        System.out.println("L5_SUM=" + sum);
        System.out.println("L5_STR=" + localStr);
        System.out.flush();
        return sum + child;
    }

    public static int level6(int depth, String tag, String snapshotFile, List<String> acc) {
        double pi = 3.14159265358979;
        double e = 2.71828182845905;
        double product = pi * e;
        String localStr = tag + "-L6";
        acc.add("L6");

        int child = level7(depth + 1, localStr, snapshotFile, acc);

        System.out.println("L6_PI=" + pi);
        System.out.println("L6_E=" + e);
        System.out.println("L6_PRODUCT=" + product);
        System.out.println("L6_STR=" + localStr);
        System.out.flush();
        return (int) product + child;
    }

    public static int level7(int depth, String tag, String snapshotFile, List<String> acc) {
        int beforeFreeze = depth * 1000; // 8000
        String localStr = tag + "-L7";
        acc.add("L7");

        System.out.println("BEFORE_FREEZE depth=" + depth + " acc=" + acc);
        System.out.flush();

        Durable.freeze(new java.util.function.Consumer<ThreadSnapshot>() {
            @Override
            public void accept(ThreadSnapshot snapshot) {
                try {
                    byte[] bytes = serialize(snapshot);
                    Files.write(Paths.get(snapshotFile), bytes);
                    System.out.println("FREEZE_COMPLETE");
                    System.out.println("FRAME_COUNT=" + snapshot.frameCount());
                    System.out.flush();
                } catch (Exception ex) {
                    System.err.println("FREEZE_ERROR=" + ex.getMessage());
                    ex.printStackTrace(System.err);
                }
            }
        });

        // Only in RESTORED thread
        int afterFreeze = beforeFreeze + 42;
        System.out.println("L7_BEFORE=" + beforeFreeze);
        System.out.println("L7_AFTER=" + afterFreeze);
        System.out.println("L7_STR=" + localStr);
        System.out.flush();
        return afterFreeze;
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
