package ai.jacc.durableThreads;

import com.sun.jdi.*;
import ai.jacc.durableThreads.exception.NonEmptyParameterStackException;
import ai.jacc.durableThreads.exception.ThreadFrozenError;
import ai.jacc.durableThreads.internal.*;
import static ai.jacc.durableThreads.internal.FrameFilter.isInfrastructureFrame;
import ai.jacc.durableThreads.snapshot.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Implements the freeze operation: captures a thread's stack, local variables,
 * and reachable object graph into a {@link ThreadSnapshot}.
 *
 * <p>The freeze sequence:</p>
 * <ol>
 *   <li>Thread A (caller) enters freeze() and spawns Thread B</li>
 *   <li>Thread B connects to the JVM via JDI and suspends Thread A</li>
 *   <li>Thread B captures Thread A's state into a ThreadSnapshot</li>
 *   <li>Thread B calls the handler with the snapshot</li>
 *   <li>Thread B terminates Thread A by making it throw ThreadFrozenError</li>
 * </ol>
 */
final class ThreadFreezer {

    /**
     * Maximum time (in milliseconds) that the caller thread will wait for the
     * freeze worker to complete before timing out.
     *
     * <p>Override via the system property {@code durable.freeze.timeout.ms}.</p>
     */
    private static final long FREEZE_TIMEOUT_MS = getFreezeTimeoutMs();

    private static long getFreezeTimeoutMs() {
        String prop = System.getProperty("durable.freeze.timeout.ms");
        if (prop != null) {
            try {
                long val = Long.parseLong(prop.trim());
                if (val > 0) return val;
            } catch (NumberFormatException ignored) {
            }
        }
        return 30_000; // 30 seconds
    }

    private ThreadFreezer() {}

    /**
     * Freeze the calling thread. This method does not return normally in the
     * original thread — only in restored instances.
     */
    static void freeze(Consumer<ThreadSnapshot> handler) {
        freeze(handler, null);
    }

    /**
     * Freeze the calling thread with named heap objects.
     *
     * @param handler receives the captured snapshot
     * @param namedObjects user-assigned names for heap objects (may be null)
     */
    static void freeze(Consumer<ThreadSnapshot> handler, Map<String, Object> namedObjects) {
        // During restore, freeze() is called from the deepest frame's original
        // code. Instead of actually freezing, block on the go-latch until
        // RestoredThread.resume() is called.
        if (ReplayState.isRestoreInProgress()) {
            ReplayState.awaitGoLatch();
            // Clear the flag so subsequent freeze() calls from this thread
            // (re-freeze) go through the normal freeze path.
            ReplayState.setRestoreInProgress(false);
            return; // return normally — user code continues after freeze()
        }

        Thread callerThread = Thread.currentThread();
        Object lock = new Object();
        Throwable[] error = new Throwable[1];

        // Spawn the freeze worker (Thread B)
        Thread worker = new Thread(() -> {
            try {
                performFreeze(callerThread, handler, namedObjects);
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        }, "durable-freeze-worker");
        worker.setDaemon(true);
        worker.start();

        // Wait for the worker to complete. The worker will either:
        // 1. Successfully freeze us (and we'll be terminated via ThreadFrozenError), or
        // 2. Fail (and we'll get the error)
        synchronized (lock) {
            try {
                lock.wait(FREEZE_TIMEOUT_MS);
            } catch (InterruptedException e) {
                // This is the expected freeze termination path.
                // Thread B interrupted us after capturing the snapshot.
                // Throw ThreadFrozenError to cleanly terminate this thread.
                if (FreezeFlag.isFrozen(Thread.currentThread())) {
                    FreezeFlag.clearFrozen(Thread.currentThread());
                    throw new ThreadFrozenError();
                }
                // If we weren't marked as frozen, this was an unexpected interrupt
                Thread.currentThread().interrupt();
                throw new RuntimeException("Freeze interrupted unexpectedly", e);
            }
        }

        // We woke from lock.wait() without InterruptedException.
        // This can happen if lock.notifyAll() in the finally block
        // raced ahead of targetThread.interrupt() in performFreeze.
        // Check the freeze flag — if set, we were successfully frozen.
        if (FreezeFlag.isFrozen(Thread.currentThread())) {
            FreezeFlag.clearFrozen(Thread.currentThread());
            throw new ThreadFrozenError();
        }

        if (error[0] != null) {
            throw new RuntimeException("Freeze failed", error[0]);
        }

        // EXPERIMENT: If this point is reached, the interrupt/flag mechanism
        // failed to terminate the frozen thread. A blockForever() safety net
        // used to live here, but untestable code is a liability. If this
        // throw fires in CI, we need to investigate the termination path.
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
            FreezeFlag.clearFrozen(Thread.currentThread());
            throw new ThreadFrozenError();
        }
        throw new AssertionError(
                "[EXPERIMENT] Frozen thread reached end of freeze() without "
                + "being terminated. The interrupt/flag mechanism failed. "
                + "FreezeFlag.isFrozen=" + FreezeFlag.isFrozen(Thread.currentThread())
                + ", interrupted=" + Thread.currentThread().isInterrupted());
    }

