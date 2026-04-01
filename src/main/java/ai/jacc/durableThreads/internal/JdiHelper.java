package ai.jacc.durableThreads.internal;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Utilities for connecting to the local JVM via JDI (Java Debug Interface).
 */
public final class JdiHelper {

    private JdiHelper() {}

    /**
     * The single JDI connection to this JVM. Set once (either during port
     * discovery or on first call to {@link #getConnection()}) and never
     * replaced. The connection stays alive for the lifetime of the JVM.
     *
     * <p>Holding a strong static reference prevents GC from closing the
     * underlying socket. Without this, GC during shutdown closes the socket,
     * JDWP sees a disconnect, re-listens, and prints a confusing
     * "Listening for transport..." message on stderr.</p>
     */
    private static volatile VirtualMachine connection;

    /**
     * Default JDWP port. When no explicit port is detected from command-line
     * arguments, the library assumes JDWP is listening on this port. Users
     * can override by passing a different port in their {@code -agentlib:jdwp}
     * argument, or by setting the system property {@code durable.jdwp.port}.
     */
    public static final int DEFAULT_JDWP_PORT = 44892;

    /**
     * Detect the JDWP port this JVM is listening on.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Agent's cached port (detected eagerly at startup)</li>
     *   <li>System property {@code durable.jdwp.port}</li>
     *   <li>Parsed from JVM command-line arguments</li>
     *   <li>Platform-specific listening port enumeration with nonce verification</li>
     *   <li>Ephemeral port scan with nonce verification (fallback)</li>
     *   <li>Default: {@value #DEFAULT_JDWP_PORT}</li>
     * </ol>
     *
     * @return the JDWP port (always positive)
     */
    private static int detectJdwpPort() {
        // Check the agent's cached port first (detected eagerly at startup)
        int cached = ai.jacc.durableThreads.DurableAgent.getCachedJdwpPort();
        if (cached > 0) {
            return cached;
        }

        // Check system property override
        String propPort = System.getProperty("durable.jdwp.port");
        if (propPort != null) {
            try {
                int port = Integer.parseInt(propPort.trim());
                if (port > 0) return port;
            } catch (NumberFormatException ignored) {
            }
        }

        // Parse from command-line arguments (works for any fixed port)
        int argPort = detectPortFromArguments();
        if (argPort > 0) {
            ai.jacc.durableThreads.DurableAgent.cacheJdwpPort(argPort);
            return argPort;
        }

        // Scan listening ports (platform-specific) with nonce verification
        int listenPort = detectPortFromListeningSockets();
        if (listenPort > 0) {
            ai.jacc.durableThreads.DurableAgent.cacheJdwpPort(listenPort);
            return listenPort;
        }

        // Scan nearby ephemeral ports for JDWP with nonce verification
        int discoveredPort = discoverJdwpPort();
        if (discoveredPort > 0) {
            ai.jacc.durableThreads.DurableAgent.cacheJdwpPort(discoveredPort);
            return discoveredPort;
        }

        // Default port — assumes JDWP was started with address=127.0.0.1:44892
        return DEFAULT_JDWP_PORT;
    }

    /**
     * Same as {@link #detectJdwpPort()} but returns -1 instead of the default port,
     * and skips JDI-based discovery (unsafe during premain — the JVM isn't fully
     * initialized and JDI connect to self would deadlock).
     * Used during premain to avoid caching an incorrect default.
     */
    public static int detectJdwpPortNoDefault() {
        int cached = ai.jacc.durableThreads.DurableAgent.getCachedJdwpPort();
        if (cached > 0) return cached;

        String propPort = System.getProperty("durable.jdwp.port");
        if (propPort != null) {
            try {
                int port = Integer.parseInt(propPort.trim());
                if (port > 0) return port;
            } catch (NumberFormatException ignored) {}
        }

        int argPort = detectPortFromArguments();
        if (argPort > 0) return argPort;

        // Do NOT call detectPortFromListeningSockets() or discoverJdwpPort() here —
        // they use JDI connect which deadlocks during premain. Port discovery
        // will happen lazily at first freeze/restore.

        return -1;
    }

