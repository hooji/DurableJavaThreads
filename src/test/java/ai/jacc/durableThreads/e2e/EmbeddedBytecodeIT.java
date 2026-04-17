package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.snapshot.SnapshotEnvironment;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * E2E verification that a freshly-frozen snapshot carries embedded class
 * file bytes inside its {@link SnapshotEnvironment}. This is the freeze
 * side of the tier-2 portable-snapshot feature.
 *
 * <p>The restore side (installing embedded classes on a JVM that lacks
 * them on its classpath) is exercised by every other E2E test
 * transparently: when the class is already on the classpath, the installer
 * is a no-op; when it isn't, the installer writes a temp jar and calls
 * {@code Instrumentation.appendToSystemClassLoaderSearch}.</p>
 */
@Tag("e2e")
class EmbeddedBytecodeIT {

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    @Test
    void freezeEmbedsUserClassBytesWhenEnabled() throws Exception {
        Path snapshotFile = Files.createTempFile("embedded-bytes-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.EmbeddedBytecodeFreezeProgram",
                    classpath, port,
                    new String[]{snapshotFile.toString()}, 60);

            assertTrue(result.stdout().contains("FREEZE_COMPLETE"),
                    "Expected FREEZE_COMPLETE in child stdout; got:\n" + result.stdout()
                    + "\nstderr:\n" + result.stderr());

            // Deserialize the snapshot produced by the child JVM
            ThreadSnapshot snapshot;
            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(snapshotFile))) {
                snapshot = (ThreadSnapshot) ois.readObject();
            }

            SnapshotEnvironment env = snapshot.environment();
            assertNotNull(env, "Snapshot must carry environment metadata");
            assertFalse(env.classEntries().isEmpty(),
                    "Snapshot environment must contain class entries");

            // Find the entry for a user class we know was captured. Person
            // is a nested class referenced from a local in the frozen frame.
            SnapshotEnvironment.ClassEntry personEntry = null;
            for (SnapshotEnvironment.ClassEntry e : env.classEntries()) {
                if (e.className().endsWith("HeapObjectFreezeProgram$Person")) {
                    personEntry = e;
                    break;
                }
            }
            if (personEntry == null) {
                // Dump all class entries to aid debugging if the test environment changes
                StringBuilder sb = new StringBuilder("Person class entry not found. Entries:\n");
                for (SnapshotEnvironment.ClassEntry e : env.classEntries()) {
                    sb.append("  ").append(e.className())
                      .append(" bytes=").append(e.bytecode() == null ? "null" : e.bytecode().length)
                      .append("\n");
                }
                fail(sb.toString());
            }

            byte[] bytes = personEntry.bytecode();
            assertNotNull(bytes, "Person ClassEntry must carry embedded bytecode");
            assertTrue(bytes.length > 0, "Embedded bytecode must be non-empty");
            // Sanity: Java class files start with CAFEBABE
            assertTrue(bytes.length > 4
                    && (bytes[0] & 0xFF) == 0xCA
                    && (bytes[1] & 0xFF) == 0xFE
                    && (bytes[2] & 0xFF) == 0xBA
                    && (bytes[3] & 0xFF) == 0xBE,
                    "Embedded bytecode must begin with the 0xCAFEBABE magic");
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    void freezeSkipsClassBytesByDefault() throws Exception {
        Path snapshotFile = Files.createTempFile("no-embedded-bytes-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.HeapObjectFreezeProgram",
                    classpath, port,
                    new String[]{snapshotFile.toString()}, 60);

            assertTrue(result.stdout().contains("FREEZE_COMPLETE"),
                    "Expected FREEZE_COMPLETE in child stdout; got:\n" + result.stdout()
                    + "\nstderr:\n" + result.stderr());

            ThreadSnapshot snapshot;
            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(snapshotFile))) {
                snapshot = (ThreadSnapshot) ois.readObject();
            }

            SnapshotEnvironment env = snapshot.environment();
            assertNotNull(env);
            for (SnapshotEnvironment.ClassEntry e : env.classEntries()) {
                if (e.bytecode() != null && e.bytecode().length > 0) {
                    fail("ClassEntry " + e.className() + " unexpectedly carries "
                            + e.bytecode().length + " embedded bytes while the "
                            + "embedClassBytecodes flag was off");
                }
            }
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }
}
