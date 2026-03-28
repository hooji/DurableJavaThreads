package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

import java.util.*;

/**
 * E2E program: freeze and restore a thread that holds LinkedList, TreeSet,
 * and ArrayDeque in local variables. After restore, prints the collection
 * contents to verify they survived the round-trip.
 *
 * <p>Usage: CollectionTypesFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class CollectionTypesFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: CollectionTypesFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "collection-types-worker");
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
        // LinkedList
        LinkedList<String> linkedList = new LinkedList<String>();
        linkedList.add("alpha");
        linkedList.add("beta");
        linkedList.add("gamma");

        // TreeSet (natural ordering)
        TreeSet<String> treeSet = new TreeSet<String>();
        treeSet.add("cherry");
        treeSet.add("apple");
        treeSet.add("banana");

        // ArrayDeque
        ArrayDeque<String> deque = new ArrayDeque<String>();
        deque.addLast("first");
        deque.addLast("second");
        deque.addLast("third");

        System.out.println("Before freeze:");
        System.out.println("LinkedList=" + linkedList);
        System.out.println("TreeSet=" + treeSet);
        System.out.println("ArrayDeque=" + deque);
        System.out.flush();

        Durable.freeze(snapshotFile);

        // After restore
        System.out.println("After restore:");
        System.out.println("LinkedList=" + linkedList);
        System.out.println("LinkedList.size=" + linkedList.size());
        System.out.println("TreeSet=" + treeSet);
        System.out.println("TreeSet.size=" + treeSet.size());
        System.out.println("ArrayDeque=" + deque);
        System.out.println("ArrayDeque.size=" + deque.size());
        System.out.flush();
    }
}
