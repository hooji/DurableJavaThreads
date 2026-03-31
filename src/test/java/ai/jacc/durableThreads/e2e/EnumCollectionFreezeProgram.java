package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

import java.util.*;

/**
 * E2E program: freeze and restore a thread that holds EnumSet and EnumMap
 * in local variables. After restore, prints the collection contents to
 * verify they survived the round-trip.
 *
 * <p>Usage: EnumCollectionFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class EnumCollectionFreezeProgram {

    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: EnumCollectionFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        final String snapshotFile = args[0];

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "enum-collection-worker");
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
        // EnumSet with two elements
        EnumSet<Season> warmSeasons = EnumSet.of(Season.SPRING, Season.SUMMER);

        // EnumMap with entries
        EnumMap<Season, String> seasonNames = new EnumMap<Season, String>(Season.class);
        seasonNames.put(Season.SPRING, "Spring Time");
        seasonNames.put(Season.SUMMER, "Summer Days");
        seasonNames.put(Season.WINTER, "Winter Nights");

        System.out.println("Before freeze:");
        System.out.println("EnumSet=" + warmSeasons);
        System.out.println("EnumMap=" + seasonNames);
        System.out.flush();

        Durable.freeze(snapshotFile);

        // After restore
        System.out.println("After restore:");
        System.out.println("EnumSet=" + warmSeasons);
        System.out.println("EnumSet.size=" + warmSeasons.size());
        System.out.println("EnumSet.contains.SPRING=" + warmSeasons.contains(Season.SPRING));
        System.out.println("EnumSet.contains.SUMMER=" + warmSeasons.contains(Season.SUMMER));
        System.out.println("EnumSet.contains.AUTUMN=" + warmSeasons.contains(Season.AUTUMN));
        System.out.println("EnumMap=" + seasonNames);
        System.out.println("EnumMap.size=" + seasonNames.size());
        System.out.println("EnumMap.SPRING=" + seasonNames.get(Season.SPRING));
        System.out.println("EnumMap.SUMMER=" + seasonNames.get(Season.SUMMER));
        System.out.println("EnumMap.WINTER=" + seasonNames.get(Season.WINTER));
        System.out.println("EnumMap.AUTUMN=" + seasonNames.get(Season.AUTUMN));
        System.out.flush();
    }
}
