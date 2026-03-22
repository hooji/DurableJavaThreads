package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * E2E program: freeze with enum local variables (both user-defined and JDK).
 * Verifies that enum identity (==) is preserved across freeze/restore,
 * including enums used in switch statements, maps, and sets.
 *
 * <p>Usage: EnumFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class EnumFreezeProgram {

    /** User-defined enum with custom fields. */
    public enum Color {
        RED("warm"), GREEN("cool"), BLUE("cool");

        private final String temperature;
        Color(String temperature) { this.temperature = temperature; }
        public String temperature() { return temperature; }
    }

    /** User-defined enum without extra fields. */
    public enum Direction { NORTH, SOUTH, EAST, WEST }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: EnumFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "enum-worker");
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
        // User-defined enums
        Color color = Color.RED;
        Direction dir = Direction.EAST;

        // JDK enum
        TimeUnit unit = TimeUnit.SECONDS;

        // Enum in a Map
        Map<Color, Integer> colorCounts = new HashMap<Color, Integer>();
        colorCounts.put(Color.RED, 5);
        colorCounts.put(Color.GREEN, 3);
        colorCounts.put(Color.BLUE, 7);

        // Enum in a Set
        Set<Direction> visited = new HashSet<Direction>();
        visited.add(Direction.NORTH);
        visited.add(Direction.EAST);

        System.out.println("BEFORE_FREEZE");
        System.out.flush();

        freezePoint(snapshotFile, color, dir, unit, colorCounts, visited);

        // Only in RESTORED thread — verify enum identity and values
        // == identity checks
        System.out.println("COLOR_IS_RED=" + (color == Color.RED));
        System.out.println("DIR_IS_EAST=" + (dir == Direction.EAST));
        System.out.println("UNIT_IS_SECONDS=" + (unit == TimeUnit.SECONDS));

        // Values
        System.out.println("COLOR=" + color);
        System.out.println("COLOR_TEMP=" + color.temperature());
        System.out.println("COLOR_ORDINAL=" + color.ordinal());
        System.out.println("DIR=" + dir);
        System.out.println("UNIT=" + unit);

        // Switch statement (relies on == identity)
        String switchResult;
        switch (color) {
            case RED: switchResult = "matched-red"; break;
            case GREEN: switchResult = "matched-green"; break;
            case BLUE: switchResult = "matched-blue"; break;
            default: switchResult = "no-match";
        }
        System.out.println("SWITCH=" + switchResult);

        // Map with enum keys
        System.out.println("MAP_RED=" + colorCounts.get(Color.RED));
        System.out.println("MAP_GREEN=" + colorCounts.get(Color.GREEN));
        System.out.println("MAP_BLUE=" + colorCounts.get(Color.BLUE));

        // Set with enum values
        System.out.println("SET_CONTAINS_NORTH=" + visited.contains(Direction.NORTH));
        System.out.println("SET_CONTAINS_EAST=" + visited.contains(Direction.EAST));
        System.out.println("SET_CONTAINS_SOUTH=" + visited.contains(Direction.SOUTH));

        System.out.flush();
    }

    public static void freezePoint(String snapshotFile,
            Color color, Direction dir, TimeUnit unit,
            Map<Color, Integer> colorCounts, Set<Direction> visited) {

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