    /**
     * Discover the JDWP port by enumerating listening TCP ports on this machine,
     * then verifying each via JDI nonce check.
     *
     * <p>Platform-specific enumeration:</p>
     * <ul>
     *   <li><b>Linux:</b> reads {@code /proc/net/tcp} and {@code /proc/net/tcp6} (no subprocess)</li>
     *   <li><b>macOS:</b> runs {@code lsof -iTCP -sTCP:LISTEN -nP -p <pid>}</li>
     *   <li><b>Windows:</b> runs {@code netstat -ano} and filters by PID</li>
     * </ul>
     *
     * <p>This is fast because a typical process has very few listening ports.
     * No Attach API or {@code allowAttachSelf} flag needed.</p>
     *
     * @return the verified JDWP port, or -1 if not found
     */
    private static int detectPortFromListeningSockets() {
        if (!isJdwpOnCommandLine()) {
            return -1;
        }

        String expectedNonce = ai.jacc.durableThreads.DurableAgent.jdwpDiscoveryNonce;
        if (expectedNonce == null || expectedNonce.isEmpty()) {
            return -1;
        }

        List<Integer> listeningPorts = getListeningPorts();
        return probePortsInParallel(listeningPorts, expectedNonce);
    }

    // --- Platform-specific listening port enumeration ---

