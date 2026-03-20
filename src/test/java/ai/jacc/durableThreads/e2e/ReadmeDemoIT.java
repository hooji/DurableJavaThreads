package ai.jacc.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test that replicates the exact Quick Start demo from the README.
 *
 * <p>This test writes FreezeDemo.java and RestoreDemo.java as standalone
 * source files, compiles them with {@code javac -g}, then runs them with
 * the agent jar — exactly as documented in the README.</p>
 *
 * <p>If the demo instructions in the README become stale, this test will
 * catch it.</p>
 */
@Tag("e2e")
class ReadmeDemoIT {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("readme-demo-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null) {
            try (var files = Files.walk(tempDir)) {
                files.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
    }

    @Test
    @DisplayName("README Quick Start demo: compile, freeze on main thread, restore")
    void readmeQuickStartDemo() throws Exception {
        String agentJar = findAgentJar();

        // Write FreezeDemo.java — exactly as shown in the README
        Files.writeString(tempDir.resolve("FreezeDemo.java"), """
                import ai.jacc.durableThreads.Durable;

                public class FreezeDemo {
                    public static void main(String[] args) throws Exception {
                        for (int i = 0; i <= 10; i++) {
                            System.out.println("i=" + i);

                            if (i == 5) {
                                System.out.println("About to freeze!");
                                Durable.freeze("./snapshot.dat");
                                // Everything below only runs after restore
                                System.out.println("Resumed!");
                            }
                        }
                        System.out.println("Done!");
                    }
                }
                """);

        // Write RestoreDemo.java — exactly as shown in the README
        Files.writeString(tempDir.resolve("RestoreDemo.java"), """
                import ai.jacc.durableThreads.Durable;

                public class RestoreDemo {
                    public static void main(String[] args) throws Exception {
                        Durable.restore("./snapshot.dat", true, true);
                    }
                }
                """);

        // --- Compile with javac -g (exactly as README instructs) ---
        String javaHome = System.getProperty("java.home");
        String javac = Path.of(javaHome, "bin", "javac").toString();

        ProcessBuilder compilePb = new ProcessBuilder(
                javac, "-g", "-cp", agentJar,
                "FreezeDemo.java", "RestoreDemo.java");
        compilePb.directory(tempDir.toFile());
        compilePb.environment().remove("JAVA_TOOL_OPTIONS");
        Process compileProc = compilePb.start();
        String compileErr = readStream(compileProc.getErrorStream());
        assertTrue(compileProc.waitFor(30, TimeUnit.SECONDS), "javac should complete");
        assertEquals(0, compileProc.exitValue(),
                "javac -g should succeed. Stderr:\n" + compileErr);
        assertTrue(Files.exists(tempDir.resolve("FreezeDemo.class")), "FreezeDemo.class should exist");
        assertTrue(Files.exists(tempDir.resolve("RestoreDemo.class")), "RestoreDemo.class should exist");

        // --- Run FreezeDemo (freezes main thread at i==5) ---
        String java = Path.of(javaHome, "bin", "java").toString();
        String classpath = tempDir.toAbsolutePath() + File.pathSeparator + agentJar;

        ProcessBuilder freezePb = new ProcessBuilder(
                java,
                "-javaagent:" + agentJar,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=44892",
                "--add-modules", "jdk.jdi",
                "-cp", classpath,
                "FreezeDemo");
        freezePb.directory(tempDir.toFile());
        freezePb.environment().remove("JAVA_TOOL_OPTIONS");
        Process freezeProc = freezePb.start();

        var freezeStdout = CompletableFuture.supplyAsync(() -> readStream(freezeProc.getInputStream()));
        var freezeStderr = CompletableFuture.supplyAsync(() -> readStream(freezeProc.getErrorStream()));

        assertTrue(freezeProc.waitFor(60, TimeUnit.SECONDS), "FreezeDemo should complete within 60s");

        String fOut = freezeStdout.get(5, TimeUnit.SECONDS);
        String fErr = freezeStderr.get(5, TimeUnit.SECONDS);

        System.out.println("=== FREEZE STDOUT ===\n" + fOut);
        if (!fErr.isBlank()) System.out.println("=== FREEZE STDERR ===\n" + fErr);

        // Should print i=0 through i=5 and "About to freeze!"
        for (int i = 0; i <= 5; i++) {
            assertTrue(fOut.contains("i=" + i), "Should print i=" + i);
        }
        assertTrue(fOut.contains("About to freeze!"), "Should print 'About to freeze!'");

        // Original should NOT resume or continue
        assertFalse(fOut.contains("Resumed!"), "Original should not resume");
        assertFalse(fOut.contains("Done!"), "Original should not reach Done");
        assertFalse(fOut.contains("i=6"), "Original should not reach i=6");

        // Snapshot file should exist
        Path snapshotFile = tempDir.resolve("snapshot.dat");
        assertTrue(Files.exists(snapshotFile), "snapshot.dat should exist");
        assertTrue(Files.size(snapshotFile) > 100, "snapshot.dat should have content");

        // --- Run RestoreDemo ---
        ProcessBuilder restorePb = new ProcessBuilder(
                java,
                "-javaagent:" + agentJar,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=44892",
                "--add-modules", "jdk.jdi",
                "-cp", classpath,
                "RestoreDemo");
        restorePb.directory(tempDir.toFile());
        restorePb.environment().remove("JAVA_TOOL_OPTIONS");
        Process restoreProc = restorePb.start();

        var restoreStdout = CompletableFuture.supplyAsync(() -> readStream(restoreProc.getInputStream()));
        var restoreStderr = CompletableFuture.supplyAsync(() -> readStream(restoreProc.getErrorStream()));

        assertTrue(restoreProc.waitFor(60, TimeUnit.SECONDS), "RestoreDemo should complete within 60s");

        String rOut = restoreStdout.get(5, TimeUnit.SECONDS);
        String rErr = restoreStderr.get(5, TimeUnit.SECONDS);

        System.out.println("=== RESTORE STDOUT ===\n" + rOut);
        if (!rErr.isBlank()) System.out.println("=== RESTORE STDERR ===\n" + rErr);

        // Restored thread should resume and complete
        assertTrue(rOut.contains("Resumed!"), "Restored thread should print 'Resumed!'");
        assertTrue(rOut.contains("Done!"), "Restored thread should print 'Done!'");
        for (int i = 6; i <= 10; i++) {
            assertTrue(rOut.contains("i=" + i), "Restored thread should print i=" + i);
        }

        // Restored thread must NOT replay pre-freeze output
        for (int i = 0; i <= 4; i++) {
            String lineToCheck = "i=" + i;
            assertFalse(rOut.lines().anyMatch(l -> l.equals(lineToCheck)),
                    "Restore must not replay i=" + i + ". Stdout:\n" + rOut);
        }
        assertFalse(rOut.contains("About to freeze!"),
                "Restore must not replay 'About to freeze!'. Stdout:\n" + rOut);

        // Exact output check: only post-freeze lines
        var userLines = rOut.lines()
                .filter(l -> !l.isBlank())
                .filter(l -> !l.startsWith("Listening for transport dt_socket"))
                .toList();
        assertEquals(List.of(
                "Resumed!", "i=6", "i=7", "i=8", "i=9", "i=10", "Done!"),
                userLines,
                "Restore output should be exactly the post-freeze lines. Got:\n" + rOut);
    }

    private static String findAgentJar() {
        Path target = Path.of("target");
        try (var files = Files.list(target)) {
            return files
                    .filter(p -> p.getFileName().toString().startsWith("durable-threads-"))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().contains("original"))
                    .findFirst()
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .orElseThrow(() -> new RuntimeException(
                            "Agent jar not found in target/. Run 'mvn package -DskipTests' first."));
        } catch (IOException e) {
            throw new RuntimeException("Cannot list target/ directory", e);
        }
    }

    private static String readStream(java.io.InputStream is) {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "[error reading stream: " + e.getMessage() + "]";
        }
    }
}
