package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * E2E program: freeze with non-trivial object references as local variables.
 * Verifies that heap objects (not just primitives and strings) survive
 * the freeze/restore cycle.
 *
 * <p>The frozen thread has locals referencing:</p>
 * <ul>
 *   <li>A user-defined POJO ({@code Person}) — tests non-Serializable field extraction</li>
 *   <li>An {@code ArrayList} with elements — tests collection state</li>
 *   <li>An {@code int[]} array — tests array capture/restore</li>
 * </ul>
 *
 * <p>Usage: HeapObjectFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class HeapObjectFreezeProgram {

    /** Simple non-Serializable POJO. */
    public static class Person {
        public String name;
        public int age;
        public Person friend;

        public Person() {}

        public Person(String name, int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public String toString() {
            return name + "(" + age + ")";
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: HeapObjectFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];

        // Use anonymous Runnable — lambda frames cause LambdaFrameException
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "heap-object-worker");
        worker.setUncaughtExceptionHandler((t, e) -> {
            if (e.getClass().getSimpleName().equals("ThreadFrozenError")) return;
            System.err.println("UNCAUGHT=" + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });
        worker.start();
        worker.join(30_000);
    }

    static void doWork(String snapshotFile) {
        // Create object graph with locals
        Person alice = new Person("Alice", 30);
        Person bob = new Person("Bob", 25);
        alice.friend = bob;

        int[] scores = {10, 20, 30, 40, 50};

        List<String> tags = new ArrayList<>();
        tags.add("durable");
        tags.add("threads");

        int primitiveLocal = 99;

        System.out.println("BEFORE_FREEZE alice=" + alice + " bob=" + bob);
        System.out.println("BEFORE_FREEZE scores.length=" + scores.length);
        System.out.println("BEFORE_FREEZE tags=" + tags);
        System.out.println("BEFORE_FREEZE primitiveLocal=" + primitiveLocal);
        System.out.flush();

        Durable.freeze(snapshot -> {
            try {
                byte[] bytes = serialize(snapshot);
                Files.write(Paths.get(snapshotFile), bytes);
                System.out.println("FREEZE_COMPLETE");
                System.out.println("HEAP_SIZE=" + snapshot.heap().size());
                System.out.flush();
            } catch (Exception e) {
                System.err.println("FREEZE_ERROR=" + e.getMessage());
                e.printStackTrace(System.err);
            }
        });

        // Only in RESTORED thread — verify object state survived
        System.out.println("AFTER_FREEZE primitiveLocal=" + primitiveLocal);

        if (alice != null) {
            System.out.println("AFTER_FREEZE alice.name=" + alice.name);
            System.out.println("AFTER_FREEZE alice.age=" + alice.age);
            if (alice.friend != null) {
                System.out.println("AFTER_FREEZE alice.friend.name=" + alice.friend.name);
            } else {
                System.out.println("AFTER_FREEZE alice.friend=null");
            }
        } else {
            System.out.println("AFTER_FREEZE alice=null");
        }

        if (scores != null) {
            System.out.println("AFTER_FREEZE scores.length=" + scores.length);
            if (scores.length >= 5) {
                System.out.println("AFTER_FREEZE scores[2]=" + scores[2]);
            }
        } else {
            System.out.println("AFTER_FREEZE scores=null");
        }

        if (tags != null) {
            System.out.println("AFTER_FREEZE tags.size=" + tags.size());
        } else {
            System.out.println("AFTER_FREEZE tags=null");
        }

        System.out.flush();
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }
}