    /**
     * Get all TCP ports in LISTEN state, using platform-specific mechanisms.
     */
    private static List<Integer> getListeningPorts() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            return getListeningPortsLinux();
        } else if (os.contains("mac") || os.contains("darwin")) {
            return getListeningPortsMacOS();
        } else if (os.contains("win")) {
            return getListeningPortsWindows();
        }
        return Collections.emptyList(); // Unknown OS — fall through to ephemeral scan
    }

    /**
     * Linux: parse {@code /proc/net/tcp} and {@code /proc/net/tcp6}.
     * Each line: {@code sl local_address rem_address st ...}
     * where local_address is {@code hex_ip:hex_port} and st {@code 0A} = LISTEN.
     */
    private static List<Integer> getListeningPortsLinux() {
        List<Integer> ports = new ArrayList<>();
        for (String procFile : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try {
                List<String> lines = Files.readAllLines(java.nio.file.Paths.get(procFile));
                for (int i = 1; i < lines.size(); i++) { // skip header
                    String[] fields = lines.get(i).trim().split("\\s+");
                    if (fields.length >= 4 && "0A".equals(fields[3])) {
                        String localAddr = fields[1];
                        int colonIdx = localAddr.indexOf(':');
                        if (colonIdx >= 0) {
                            int port = Integer.parseInt(localAddr.substring(colonIdx + 1), 16);
                            if (port > 0 && !ports.contains(port)) {
                                ports.add(port);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return ports;
    }

    /**
     * macOS: run {@code lsof -iTCP -sTCP:LISTEN -nP -p <pid>} and parse output.
     * Output lines look like: {@code java 1234 user 5u IPv6 0x... TCP *:43567 (LISTEN)}
     */
    private static List<Integer> getListeningPortsMacOS() {
        List<Integer> ports = new ArrayList<>();
        long pid = getPid();
        try {
            Process proc = new ProcessBuilder("lsof", "-iTCP", "-sTCP:LISTEN", "-nP",
                    "-p", String.valueOf(pid)).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Find "TCP *:PORT" or "TCP 127.0.0.1:PORT"
                    int tcpIdx = line.indexOf("TCP ");
                    if (tcpIdx < 0) continue;
                    String afterTcp = line.substring(tcpIdx + 4);
                    int colonIdx = afterTcp.indexOf(':');
                    if (colonIdx < 0) continue;
                    int spaceIdx = afterTcp.indexOf(' ', colonIdx);
                    String portStr = spaceIdx < 0
                            ? afterTcp.substring(colonIdx + 1)
                            : afterTcp.substring(colonIdx + 1, spaceIdx);
                    try {
                        int port = Integer.parseInt(portStr.trim());
                        if (port > 0 && !ports.contains(port)) {
                            ports.add(port);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            } finally {
                reader.close();
            }
            proc.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        return ports;
    }

    /**
     * Windows: run {@code netstat -ano} and filter for LISTENING lines matching our PID.
     * Output lines look like: {@code TCP 0.0.0.0:43567 0.0.0.0:0 LISTENING 1234}
     */
    private static List<Integer> getListeningPortsWindows() {
        List<Integer> ports = new ArrayList<>();
        String pid = String.valueOf(getPid());
        try {
            Process proc = new ProcessBuilder("netstat", "-ano")
                    .redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.contains("LISTENING")) continue;
                    String[] fields = trimmed.split("\\s+");
                    // fields: [TCP, local_addr, foreign_addr, LISTENING, pid]
                    if (fields.length < 5 || !fields[4].equals(pid)) continue;
                    String localAddr = fields[1]; // e.g. "0.0.0.0:43567" or "[::]:43567"
                    int colonIdx = localAddr.lastIndexOf(':');
                    if (colonIdx < 0) continue;
                    try {
                        int port = Integer.parseInt(localAddr.substring(colonIdx + 1).trim());
                        if (port > 0 && !ports.contains(port)) {
                            ports.add(port);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            } finally {
                reader.close();
            }
            proc.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        return ports;
    }

    /**
     * Parse the JDWP port from the JVM's command-line input arguments.
     * Returns the port if it is a fixed (non-zero) value, or -1 if not found
     * or if port 0 was specified (meaning OS-assigned).
     */
    private static int detectPortFromArguments() {
        List<String> args;
        try {
            args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Throwable t) {
            // ManagementFactory can fail on some JDK versions (10-14) with
            // NoClassDefFoundError: Could not initialize class PlatformMBeanFinder.
            // Fall through to other port detection methods.
            return -1;
        }
        for (String arg : args) {
            if (arg.contains("jdwp") && arg.contains("address=")) {
                String addressPart = extractValue(arg, "address");
                if (addressPart != null) {
                    int colonIdx = addressPart.lastIndexOf(':');
                    String portStr = colonIdx >= 0 ? addressPart.substring(colonIdx + 1) : addressPart;
                    try {
                        int port = Integer.parseInt(portStr.trim());
                        if (port > 0) {
                            return port;
                        }
                    } catch (NumberFormatException e) {
                        // continue searching
                    }
                }
            }
        }
        return -1;
    }

    // --- JDWP port discovery via parallel probing ---

    /** Socket connect/handshake timeout for JDI port probes (ms). */
    private static final int PROBE_TIMEOUT_MS = 200;

    /**
     * Probe a list of candidate ports in parallel for a JDWP connection
     * belonging to this JVM (verified via nonce).
     *
     * <p>One thread is created per candidate — every probe starts immediately
     * with no queuing. The correct JDWP port responds in microseconds on
     * localhost, so this returns almost instantly. Failed probes (closed ports,
     * non-JDWP services) time out harmlessly in the background.</p>
     *
     * <p>Each probe wraps its {@code jdiConnect} call in a hard timeout via
     * {@code Future.get()} to handle non-JDWP ports that accept TCP but never
     * complete the JDWP handshake (where JDI's built-in timeout is unreliable).</p>
     *
     * @param candidates list of port numbers to probe (must not contain duplicates)
     * @param expectedNonce the nonce to verify
     * @return the verified JDWP port, or -1 if none matched
     */
    private static int probePortsInParallel(List<Integer> candidates, String expectedNonce) {
        if (candidates.isEmpty()) return -1;

        // One thread per candidate — no queuing, no starvation.
        ExecutorService probePool = Executors.newFixedThreadPool(candidates.size(), r -> {
            Thread t = new Thread(r, "jdwp-probe");
            t.setDaemon(true);
            return t;
        });
        // Separate single-thread pool for per-probe hard timeouts. Each probe
        // submits its jdiConnect to this pool and waits with Future.get(timeout),
        // ensuring hung JDWP handshakes on non-JDWP ports are killed reliably.
        ExecutorService connectPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "jdwp-connect");
            t.setDaemon(true);
            return t;
        });
        CompletionService<Integer> cs = new ExecutorCompletionService<>(probePool);

        try {
            for (int port : candidates) {
                cs.submit(() -> {
                    try {
                        // Hard timeout via Future — JDI's built-in timeout doesn't
                        // reliably abort when a non-JDWP port accepts TCP but never
                        // sends the JDWP handshake.
                        Future<VirtualMachine> connectFuture = connectPool.submit(
                                () -> jdiConnect(port, PROBE_TIMEOUT_MS));
                        VirtualMachine vm;
                        try {
                            vm = connectFuture.get(PROBE_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException e) {
                            connectFuture.cancel(true);
                            return -1;
                        }
                        List<ReferenceType> types = vm.classesByName(
                                "ai.jacc.durableThreads.DurableAgent");
                        if (types.isEmpty()) { vm.dispose(); return -1; }
                        Field nonceField = types.get(0).fieldByName("jdwpDiscoveryNonce");
                        if (nonceField == null) { vm.dispose(); return -1; }
                        Value val = types.get(0).getValue(nonceField);
                        if (val instanceof StringReference
                                && expectedNonce.equals(((StringReference) val).value())) {
                            connection = vm;
                            return port;
                        }
                        vm.dispose();
                    } catch (Exception e) {
                        // Connection failed, not JDWP, etc. — expected for most ports
                    }
                    return -1;
                });
            }

            // Collect results with a total deadline. The correct port responds
            // almost instantly; we only wait for the full timeout if no port matches.
            long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS + 500;
            for (int i = 0; i < candidates.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    Future<Integer> future = cs.poll(remaining, TimeUnit.MILLISECONDS);
                    if (future == null) break;
                    int port = future.get();
                    if (port > 0) return port;
                } catch (ExecutionException | InterruptedException e) {
                    // continue collecting
                }
            }
            return -1;
        } finally {
            probePool.shutdownNow();
            connectPool.shutdownNow();
        }
    }

    /**
     * Discover the JDWP port by scanning nearby ephemeral ports.
     *
     * <p>Strategy:</p>
     * <ol>
     *   <li>Allocate and immediately release an ephemeral port ("probe port") to
     *       get a reference point in the OS's ephemeral range. Since the OS assigns
     *       ephemeral ports roughly sequentially, JDWP's port (allocated earlier at
     *       JVM startup) will be nearby — typically just below the probe port.</li>
     *   <li>Scan ports near the probe. For each open port, attempt a full JDI
     *       connection and verify the nonce stored in
     *       {@code DurableAgent.jdwpDiscoveryNonce} to confirm it belongs to THIS JVM.</li>
     * </ol>
     *
     * @return the discovered JDWP port, or -1 if not found
     */
    /**
     * Number of ports to scan in each direction from the probe port.
     * With one thread per candidate, all 2*SCAN_RANGE+1 ports are probed
     * simultaneously — the correct port responds in microseconds.
     */
    private static final int SCAN_RANGE = 50;

    private static int discoverJdwpPort() {
        // Only scan if JDWP is actually on the command line
        if (!isJdwpOnCommandLine()) {
            return -1;
        }

        String expectedNonce = ai.jacc.durableThreads.DurableAgent.jdwpDiscoveryNonce;
        if (expectedNonce == null || expectedNonce.isEmpty()) {
            return -1;
        }

        // Allocate a probe port as a reference point in the ephemeral range.
        int probePort;
        try (ServerSocket ss = new ServerSocket(0)) {
            probePort = ss.getLocalPort();
        } catch (IOException e) {
            return -1;
        }

        // Build candidate list: ports below the probe (most likely), then above.
        // NOTE: Do NOT pre-probe with raw TCP or JDWP handshake — JDWP re-listens on
        // a NEW port after each debugger disconnect, so probes "consume" the port.
        List<Integer> candidates = new ArrayList<>();
        for (int port = probePort - 1; port >= Math.max(1, probePort - SCAN_RANGE); port--) {
            candidates.add(port);
        }
        for (int port = probePort; port <= Math.min(65535, probePort + SCAN_RANGE); port++) {
            candidates.add(port);
        }

        return probePortsInParallel(candidates, expectedNonce);
    }

    /**
     * Check if JDWP appears on the JVM command line.
     * This avoids expensive port scanning when JDWP is not configured.
     */
    private static boolean isJdwpOnCommandLine() {
        try {
            List<String> args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String arg : args) {
                if (arg.contains("jdwp")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // ManagementFactory fails on some JDK versions (10-14).
            // Assume JDWP might be present — let the port scanner check.
            return true;
        }
        return false;
    }

    private static final Object JDI_CONNECT_LOCK = new Object();

    /**
     * Get the JDI connection to this JVM, creating it on first call.
     *
     * <p>The connection is established once and reused for all subsequent
     * freeze/restore operations. It is never replaced or reconnected —
     * the JDI connection to our own JVM stays alive for the lifetime of
     * the process.</p>
     *
     * <p>If parallel port discovery ({@link #probePortsInParallel}) already
     * established a connection, that connection is used directly without
     * creating a second one.</p>
     *
     * @return the VirtualMachine connection (never null)
     * @throws RuntimeException if JDWP is not enabled or connection fails
     */
    public static VirtualMachine getConnection() {
        VirtualMachine vm = connection;
        if (vm != null) return vm;

        synchronized (JDI_CONNECT_LOCK) {
            // Double-check under lock
            vm = connection;
            if (vm != null) return vm;

            // Detect the JDWP port. Port discovery may establish a connection
            // as a side-effect (probePortsInParallel sets 'connection' when
            // it finds and verifies the port via a JDI probe). Check again
            // after detection before attempting a redundant connect.
            int port = detectJdwpPort();
            vm = connection;
            if (vm != null) return vm;

            if (port < 0) {
                throw new RuntimeException(
                        "JDWP not enabled. Start with: "
                        + "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005");
            }
            vm = jdiConnect(port, 0);
            connection = vm;
            return vm;
        }
    }

    /**
     * Low-level JDI socket attach with optional timeout.
     *
     * @param port      JDWP port
     * @param timeoutMs connection timeout in ms, or 0 for no timeout
     */
    private static VirtualMachine jdiConnect(int port, int timeoutMs) {
        try {
            AttachingConnector connector = findSocketAttachConnector();
            Map<String, Connector.Argument> arguments = connector.defaultArguments();
            arguments.get("hostname").setValue("127.0.0.1");
            arguments.get("port").setValue(String.valueOf(port));
            if (timeoutMs > 0) {
                Connector.Argument timeoutArg = arguments.get("timeout");
                if (timeoutArg != null) {
                    timeoutArg.setValue(String.valueOf(timeoutMs));
                }
            }
            return connector.attach(arguments);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to JVM via JDI on port " + port, e);
        }
    }

    /**
     * Find the JDI ThreadReference corresponding to a Java thread.
     *
     * <p><b>Important:</b> We match by thread name, NOT by
     * {@link ObjectReference#uniqueID()}.  JDI's {@code uniqueID()} is an
     * internal mirror-object identifier that has <em>no</em> relation to
     * {@link Thread#threadId()}.  Comparing the two can cause accidental
     * collisions (returning the wrong thread), leading to corrupt snapshots
     * with zero user frames.</p>
     */
    public static ThreadReference findThread(VirtualMachine vm, Thread javaThread) {
        String name = javaThread.getName();
        for (ThreadReference tr : vm.allThreads()) {
            if (tr.name().equals(name)) {
                return tr;
            }
        }
        throw new RuntimeException("Could not find JDI ThreadReference for thread: " + name);
    }

    private static AttachingConnector findSocketAttachConnector() {
        for (AttachingConnector connector : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (connector.name().equals("com.sun.jdi.SocketAttach")) {
                return connector;
            }
        }
        throw new RuntimeException("SocketAttach connector not found. Is JDI available?");
    }

    private static String extractValue(String arg, String key) {
        int idx = arg.indexOf(key + "=");
        if (idx < 0) return null;
        int start = idx + key.length() + 1;
        int end = arg.indexOf(',', start);
        return end < 0 ? arg.substring(start) : arg.substring(start, end);
    }

    /**
     * Get the current process ID.
     *
     * <p>Tries ProcessHandle.current().pid() first (JDK 9+), falling back to
     * ManagementFactory.getRuntimeMXBean().getName() for JDK 8. The fallback
     * can fail on JDK 10-14 with ManagementFactory initialization errors.</p>
     */
    private static long getPid() {
        // JDK 9+: ProcessHandle.current().pid() — no ManagementFactory dependency
        try {
            Class<?> phClass = Class.forName("java.lang.ProcessHandle");
            Object current = phClass.getMethod("current").invoke(null);
            return (long) phClass.getMethod("pid").invoke(current);
        } catch (Throwable ignored) {
            // JDK 8 or reflection failure — fall through
        }

        // JDK 8 fallback: ManagementFactory
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int atIdx = name.indexOf('@');
            if (atIdx > 0) {
                return Long.parseLong(name.substring(0, atIdx));
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    // ===================================================================
    // ConcurrentHashMap JDI walking utilities
    // ===================================================================

    /**
     * Find a field by name in a JDI type or its supertypes.
     *
     * <p>ConcurrentHashMap's internal {@code Node} class inherits fields from
     * superclasses, so a simple {@code fieldByName} on the concrete type may
     * miss them. This walks up the class hierarchy.</p>
     *
     * @param type the JDI type to search
     * @param name the field name
     * @return the field, or null if not found
     */
    public static com.sun.jdi.Field findFieldInHierarchy(ReferenceType type, String name) {
        com.sun.jdi.Field f = type.fieldByName(name);
        if (f != null) return f;
        if (type instanceof ClassType && ((ClassType) type).superclass() != null) {
            return findFieldInHierarchy(((ClassType) type).superclass(), name);
        }
        return null;
    }

    /**
     * Look up a single value by String key in a {@code ConcurrentHashMap}
     * accessed via JDI.
     *
     * <p>Walks the internal {@code table} array and node chains to find the
     * entry matching {@code targetKey}. This depends on ConcurrentHashMap's
     * internal structure ({@code table}, {@code key}, {@code val}, {@code next}
     * fields), which has been stable across JDK 8–25.</p>
     *
     * @param mapRef JDI reference to the ConcurrentHashMap instance
     * @param targetKey the String key to search for
     * @return the Value associated with the key, or null if not found
     */
    public static Value getConcurrentHashMapValue(ObjectReference mapRef, String targetKey) {
        ReferenceType mapType = mapRef.referenceType();

        com.sun.jdi.Field tableField = findFieldInHierarchy(mapType, "table");
        if (tableField == null) return null;

        ArrayReference table = (ArrayReference) mapRef.getValue(tableField);
        if (table == null) return null;

        for (int i = 0; i < table.length(); i++) {
            ObjectReference node = (ObjectReference) table.getValue(i);
            while (node != null) {
                com.sun.jdi.Field keyField = findFieldInHierarchy(node.referenceType(), "key");
                com.sun.jdi.Field valField = findFieldInHierarchy(node.referenceType(), "val");
                com.sun.jdi.Field nextField = findFieldInHierarchy(node.referenceType(), "next");

                if (keyField == null || valField == null) break;

                Value keyVal = node.getValue(keyField);
                if (keyVal instanceof StringReference
                        && ((StringReference) keyVal).value().equals(targetKey)) {
                    return node.getValue(valField);
                }

                if (nextField != null) {
                    Value nextVal = node.getValue(nextField);
                    node = (nextVal instanceof ObjectReference) ? (ObjectReference) nextVal : null;
                } else {
                    break;
                }
            }
        }
        return null;
    }
}
