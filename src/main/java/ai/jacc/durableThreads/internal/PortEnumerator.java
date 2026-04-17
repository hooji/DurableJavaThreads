package ai.jacc.durableThreads.internal;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Platform-specific enumeration of TCP listening ports for this process,
 * plus command-line argument parsing helpers.
 *
 * <p>Used by the self-attach subsystem to build a candidate list of ports
 * that might be the in-process JDWP server. A subsequent verification step
 * (see {@link PortProber}) attaches to each candidate and confirms identity
 * via a nonce written by the agent at {@code premain} time.</p>
 */
public final class PortEnumerator {

    private PortEnumerator() {}

    /**
     * Get all TCP ports this process is listening on, using platform-specific
     * mechanisms. Returns an empty list on unsupported platforms.
     */
    public static List<Integer> getListeningPorts() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            return getListeningPortsLinux();
        } else if (os.contains("mac") || os.contains("darwin")) {
            return getListeningPortsMacOS();
        } else if (os.contains("win")) {
            return getListeningPortsWindows();
        }
        return Collections.emptyList();
    }

    /**
     * Linux: parse {@code /proc/net/tcp} and {@code /proc/net/tcp6}.
     * Each line: {@code sl local_address rem_address st ...}
     * where local_address is {@code hex_ip:hex_port} and st {@code 0A} = LISTEN.
     */
    static List<Integer> getListeningPortsLinux() {
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
    static List<Integer> getListeningPortsMacOS() {
        List<Integer> ports = new ArrayList<>();
        long pid = getPid();
        try {
            Process proc = new ProcessBuilder("lsof", "-iTCP", "-sTCP:LISTEN", "-nP",
                    "-p", String.valueOf(pid)).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
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
    static List<Integer> getListeningPortsWindows() {
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
                    if (fields.length < 5 || !fields[4].equals(pid)) continue;
                    String localAddr = fields[1];
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
    public static int detectPortFromArguments() {
        List<String> args;
        try {
            args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Throwable t) {
            // ManagementFactory can fail on some JDK versions (10-14) with
            // NoClassDefFoundError: Could not initialize class PlatformMBeanFinder.
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

    /**
     * Check if JDWP appears on the JVM command line. Avoids expensive port
     * scanning when JDWP is not configured.
     */
    public static boolean isJdwpOnCommandLine() {
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

    /**
     * Get the current process ID.
     *
     * <p>Tries ProcessHandle.current().pid() first (JDK 9+), falling back to
     * ManagementFactory.getRuntimeMXBean().getName() for JDK 8. The fallback
     * can fail on JDK 10-14 with ManagementFactory initialization errors.</p>
     */
    public static long getPid() {
        try {
            Class<?> phClass = Class.forName("java.lang.ProcessHandle");
            Object current = phClass.getMethod("current").invoke(null);
            return (long) phClass.getMethod("pid").invoke(current);
        } catch (Throwable ignored) {
            // JDK 8 or reflection failure — fall through
        }

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

    private static String extractValue(String arg, String key) {
        int idx = arg.indexOf(key + "=");
        if (idx < 0) return null;
        int start = idx + key.length() + 1;
        int end = arg.indexOf(',', start);
        return end < 0 ? arg.substring(start) : arg.substring(start, end);
    }
}
