package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * E2E program: freeze with immutable JDK types as local variables.
 * Verifies that BigDecimal, BigInteger, UUID, java.time.*, and URI
 * all survive the freeze/restore cycle with correct values.
 *
 * <p>The frozen thread holds locals of each immutable type across two
 * stack frames so we verify both direct locals and parameter passing.</p>
 *
 * <p>Usage: ImmutableTypesFreezeProgram &lt;snapshotFile&gt;</p>
 */
public class ImmutableTypesFreezeProgram {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ImmutableTypesFreezeProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork(snapshotFile);
            }
        }, "immutable-types-worker");
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
        // BigDecimal and BigInteger
        BigDecimal price = new BigDecimal("199.99");
        BigDecimal tax = new BigDecimal("0.0825");
        BigInteger bigNum = new BigInteger("123456789012345678901234567890");

        // UUID
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // java.time types
        LocalDate date = LocalDate.of(2025, 6, 15);
        LocalTime time = LocalTime.of(14, 30, 45, 123456789);
        LocalDateTime dateTime = LocalDateTime.of(date, time);
        Instant instant = Instant.ofEpochSecond(1718450000L, 500000000);
        Duration duration = Duration.ofHours(2).plusMinutes(30).plusSeconds(15);
        ZonedDateTime zonedDt = ZonedDateTime.of(dateTime, ZoneId.of("America/New_York"));
        Period period = Period.of(1, 6, 15);

        // URI
        URI uri = URI.create("https://example.com/api/v1/resource?id=42");

        // Put some immutables inside a HashMap to test collection interop
        Map<String, BigDecimal> priceMap = new HashMap<String, BigDecimal>();
        priceMap.put("item1", new BigDecimal("29.99"));
        priceMap.put("item2", new BigDecimal("49.50"));

        List<UUID> uuidList = new ArrayList<UUID>();
        uuidList.add(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        uuidList.add(UUID.fromString("22222222-2222-2222-2222-222222222222"));

        System.out.println("BEFORE_FREEZE");
        System.out.flush();

        freezePoint(snapshotFile, price, tax, bigNum, id, date, time, dateTime,
                instant, duration, zonedDt, period, uri, priceMap, uuidList);

        // Only in RESTORED thread — verify all values
        System.out.println("PRICE=" + price);
        System.out.println("TAX=" + tax);
        System.out.println("BIGNUM=" + bigNum);
        System.out.println("UUID=" + id);
        System.out.println("DATE=" + date);
        System.out.println("TIME=" + time);
        System.out.println("DATETIME=" + dateTime);
        System.out.println("INSTANT=" + instant);
        System.out.println("DURATION=" + duration);
        System.out.println("ZONED=" + zonedDt);
        System.out.println("PERIOD=" + period);
        System.out.println("URI=" + uri);

        // Verify computed values still work
        BigDecimal total = price.multiply(tax.add(BigDecimal.ONE));
        System.out.println("TOTAL=" + total);

        // Verify collections with immutable values
        System.out.println("MAP_ITEM1=" + priceMap.get("item1"));
        System.out.println("MAP_ITEM2=" + priceMap.get("item2"));
        System.out.println("UUIDS=" + uuidList.size()
                + "," + uuidList.get(0) + "," + uuidList.get(1));

        System.out.flush();
    }

    public static void freezePoint(String snapshotFile,
            BigDecimal price, BigDecimal tax, BigInteger bigNum, UUID id,
            LocalDate date, LocalTime time, LocalDateTime dateTime,
            Instant instant, Duration duration, ZonedDateTime zonedDt,
            Period period, URI uri,
            Map<String, BigDecimal> priceMap, List<UUID> uuidList) {

        // Additional locals in this frame to verify parameter passing
        int marker = 42;

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
        System.out.println("MARKER=" + marker);
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
