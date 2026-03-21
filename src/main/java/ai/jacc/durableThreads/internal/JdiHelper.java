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
     * Cached JDI connection discovered during port scanning.
     * Once we find and verify the JDWP port, we keep the connection open
     * so it can be reused for freeze/restore without reconnecting.
     */
    private static volatile VirtualMachine cachedVm;

    /**
     * Permanent strong reference to the last JDI connection returned by
     * {@link #connect(int)}. This prevents the VirtualMachine (and its
     * underlying socket) from being garbage-collected during JVM shutdown.
     * Without this, GC closes the socket, JDWP sees a disconnect, re-listens,
     * and prints a confusing "Listening for transport..." message on stderr.
     */
    private static volatile VirtualMachine keepAliveVm;

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
    public static int detectJdwpPort() {
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
            return argPort;
        }

        // Scan listening ports (platform-specific) with nonce verification
        int listenPort = detectPortFromListeningSockets();
        if (listenPort > 0) {
            return listenPort;
        }

        // Scan nearby ephemeral ports for JDWP with nonce verification
        int discoveredPort = discoverJdwpPort();
        if (discoveredPort > 0) {
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
        for (int port : listeningPorts) {
            if (connectAndVerifyNonce(port, expectedNonce)) {
                return port;
            }
        }
        return -1;
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
        List<String> args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
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

    // --- JDWP port discovery via ephemeral port scanning ---

    /** Socket connect/handshake timeout for JDI port probes (ms). */
    private static final int PROBE_TIMEOUT_MS = 200;

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
     * Maximum number of ports to scan in each direction from the probe port.
     * This is a last-resort fallback after the Attach API fails.
     */
    private static final int SCAN_RANGE = 200;

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

        // Scan nearby ports via JDI connect + nonce verification.
        // Closed ports fail instantly. Open non-JDWP ports time out after PROBE_TIMEOUT_MS.
        // Our JDWP port: handshake succeeds, nonce matches, connection CACHED.
        //
        // NOTE: Do NOT pre-probe with raw TCP or JDWP handshake — JDWP re-listens on
        // a NEW port after each debugger disconnect, so probes "consume" the port.

        for (int port = probePort - 1; port >= Math.max(1, probePort - SCAN_RANGE); port--) {
            if (connectAndVerifyNonce(port, expectedNonce)) {
                return port;
            }
        }
        for (int port = probePort; port <= Math.min(65535, probePort + SCAN_RANGE); port++) {
            if (connectAndVerifyNonce(port, expectedNonce)) {
                return port;
            }
        }

        return -1;
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
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Connect to a port via JDI and verify that it belongs to THIS JVM by reading
     * the nonce from {@code DurableAgent.jdwpDiscoveryNonce}.
     *
     * <p>If verified, the JDI connection is cached for later reuse by {@link #connect(int)}
     * — this avoids reconnecting and eliminates the double-connection problem with JDWP's
     * single-debugger-at-a-time constraint.</p>
     */
    private static boolean connectAndVerifyNonce(int port, String expectedNonce) {
        // Use a hard timeout via Future — JDI's built-in timeout doesn't reliably
        // abort when a non-JDWP port accepts TCP but never sends the handshake.
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jdwp-probe-" + port);
            t.setDaemon(true);
            return t;
        });
        try {
            Future<VirtualMachine> future = executor.submit(() -> {
                VirtualMachine vm = jdiConnect(port, PROBE_TIMEOUT_MS);
                List<ReferenceType> types = vm.classesByName("ai.jacc.durableThreads.DurableAgent");
                if (types.isEmpty()) { vm.dispose(); return null; }
                Field nonceField = types.get(0).fieldByName("jdwpDiscoveryNonce");
                if (nonceField == null) { vm.dispose(); return null; }
                Value val = types.get(0).getValue(nonceField);
                if (val instanceof StringReference && expectedNonce.equals(((StringReference) val).value())) {
                    return vm; // Nonce matches — return the connection
                }
                vm.dispose();
                return null;
            });

            VirtualMachine vm = future.get(PROBE_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
            if (vm != null) {
                cachedVm = vm;
                return true;
            }
            return false;
        } catch (TimeoutException e) {
            // Hard timeout — JDI connect hung on a non-JDWP port
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Connect to the local JVM via JDI socket attach.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Reuse the existing {@code keepAliveVm} connection (from a prior
     *       freeze or restore in the same JVM)</li>
     *   <li>Return a cached connection from port auto-discovery</li>
     *   <li>Establish a new connection</li>
     * </ol>
     *
     * @param port the JDWP port
     * @return the VirtualMachine connection
     */
    public static VirtualMachine connect(int port) {
        VirtualMachine vm;

        // Reuse existing keep-alive connection (from prior freeze/restore)
        VirtualMachine alive = keepAliveVm;
        if (alive != null) {
            try {
                // Verify the connection is still usable by calling a lightweight method
                alive.allThreads();
                return alive;
            } catch (Exception e) {
                // Connection is dead — fall through to reconnect
                keepAliveVm = null;
            }
        }

        // Return cached connection from discovery if available
        VirtualMachine cached = cachedVm;
        if (cached != null) {
            cachedVm = null;
            vm = cached;
        } else {
            vm = jdiConnect(port, 0);
        }

        // Keep a strong static reference so the VM (and its socket) is never
        // GC'd. Without this, GC during shutdown closes the socket, JDWP
        // re-listens, and prints a spurious "Listening..." message on stderr.
        keepAliveVm = vm;
        return vm;
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
     * Get the current process ID in a Java 8 compatible way.
     * Uses ManagementFactory.getRuntimeMXBean().getName() which returns "pid@hostname".
     */
    private static long getPid() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int atIdx = name.indexOf('@');
        if (atIdx > 0) {
            try {
                return Long.parseLong(name.substring(0, atIdx));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }
}
