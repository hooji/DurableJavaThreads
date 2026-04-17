package ai.jacc.durableThreads.internal;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.Field;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Probes TCP candidate ports for a JDWP server belonging to this JVM.
 *
 * <p>Each probe performs a full JDI socket attach and reads a nonce static
 * field from the {@code DurableAgent} class in the target. A matching nonce
 * means the port really is this JVM's JDWP server.</p>
 *
 * <p>Probes run in parallel (one thread per candidate) with a hard timeout
 * via {@code Future.get}, because JDI's built-in timeout does not reliably
 * abort hung JDWP handshakes against non-JDWP services that accept TCP but
 * never complete the handshake.</p>
 */
public final class PortProber {

    /** Socket connect + handshake timeout for a single probe (ms). */
    public static final int PROBE_TIMEOUT_MS = 200;

    private PortProber() {}

    /**
     * The outcome of a successful probe: the port number and the live
     * {@link VirtualMachine} produced by the verifying attach. Callers that
     * plan to reuse the connection should hold onto the {@code vm}; otherwise
     * call {@link VirtualMachine#dispose()}.
     */
    public static final class Result {
        private final int port;
        private final VirtualMachine vm;

        public Result(int port, VirtualMachine vm) {
            this.port = port;
            this.vm = vm;
        }

        public int port() { return port; }
        public VirtualMachine vm() { return vm; }
    }

    /**
     * Probe a list of candidate ports in parallel for a JDWP connection
     * belonging to this JVM (verified via nonce).
     *
     * @param candidates    distinct port numbers to probe
     * @param expectedNonce nonce to verify via {@code DurableAgent.jdwpDiscoveryNonce}
     * @return the verified port + VM, or {@code null} if no candidate matched
     */
    public static Result probePortsInParallel(List<Integer> candidates, String expectedNonce) {
        if (candidates.isEmpty()) return null;

        ExecutorService probePool = Executors.newFixedThreadPool(candidates.size(), r -> {
            Thread t = new Thread(r, "jdwp-probe");
            t.setDaemon(true);
            return t;
        });
        // Separate single-purpose pool for per-probe hard timeouts. Each probe
        // submits its jdiConnect to this pool and waits with Future.get(timeout),
        // ensuring hung JDWP handshakes on non-JDWP ports are killed reliably.
        ExecutorService connectPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "jdwp-connect");
            t.setDaemon(true);
            return t;
        });
        CompletionService<Result> cs = new ExecutorCompletionService<>(probePool);

        try {
            for (int port : candidates) {
                final int candidate = port;
                cs.submit(() -> {
                    try {
                        Future<VirtualMachine> connectFuture = connectPool.submit(
                                () -> jdiConnect(candidate, PROBE_TIMEOUT_MS));
                        VirtualMachine vm;
                        try {
                            vm = connectFuture.get(PROBE_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException e) {
                            connectFuture.cancel(true);
                            return null;
                        }
                        List<ReferenceType> types = vm.classesByName(
                                "ai.jacc.durableThreads.DurableAgent");
                        if (types.isEmpty()) { vm.dispose(); return null; }
                        Field nonceField = types.get(0).fieldByName("jdwpDiscoveryNonce");
                        if (nonceField == null) { vm.dispose(); return null; }
                        Value val = types.get(0).getValue(nonceField);
                        if (val instanceof StringReference
                                && expectedNonce.equals(((StringReference) val).value())) {
                            return new Result(candidate, vm);
                        }
                        vm.dispose();
                    } catch (Exception e) {
                        // Connection failed, not JDWP, etc. — expected for most ports
                    }
                    return null;
                });
            }

            long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS + 500;
            for (int i = 0; i < candidates.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    Future<Result> future = cs.poll(remaining, TimeUnit.MILLISECONDS);
                    if (future == null) break;
                    Result r = future.get();
                    if (r != null) return r;
                } catch (ExecutionException | InterruptedException e) {
                    // continue collecting
                }
            }
            return null;
        } finally {
            probePool.shutdownNow();
            connectPool.shutdownNow();
        }
    }

    /**
     * Low-level JDI socket attach with optional timeout.
     *
     * @param port      JDWP port
     * @param timeoutMs connection timeout in ms, or 0 for no timeout
     */
    public static VirtualMachine jdiConnect(int port, int timeoutMs) {
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

    private static AttachingConnector findSocketAttachConnector() {
        for (AttachingConnector connector : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (connector.name().equals("com.sun.jdi.SocketAttach")) {
                return connector;
            }
        }
        throw new RuntimeException("SocketAttach connector not found. Is JDI available?");
    }
}
