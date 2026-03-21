package ai.jacc.durableThreads.e2e;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Utility for launching child JVM processes with the durable-threads agent
 * and JDWP enabled. Used by E2E integration tests.
 */
public final class ChildJvm {

    private ChildJvm() {}

    /** Result of running a child JVM process. */
    public static final class Result {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        public Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int exitCode() { return exitCode; }
        public String stdout() { return stdout; }
        public String stderr() { return stderr; }

        public boolean succeeded() { return exitCode == 0; }

        public List<String> stdoutLines() {
            return stdout.isBlank() ? List.of() : List.of(stdout.split("\n"));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Result)) return false;
            Result that = (Result) o;
            return exitCode == that.exitCode
                    && Objects.equals(stdout, that.stdout)
                    && Objects.equals(stderr, that.stderr);
        }

        @Override
        public int hashCode() {
            return Objects.hash(exitCode, stdout, stderr);
        }

        @Override
        public String toString() {
            return "Result[exitCode=" + exitCode + ", stdout=" + stdout
                    + ", stderr=" + stderr + "]";
        }
    }

    /**
     * Run a class in a child JVM with the durable-threads agent loaded.
     *
     * @param mainClass     the class with a main(String[]) method
     * @param classpath     classpath entries (typically includes the agent jar and test classes)
     * @param jdwpPort      JDWP port: positive = specific port, 0 = no JDWP, negative = auto-assigned port
     * @param args          arguments to the main method
     * @param timeoutSec    max seconds to wait for the process
     * @return the process result
     */
    public static Result run(String mainClass, String classpath, int jdwpPort,
                              String[] args, int timeoutSec) throws Exception {
        String javaHome = System.getProperty("java.home");
        String java = Path.of(javaHome, "bin", "java").toString();
        String agentJar = findAgentJar();

        List<String> cmd = new ArrayList<>();
        cmd.add(java);

        // Agent
        cmd.add("-javaagent:" + agentJar);

        // JDWP (required for freeze/restore)
        if (jdwpPort > 0) {
            cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + jdwpPort);
        } else if (jdwpPort < 0) {
            // Auto-assigned port — JDWP picks an ephemeral port, library discovers it
            cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n");
        }

        // Add JDI module
        cmd.add("--add-modules");
        cmd.add("jdk.jdi");

        // Classpath
        cmd.add("-cp");
        cmd.add(classpath);

        // Suppress the proxy env from propagating to child JVMs
        cmd.add("-D_JAVA_OPTIONS=");

        // Main class
        cmd.add(mainClass);

        // Args
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("JAVA_TOOL_OPTIONS"); // don't propagate proxy
        pb.redirectErrorStream(false);

        Process proc = pb.start();

        // Read stdout and stderr in background threads to avoid blocking
        var stdoutFuture = CompletableFuture.supplyAsync(
                () -> readStream(proc.getInputStream()));
        var stderrFuture = CompletableFuture.supplyAsync(
                () -> readStream(proc.getErrorStream()));

        boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            proc.waitFor(5, TimeUnit.SECONDS); // give it a moment to die
        }

        String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
        String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

        if (!finished) {
            return new Result(-1, stdout, stderr + "\n[TIMEOUT after " + timeoutSec + "s]");
        }

        return new Result(proc.exitValue(), stdout, stderr);
    }

    /**
     * Build a classpath string containing the agent jar and the test classes directory.
     */
    public static String buildClasspath() {
        String agentJar = findAgentJar();
        String testClasses = Path.of("target", "test-classes").toAbsolutePath().toString();
        String mainClasses = Path.of("target", "classes").toAbsolutePath().toString();
        return agentJar + File.pathSeparator + testClasses + File.pathSeparator + mainClasses;
    }

    /**
     * Find an available port for JDWP.
     */
    public static int findFreePort() {
        try (var ss = new java.net.ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            return 15005 + new Random().nextInt(1000);
        }
    }

    private static String findAgentJar() {
        // Look for the shaded jar in target/
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

    private static String readStream(InputStream is) {
        try (var reader = new BufferedReader(new InputStreamReader(is))) {
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