    private static void performFreeze(Thread targetThread, Consumer<ThreadSnapshot> handler,
                                       Map<String, Object> namedObjects) {
        VirtualMachine vm = SelfConnection.getConnection();

        // Find the target thread in JDI
        ThreadReference threadRef = JdiHelper.findThread(vm, targetThread);

        // Suspend the target thread and capture its state.
        ThreadSnapshot snapshot;
        threadRef.suspend();
        try {
            snapshot = captureSnapshot(vm, threadRef, targetThread.getName(), namedObjects);
        } finally {
            threadRef.resume();
        }

        // The target thread is now resumed but blocked in lock.wait() inside
        // freeze(). The snapshot is an immutable copy — Thread A running
        // doesn't affect it. Call the handler with no JDI suspension held,
        // eliminating the risk of deadlock if the handler acquires locks
        // that Thread A holds.
        handler.accept(snapshot);

        // Terminate the target thread AFTER the handler has finished.
        // This is critical: if the target is the main thread, killing it may
        // terminate the JVM. We must ensure the handler has completed before
        // that can happen.
        //
        // Install a per-thread handler to silently swallow the ThreadFrozenError
        // that terminates this specific thread. We scope it narrowly — only this
        // thread, only ThreadFrozenError — so we don't mask real exceptions.
        Thread.UncaughtExceptionHandler previous = targetThread.getUncaughtExceptionHandler();
        targetThread.setUncaughtExceptionHandler((t, e) -> {
            if (e instanceof ThreadFrozenError) {
                return; // expected — silently swallow
            }
            // Not a freeze error — delegate to whatever was there before
            if (previous != null) {
                previous.uncaughtException(t, e);
            } else {
                Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(t, e);
                } else {
                    System.err.println("Exception in thread \"" + t.getName() + "\"");
                    e.printStackTrace();
                }
            }
        });
        FreezeFlag.markFrozen(targetThread);
        targetThread.interrupt();
    }

    /**
     * Check if a frame is a lambda-generated class.
     * Lambda frames are skipped during capture — the synthetic method they
     * delegate to is captured instead.
     */
    private static boolean isLambdaFrame(String className) {
        return className.contains("$$Lambda");
    }

    /**
     * Detect the functional interface implemented by a lambda class.
     * Lambda classes implement exactly one functional interface (plus possibly
     * marker interfaces like Serializable). We return the first interface
     * that isn't a well-known marker.
     */
    private static String detectLambdaInterface(ReferenceType lambdaType) {
        List<InterfaceType> interfaces = null;
        if (lambdaType instanceof com.sun.jdi.ClassType) {
            interfaces = ((com.sun.jdi.ClassType) lambdaType).interfaces();
        }
        if (interfaces != null) {
            for (InterfaceType iface : interfaces) {
                String name = iface.name();
                // Skip marker interfaces
                if ("java.io.Serializable".equals(name)) continue;
                if ("java.lang.Comparable".equals(name)) continue;
                return name; // first functional interface
            }
        }
        return null;
    }

    private static ThreadSnapshot captureSnapshot(VirtualMachine vm, ThreadReference threadRef,
                                                    String threadName,
                                                    Map<String, Object> namedObjects) {
        try {
            List<StackFrame> jdiFrames = threadRef.frames();
            List<FrameSnapshot> frameSnapshots = new ArrayList<>();
            JdiHeapWalker heapWalker = new JdiHeapWalker();

            // Register named objects with the heap walker so they get named
            // in the resulting ObjectSnapshots. Build an inverted identity map
            // (Object → name), then resolve each object to a JDI uniqueID
            // via HeapObjectBridge.
            Map<String, Object> effectiveNames = namedObjects != null
                    ? new HashMap<>(namedObjects) : new HashMap<String, Object>();

            // Auto-name "this" from the immediate caller frame (the topmost
            // user frame, which is the frame that called freeze()). We do this
            // only if the user didn't explicitly name "this".
            if (!effectiveNames.containsKey("this")) {
                autoNameThis(jdiFrames, heapWalker, effectiveNames);
            }

            // Resolve named objects to JDI uniqueIDs via HeapObjectBridge
            if (!effectiveNames.isEmpty()) {
                registerNamedObjectsViaJdi(vm, heapWalker, effectiveNames);
            }

            // Walk frames bottom to top (JDI gives top to bottom).
            // Filter out JDK and library-internal frames — they can't be replayed
            // (not instrumented) and are infrastructure that gets recreated naturally.
            // Lambda frames ($$Lambda) are skipped — the next frame (the synthetic
            // method on the enclosing class) IS instrumented and IS captured.
            String pendingLambdaBridgeInterface = null;

            for (int i = jdiFrames.size() - 1; i >= 0; i--) {
                StackFrame jdiFrame = jdiFrames.get(i);
                Location location = jdiFrame.location();
                Method method = location.method();
                ReferenceType declaringType = method.declaringType();

                String className = declaringType.name().replace('.', '/');
                String methodName = method.name();
                String methodSig = method.signature();

                // Skip JDK/library infrastructure frames (Thread.run, reflection, etc.)
                if (isInfrastructureFrame(className)) continue;

                // Lambda frames: skip but capture the interface for the next frame.
                // Lambda classes aren't instrumented (hidden/anonymous classes bypass
                // ClassFileTransformer), but the synthetic method they delegate to IS
                // on the enclosing class and IS instrumented.
                if (isLambdaFrame(className)) {
                    pendingLambdaBridgeInterface = detectLambdaInterface(declaringType);
                    continue;
                }

                int bcp = (int) location.codeIndex();

                // Compute bytecode hash from ORIGINAL (pre-instrumentation) bytes.
                // This decouples hash validation from instrumentation changes,
                // allowing library upgrades without invalidating frozen snapshots.
                byte[] originalBytecode = InvokeRegistry.getOriginalBytecode(className);
                byte[] hash = originalBytecode != null
                        ? BytecodeHasher.hash(originalBytecode, methodName, methodSig)
                        : new byte[0];

                // Validate operand stack is empty at this call site.
                // Uses INSTRUMENTED bytecode because JDI reports BCPs relative
                // to the instrumented code.
                byte[] instrumentedBytecode = InvokeRegistry.getInstrumentedBytecode(className);
                if (instrumentedBytecode != null) {
                    String stackError = OperandStackChecker.checkStackAtInvoke(
                            instrumentedBytecode, methodName, methodSig, bcp);
                    if (stackError != null) {
                        throw new NonEmptyParameterStackException(stackError);
                    }
                }

                // Compute the invoke index from the BCP — this is used at restore
                // time to set __skip so the replay skips the freeze invoke.
                String invokeKey = InvokeRegistry.key(className, methodName, methodSig);
                int invokeIndex = InvokeRegistry.getInvokeIndex(invokeKey, bcp);

                if (invokeIndex < 0) {
                    throw new RuntimeException(
                            "Cannot determine invoke index for "
                            + className.replace('/', '.') + "." + methodName
                            + " at BCP " + bcp + ". "
                            + "InvokeRegistry has no matching offset. "
                            + "This frame cannot be frozen.");
                }

                // Capture local variables
                List<ai.jacc.durableThreads.snapshot.LocalVariable> locals = captureLocals(jdiFrame, heapWalker);

                // If the previous frame was a lambda bridge, attach the interface
                // name to THIS frame so restore can create a proxy receiver.
                String bridgeInterface = pendingLambdaBridgeInterface;
                pendingLambdaBridgeInterface = null;

                frameSnapshots.add(new FrameSnapshot(
                        className, methodName, methodSig, bcp, invokeIndex, hash,
                        locals, bridgeInterface));
            }

            if (frameSnapshots.isEmpty()) {
                throw new RuntimeException(
                        "Captured 0 user frames for thread '" + threadName + "'. "
                        + "This usually means the wrong thread was suspended "
                        + "(JDI found a thread whose stack contains only infrastructure frames).");
            }

            // Build environment metadata for portable restoration
            List<ObjectSnapshot> heapSnapshots = heapWalker.getSnapshots();
            SnapshotEnvironment environment = buildEnvironment(frameSnapshots, heapSnapshots);

            return new ThreadSnapshot(
                    Instant.now(),
                    threadName,
                    frameSnapshots,
                    heapSnapshots,
                    Durable.VERSION,
                    environment);

        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException("Thread not properly suspended for capture", e);
        }
    }

    /**
     * Auto-name the "this" reference from the topmost user frame (the frame
     * that called freeze()). Only applies to instance methods.
     */
    private static void autoNameThis(List<StackFrame> jdiFrames,
                                      JdiHeapWalker heapWalker,
                                      Map<String, Object> effectiveNames) {
        // Find the topmost user frame (first non-infrastructure frame from top)
        for (StackFrame frame : jdiFrames) {
            String className = frame.location().declaringType().name().replace('.', '/');
            if (isInfrastructureFrame(className)) continue;

            // Found the user frame that called freeze()
            Method method = frame.location().method();
            if (method.isStatic()) return; // no "this" in static methods

            ObjectReference thisRef = frame.thisObject();
            if (thisRef != null) {
                heapWalker.registerNamedObject(thisRef.uniqueID(), "this");
            }
            return;
        }
    }

    /**
     * Resolve named objects from the user's map to JDI uniqueIDs.
     *
     * <p>Strategy: put the named objects into {@link HeapObjectBridge}, then
     * read them back via JDI to get their {@code ObjectReference.uniqueID()}.
     * This is the same mechanism used during restore.</p>
     */
    private static void registerNamedObjectsViaJdi(VirtualMachine vm,
                                                     JdiHeapWalker heapWalker,
                                                     Map<String, Object> namedObjects) {
        // Use negative IDs as keys to avoid colliding with real snapshot IDs
        // that HeapObjectBridge might contain from a prior restore
        long baseKey = -1_000_000;
        Map<Long, String> keyToName = new HashMap<>();

        for (Map.Entry<String, Object> entry : namedObjects.entrySet()) {
            if (entry.getValue() == null) continue;
            long bridgeKey = baseKey--;
            HeapObjectBridge.put(bridgeKey, entry.getValue());
            keyToName.put(bridgeKey, entry.getKey());
        }

        if (keyToName.isEmpty()) return;

        try {
            // Find HeapObjectBridge.objects via JDI
            List<ReferenceType> bridgeTypes = vm.classesByName(
                    "ai.jacc.durableThreads.internal.HeapObjectBridge");
            if (bridgeTypes.isEmpty()) return;

            ReferenceType bridgeType = bridgeTypes.get(0);
            com.sun.jdi.Field objectsField = bridgeType.fieldByName("objects");
            if (objectsField == null) return;

            ObjectReference mapRef = (ObjectReference) bridgeType.getValue(objectsField);
            if (mapRef == null) return;

            // Look up each named object in the ConcurrentHashMap via JDI
            for (Map.Entry<Long, String> ke : keyToName.entrySet()) {
                Value objVal = JdiHelper.getConcurrentHashMapValue(
                        mapRef, String.valueOf(ke.getKey()));
                if (objVal instanceof ObjectReference) {
                    heapWalker.registerNamedObject(
                            ((ObjectReference) objVal).uniqueID(), ke.getValue());
                }
            }
        } finally {
            // Clean up bridge entries
            for (long key : keyToName.keySet()) {
                HeapObjectBridge.remove(key);
            }
        }
    }

    private static List<ai.jacc.durableThreads.snapshot.LocalVariable> captureLocals(StackFrame frame, JdiHeapWalker heapWalker) {
        List<ai.jacc.durableThreads.snapshot.LocalVariable> result = new ArrayList<>();
        Location location = frame.location();
        Method method = location.method();

        List<com.sun.jdi.LocalVariable> jdiLocals;
        try {
            jdiLocals = method.variables();
        } catch (AbsentInformationException e) {
            throw new RuntimeException(
                    "No debug info for method " + method.declaringType().name() + "."
                    + method.name() + ". Classes must be compiled with debug info (-g) "
                    + "for thread freeze to capture local variables.", e);
        }

        // JDI's method.variables() doesn't include the implicit "this" parameter.
        // Explicitly capture "this" for instance methods.
        if (!method.isStatic()) {
            ObjectReference thisRef = frame.thisObject();
            if (thisRef != null) {
                ObjectRef thisObjRef = heapWalker.capture(thisRef);
                result.add(new ai.jacc.durableThreads.snapshot.LocalVariable(
                        0, "this",
                        "L" + method.declaringType().name().replace('.', '/') + ";",
                        thisObjRef));
            }
        }

        for (com.sun.jdi.LocalVariable jdiLocal : jdiLocals) {
            if (!jdiLocal.isVisible(frame)) continue;

            Value value = frame.getValue(jdiLocal);
            ObjectRef ref = heapWalker.capture(value);
            result.add(new ai.jacc.durableThreads.snapshot.LocalVariable(
                    0, // slot index unused — restore matches by name, not slot
                    jdiLocal.name(),
                    jdiLocal.signature(),
                    ref));
        }
        return result;
    }

    /**
     * Build environment metadata from the captured frames and heap objects.
     * Collects all unique class names, resolves their source locations
     * (jar path or directory), and computes bytecode hashes from the
     * original (pre-instrumentation) bytes.
     */
    private static SnapshotEnvironment buildEnvironment(
            List<FrameSnapshot> frames, List<ObjectSnapshot> heapSnapshots) {
        // Collect all unique class names from frames and heap
        Set<String> classNames = new LinkedHashSet<>();
        for (FrameSnapshot frame : frames) {
            classNames.add(frame.className());
        }
        for (ObjectSnapshot obj : heapSnapshots) {
            String cn = obj.className().replace('.', '/');
            if (!cn.contains("$$Lambda") && !cn.startsWith("java/")
                    && !cn.startsWith("javax/") && !cn.startsWith("jdk/")
                    && !cn.startsWith("sun/") && !cn.startsWith("com/sun/")) {
                classNames.add(cn);
            }
        }

        // Build per-class entries with source location and bytecode hash.
        // Optionally bundle the original (pre-instrumentation) class bytes
        // for portable restore — off by default; opt in via
        // Durable.setEmbedClassBytecodes(true). Only instrumented (i.e. user)
        // classes have bytes in InvokeRegistry; JDK classes resolve through
        // the normal loader on restore regardless.
        boolean embedBytes = Durable.isEmbedClassBytecodes();
        List<SnapshotEnvironment.ClassEntry> entries = new ArrayList<>();
        for (String className : classNames) {
            byte[] originalBytes = InvokeRegistry.getOriginalBytecode(className);
            byte[] hash = originalBytes != null
                    ? BytecodeHasher.hashClass(originalBytes)
                    : new byte[0];

            String sourceLocation = resolveSourceLocation(className);
            byte[] embedded = embedBytes ? originalBytes : null;
            entries.add(new SnapshotEnvironment.ClassEntry(
                    className, sourceLocation, hash, embedded));
        }

        return new SnapshotEnvironment(
                Durable.VERSION,
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.class.path", ""),
                System.getProperty("os.name", "unknown"),
                entries);
    }

    /**
     * Resolve the source location (jar path or directory) for a class.
     */
    private static String resolveSourceLocation(String internalClassName) {
        try {
            String dotName = internalClassName.replace('/', '.');
            Class<?> clazz = Class.forName(dotName);
            java.security.CodeSource cs = clazz.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                return cs.getLocation().toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Thread-identity flag to signal that a thread has been frozen and should terminate.
     *
     * <p>Uses {@code Thread} references with identity semantics (not thread IDs)
     * to avoid false positives from JVM thread ID reuse. Stale entries are
     * automatically eligible for GC when the thread is collected.</p>
     */
    static final class FreezeFlag {
        private static final Set<Thread> frozenThreads =
                Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

        static void markFrozen(Thread t) {
            frozenThreads.add(t);
        }

        /**
         * Check if a thread has been marked as frozen. Non-destructive — the flag
         * remains set so multiple checks all see the frozen state. Call
         * {@link #clearFrozen(Thread)} when the thread is about to terminate.
         */
        static boolean isFrozen(Thread t) {
            return frozenThreads.contains(t);
        }

        /**
         * Remove the frozen flag for a thread. Called just before throwing
         * {@link ThreadFrozenError} to prevent stale entries from accumulating.
         */
        static void clearFrozen(Thread t) {
            frozenThreads.remove(t);
        }
    }
}
