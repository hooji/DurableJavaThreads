package ai.jacc.durableThreads.internal;

import com.sun.jdi.VirtualMachine;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates self-attach to this JVM's JDWP server. Holds the singleton
 * {@link VirtualMachine} for the process lifetime.
 *
 * <p>Port resolution order:</p>
 * <ol>
 *   <li>Agent's cached port (detected eagerly at startup)</li>
 *   <li>System property {@code durable.jdwp.port}</li>
 *   <li>Parsed from JVM command-line arguments</li>
 *   <li>Platform-specific listening port enumeration with nonce verification</li>
 *   <li>Ephemeral port scan with nonce verification (fallback)</li>
 *   <li>Default: {@link #DEFAULT_JDWP_PORT}</li>
 * </ol>
 *
 * <p>The singleton is never released. Keeping a strong static reference
 * prevents GC from closing the underlying socket — without this, GC during
 * shutdown closes the socket, JDWP sees a disconnect, re-listens, and prints
 * a confusing "Listening for transport..." message on stderr.</p>
 */
public final class SelfConnection {

    /**
     * Default JDWP port. Used only when no port has been detected by any
     * other mechanism and JDWP is believed to be enabled.
     */
    public static final int DEFAULT_JDWP_PORT = 44892;

    /**
     * Number of ports to scan in each direction from a probe port when
     * falling back to ephemeral-range discovery.
     */
    private static final int SCAN_RANGE = 50;

    /**
     * The single JDI connection to this JVM. Set once and never replaced.
     */
    private static volatile VirtualMachine connection;

    private static final Object JDI_CONNECT_LOCK = new Object();

    private SelfConnection() {}

    /**
     * Get the JDI connection to this JVM, creating it on first call.
     *
     * @return the VirtualMachine connection (never null)
     * @throws RuntimeException if JDWP is not enabled or connection fails
     */
    public static VirtualMachine getConnection() {
        VirtualMachine vm = connection;
        if (vm != null) return vm;

        synchronized (JDI_CONNECT_LOCK) {
            vm = connection;
            if (vm != null) return vm;

            // Port discovery may establish a connection as a side-effect
            // (successful nonce-verified probes populate 'connection').
            // Check again after detection before attempting a redundant connect.
            int port = detectJdwpPort();
            vm = connection;
            if (vm != null) return vm;

            if (port < 0) {
                throw new RuntimeException(
                        "JDWP not enabled. Start with: "
                        + "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005");
            }
            vm = PortProber.jdiConnect(port, 0);
            connection = vm;
            return vm;
        }
    }

    /**
     * Detect the JDWP port this JVM is listening on. Runs the full fallback
     * chain, ending with {@link #DEFAULT_JDWP_PORT} as a last resort.
     */
    public static int detectJdwpPort() {
        int cached = ai.jacc.durableThreads.DurableAgent.getCachedJdwpPort();
        if (cached > 0) return cached;

        String propPort = System.getProperty("durable.jdwp.port");
        if (propPort != null) {
            try {
                int port = Integer.parseInt(propPort.trim());
                if (port > 0) return port;
            } catch (NumberFormatException ignored) {
            }
        }

        int argPort = PortEnumerator.detectPortFromArguments();
        if (argPort > 0) {
            ai.jacc.durableThreads.DurableAgent.cacheJdwpPort(argPort);
            return argPort;
        }

        int listenPort = detectPortFromListeningSockets();
        if (listenPort > 0) {
            ai.jacc.durableThreads.DurableAgent.cacheJdwpPort(listenPort);
            return listenPort;
        }

        int discoveredPort = discoverJdwpPort();
        if (discoveredPort > 0) {
            ai.jacc.durableThreads.DurableAgent.cacheJdwpPort(discoveredPort);
            return discoveredPort;
        }

        return DEFAULT_JDWP_PORT;
    }

    /**
     * Like {@link #detectJdwpPort()} but returns -1 instead of the default
     * port, and skips JDI-based discovery (unsafe during premain — the JVM
     * isn't fully initialized and JDI connect to self would deadlock).
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

        int argPort = PortEnumerator.detectPortFromArguments();
        if (argPort > 0) return argPort;

        // Do NOT call detectPortFromListeningSockets() or discoverJdwpPort()
        // here — they use JDI connect which deadlocks during premain.
        return -1;
    }

    /**
     * Enumerate listening TCP ports via the OS and verify each via JDI nonce.
     * Returns the verified port, or -1 if not found.
     */
    private static int detectPortFromListeningSockets() {
        if (!PortEnumerator.isJdwpOnCommandLine()) {
            return -1;
        }

        String expectedNonce = ai.jacc.durableThreads.DurableAgent.jdwpDiscoveryNonce;
        if (expectedNonce == null || expectedNonce.isEmpty()) {
            return -1;
        }

        List<Integer> listeningPorts = PortEnumerator.getListeningPorts();
        return probeAndRemember(listeningPorts, expectedNonce);
    }

    /**
     * Fallback discovery: scan ports near a freshly allocated ephemeral port.
     *
     * <p>The OS assigns ephemeral ports roughly sequentially, so JDWP's port
     * (allocated earlier at JVM startup) is usually near a newly-allocated
     * ephemeral port. Scan {@code probePort ± SCAN_RANGE} and verify via JDI
     * nonce.</p>
     */
    private static int discoverJdwpPort() {
        if (!PortEnumerator.isJdwpOnCommandLine()) {
            return -1;
        }

        String expectedNonce = ai.jacc.durableThreads.DurableAgent.jdwpDiscoveryNonce;
        if (expectedNonce == null || expectedNonce.isEmpty()) {
            return -1;
        }

        int probePort;
        try (ServerSocket ss = new ServerSocket(0)) {
            probePort = ss.getLocalPort();
        } catch (IOException e) {
            return -1;
        }

        // Build candidate list: ports below the probe (most likely), then above.
        // NOTE: Do NOT pre-probe with raw TCP or JDWP handshake — JDWP re-listens
        // on a NEW port after each debugger disconnect, so probes "consume" the
        // port.
        List<Integer> candidates = new ArrayList<>();
        for (int port = probePort - 1; port >= Math.max(1, probePort - SCAN_RANGE); port--) {
            candidates.add(port);
        }
        for (int port = probePort; port <= Math.min(65535, probePort + SCAN_RANGE); port++) {
            candidates.add(port);
        }

        return probeAndRemember(candidates, expectedNonce);
    }

    /**
     * Run a parallel probe over the given candidates. On success, cache the
     * resulting {@link VirtualMachine} as the singleton connection so the
     * caller does not re-attach.
     */
    private static int probeAndRemember(List<Integer> candidates, String expectedNonce) {
        PortProber.Result r = PortProber.probePortsInParallel(candidates, expectedNonce);
        if (r == null) return -1;
        connection = r.vm();
        return r.port();
    }
}
