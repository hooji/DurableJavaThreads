package ai.jacc.durableThreads.internal;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

import java.util.Map;

/**
 * Utilities for connecting to the local JVM via JDI (Java Debug Interface).
 */
public final class JdiHelper {

    private JdiHelper() {}

    /**
     * Auto-detect the JDWP port that this JVM is actually listening on.
     *
     * <p>When the JVM is started with {@code address=127.0.0.1:0}, the OS
     * assigns a random port. The command-line argument still says "0", so
     * we use the Attach API to read the actual listening address from the
     * JDWP agent's properties ({@code sun.jdwp.listenerAddress}).</p>
     *
     * @return the JDWP port, or -1 if not found
     */
    public static int detectJdwpPort() {
        // Check the agent's cached port first (detected eagerly at startup)
        int cached = ai.jacc.durableThreads.DurableAgent.getCachedJdwpPort();
        if (cached > 0) {
            return cached;
        }

        // First try parsing command-line arguments (fast, works for fixed ports)
        int argPort = detectPortFromArguments();
        if (argPort > 0) {
            return argPort;
        }

        // If port was 0 (OS-assigned), use the Attach API to find the actual port
        int attachPort = detectPortViaAttachApi();
        if (attachPort > 0) {
            return attachPort;
        }

        return -1;
    }

    /**
     * Use the Attach API to query the JDWP agent for its actual listening address.
     * This is the only reliable way to get the port when {@code address=...:0} is used.
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
        } catch (Exception e) {
            // Attach API not available or failed — return -1
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

    /**
     * Connect to the local JVM via JDI socket attach.
     *
     * @param port the JDWP port
     * @return the VirtualMachine connection
     */
    public static VirtualMachine connect(int port) {
        try {
            AttachingConnector connector = findSocketAttachConnector();
            Map<String, Connector.Argument> arguments = connector.defaultArguments();
            arguments.get("hostname").setValue("127.0.0.1");
            arguments.get("port").setValue(String.valueOf(port));
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
