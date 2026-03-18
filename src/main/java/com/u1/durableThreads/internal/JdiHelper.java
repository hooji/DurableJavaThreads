package com.u1.durableThreads.internal;

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
     * Auto-detect the JDWP port from the current JVM's input arguments.
     *
     * @return the JDWP port, or -1 if not found
     */
    public static int detectJdwpPort() {
        var args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : args) {
            if (arg.contains("jdwp") && arg.contains("address=")) {
                // Parse address=*:5005 or address=5005 or address=host:5005
                String addressPart = extractValue(arg, "address");
                if (addressPart != null) {
                    // Handle *:port and host:port formats
                    int colonIdx = addressPart.lastIndexOf(':');
                    String portStr = colonIdx >= 0 ? addressPart.substring(colonIdx + 1) : addressPart;
                    try {
                        return Integer.parseInt(portStr.trim());
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
            arguments.get("hostname").setValue("localhost");
            arguments.get("port").setValue(String.valueOf(port));
            return connector.attach(arguments);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to JVM via JDI on port " + port, e);
        }
    }

    /**
     * Find the JDI ThreadReference corresponding to a Java thread.
     */
    public static ThreadReference findThread(VirtualMachine vm, Thread javaThread) {
        long threadId = javaThread.threadId();
        for (ThreadReference tr : vm.allThreads()) {
            // Match by thread ID
            if (tr.uniqueID() == threadId) {
                return tr;
            }
        }
        // Fallback: match by name
        String name = javaThread.getName();
        for (ThreadReference tr : vm.allThreads()) {
            if (tr.name().equals(name)) {
                return tr;
            }
        }
        throw new RuntimeException("Could not find JDI ThreadReference for thread: " + javaThread.getName());
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
