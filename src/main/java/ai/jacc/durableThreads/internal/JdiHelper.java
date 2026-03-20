package ai.jacc.durableThreads.internal;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

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
     *   <li>Attach API (optional fallback for {@code address=...:0})</li>
     *   <li>Ephemeral port scan with JDWP handshake + nonce verification</li>
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

        // Fallback: Attach API (only needed for address=...:0, requires jdk.attach)
        int attachPort = detectPortViaAttachApi();
        if (attachPort > 0) {
            return attachPort;
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
     * Same as {@link #detectJdwpPort()} but returns -1 instead of the default port.
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

        int attachPort = detectPortViaAttachApi();
        if (attachPort > 0) return attachPort;

        int discoveredPort = discoverJdwpPort();
        if (discoveredPort > 0) return discoveredPort;

        return -1; // Don't fall back to default
    }

    /**
     * Use the Attach API to query the JDWP agent for its actual listening address.
     * This is only needed when {@code address=...:0} is used (OS-assigned port).
     *
     * <p>This method is optional — if {@code jdk.attach} is not on the module path,
     * it silently returns -1 and the default port is used instead.</p>
     */
    private static int detectPortViaAttachApi() {
        try {
            String pid = String.valueOf(ProcessHandle.current().pid());
            com.sun.tools.attach.VirtualMachine attachVm = com.sun.tools.attach.VirtualMachine.attach(pid);
            try {
                String address = attachVm.getAgentProperties().getProperty("sun.jdwp.listenerAddress");
                if (address != null) {
                    // Format: "dt_socket:127.0.0.1:50728" or "dt_socket:50728"
                    int lastColon = address.lastIndexOf(':');
                    if (lastColon >= 0) {
                        return Integer.parseInt(address.substring(lastColon + 1).trim());
                    }
                }
            } finally {
                attachVm.detach();
            }
        } catch (Throwable e) {
            // Attach API not available (jdk.attach not loaded) or failed — return -1.
            // Catching Throwable covers NoClassDefFoundError when the module is absent.
        }
        return -1;
    }

    /**
     * Parse the JDWP port from the JVM's command-line input arguments.
     * Returns the port if it is a fixed (non-zero) value, or -1 if not found
     * or if port 0 was specified (meaning OS-assigned).
     */
    private static int detectPortFromArguments() {
        var args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
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
            var args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
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
        VirtualMachine vm = null;
        try {
            vm = jdiConnect(port, PROBE_TIMEOUT_MS);

            List<ReferenceType> types = vm.classesByName("ai.jacc.durableThreads.DurableAgent");
            if (types.isEmpty()) {
                vm.dispose();
                return false; // Not our JVM — DurableAgent not loaded
            }

            Field nonceField = types.get(0).fieldByName("jdwpDiscoveryNonce");
            if (nonceField == null) {
                vm.dispose();
                return false;
            }

            Value val = types.get(0).getValue(nonceField);
            if (val instanceof StringReference sr && expectedNonce.equals(sr.value())) {
                // This is our JVM! Cache the connection for later use.
                cachedVm = vm;
                return true;
            }
            vm.dispose();
            return false;
        } catch (Exception e) {
            if (vm != null) {
                try { vm.dispose(); } catch (Exception ignored) {}
            }
            return false; // Not JDWP, or connection failed
        }
    }

    /**
     * Connect to the local JVM via JDI socket attach.
     *
     * <p>If a cached connection exists (from port auto-discovery), it is returned
     * and the cache is cleared. Otherwise, a new connection is established.</p>
     *
     * @param port the JDWP port
     * @return the VirtualMachine connection
     */
    public static VirtualMachine connect(int port) {
        // Return cached connection from discovery if available
        VirtualMachine cached = cachedVm;
        if (cached != null) {
            cachedVm = null; // Clear cache — caller takes ownership
            return cached;
        }

        return jdiConnect(port, 0);
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
}
