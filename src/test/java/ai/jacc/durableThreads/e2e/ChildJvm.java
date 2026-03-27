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
            return stdout.trim().isEmpty() ? Collections.<String>emptyList() : Arrays.asList(stdout.split("\n"));
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
        String java = Paths.get(javaHome, "bin", "java").toString();
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

        // Add JDI and management modules (only on JDK 9+, where the module system exists).
        // jdk.jdi is needed for the JDI self-attach. java.management is needed for
        // ManagementFactory.getRuntimeMXBean() in JDWP port detection. On some
        // non-LTS JDK versions (10, 12-14), java.management is not in the default
        // resolved module set and must be explicitly added.
        if (javaSpecVersion() >= 9) {
            cmd.add("--add-modules");
            cmd.add("jdk.jdi,java.management");
        }

        // Classpath — on Java 8, append tools.jar for com.sun.jdi classes
        cmd.add("-cp");
        String toolsJar = findToolsJar();
        cmd.add(toolsJar != null ? classpath + File.pathSeparator + toolsJar : classpath);

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
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                () -> readStream(proc.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
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
        String testClasses = Paths.get("target", "test-classes").toAbsolutePath().toString();
        String mainClasses = Paths.get("target", "classes").toAbsolutePath().toString();
        String cp = agentJar + File.pathSeparator + testClasses + File.pathSeparator + mainClasses;
        String toolsJar = findToolsJar();
        if (toolsJar != null) {
            cp += File.pathSeparator + toolsJar;
        }
        return cp;
    }

    /**
     * Find an available port for JDWP.
     */
    public static int findFreePort() {
        java.net.ServerSocket ss = null;
        try {
            ss = new java.net.ServerSocket(0);
            return ss.getLocalPort();
        } catch (IOException e) {
            return 15005 + new Random().nextInt(1000);
        } finally {
            if (ss != null) {
                try { ss.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Locate tools.jar for Java 8 JDKs (contains com.sun.jdi classes).
     * Returns null on Java 9+ where JDI is in the jdk.jdi module.
     */
    static String findToolsJar() {
        if (javaSpecVersion() >= 9) return null;
        String javaHome = System.getProperty("java.home");
        // java.home typically points to the JRE inside the JDK, e.g. /usr/lib/jvm/java-8/jre
        Path toolsJar = Paths.get(javaHome, "..", "lib", "tools.jar").normalize();
        if (Files.exists(toolsJar)) return toolsJar.toString();
        // Some layouts have java.home pointing to the JDK root
        toolsJar = Paths.get(javaHome, "lib", "tools.jar");
        if (Files.exists(toolsJar)) return toolsJar.toString();
        return null;
    }

    /** Returns the major Java version (8, 9, 10, …, 25). */
    static int javaSpecVersion() {
        String v = System.getProperty("java.specification.version", "1.8");
        // Java 8 reports "1.8"; Java 9+ reports "9", "10", etc.
        if (v.startsWith("1.")) {
            return Integer.parseInt(v.substring(2));
        }
        return Integer.parseInt(v);
    }

    private static String findAgentJar() {
        // Look for the shaded jar in target/
        Path target = Paths.get("target");
        java.util.stream.Stream<Path> files = null;
        try {
            files = Files.list(target);
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
        } finally {
            if (files != null) files.close();
        }
    }

    private static String readStream(InputStream is) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "[error reading stream: " + e.getMessage() + "]";
        } finally {
            try { reader.close(); } catch (IOException ignored) {}
        }
    }
}
