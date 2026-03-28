package ai.jacc.durableThreads;

import com.sun.jdi.*;
import ai.jacc.durableThreads.internal.*;
import ai.jacc.durableThreads.snapshot.*;
import static ai.jacc.durableThreads.internal.FrameFilter.isInfrastructureFrame;

import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Implements the restore operation: validates a snapshot, rebuilds the heap,
 * and creates a thread that replays the call stack to resume execution from
 * the freeze point.
 */
final class ThreadRestorer {

    private ThreadRestorer() {}

    /** Pre-resolved local variable entry for batch setValue. */
    static final class LocalEntry {
        private final com.sun.jdi.LocalVariable jdiLocal;
        private final Value jdiValue;
        private final boolean isNull;

        LocalEntry(com.sun.jdi.LocalVariable jdiLocal, Value jdiValue, boolean isNull) {
            this.jdiLocal = jdiLocal;
            this.jdiValue = jdiValue;
            this.isNull = isNull;
        }

        public com.sun.jdi.LocalVariable jdiLocal() { return jdiLocal; }
        public Value jdiValue() { return jdiValue; }
        public boolean isNull() { return isNull; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LocalEntry)) return false;
            LocalEntry that = (LocalEntry) o;
            return isNull == that.isNull
                    && Objects.equals(jdiLocal, that.jdiLocal)
                    && Objects.equals(jdiValue, that.jdiValue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jdiLocal, jdiValue, isNull);
        }

        @Override
        public String toString() {
            return "LocalEntry[jdiLocal=" + jdiLocal + ", jdiValue=" + jdiValue
                    + ", isNull=" + isNull + "]";
        }
    }

    /**
     * Restore a frozen thread from a snapshot.
     *
     * @param snapshot the captured thread state
     * @return a Thread (not yet started) that will resume from the freeze point
     */
    static RestoredThread restore(ThreadSnapshot snapshot) {
        return restore(snapshot, null);
    }

    static RestoredThread restore(ThreadSnapshot snapshot, Map<String, Object> namedReplacements) {
        // Step 0: Sanity-check the snapshot
        if (snapshot.frameCount() == 0) {
            throw new IllegalArgumentException(
                    "Cannot restore a snapshot with 0 frames. "
                    + "This usually means the freeze captured the wrong thread "
                    + "(see JdiHelper.findThread).");
        }

        // Step 1: Force-load all classes and validate hashes
        SnapshotValidator.ensureClassesLoaded(snapshot);
        SnapshotValidator.validateBytecodeHashes(snapshot);
        SnapshotValidator.validateClassStructureHashes(snapshot);

        // Step 3: Rebuild the heap
        HeapRestorer heapRestorer = new HeapRestorer();
        Map<Long, Object> restoredHeap = heapRestorer.restoreAll(snapshot.heap(), namedReplacements);

        // Step 3b: Populate the heap object bridge for JDI access
        HeapObjectBridge.clear();
        for (Map.Entry<Long, Object> entry : restoredHeap.entrySet()) {
            HeapObjectBridge.put(entry.getKey(), entry.getValue());
        }

        // Step 4: Build replay state (now InvokeRegistry has the data)
        int[] resumeIndices = computeResumeIndices(snapshot);

        // Step 4b: Pre-resolve receiver ("this") for each frame
        Object[] frameReceivers = computeFrameReceivers(snapshot, heapRestorer);

        // Step 5: Create the replay thread.
        // Use the original thread's base name (strip any prior "-restored-*" suffix)
        // with a short unique ID to avoid name accumulation across freeze/restore cycles.
        String baseName = snapshot.threadName();
        int restoreIdx = baseName.indexOf("-restored-");
        if (restoreIdx >= 0) {
            baseName = baseName.substring(0, restoreIdx);
        }
        String threadName = baseName + "-restored-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        Thread replayThread = new Thread(() -> {
            ReplayState.activateWithLatch(resumeIndices, frameReceivers);
            // Set the restore-in-progress flag so that when the deepest frame's
            // stub deactivates replay and jumps to the original freeze() call,
            // freeze() detects it's in restore mode and blocks on the go-latch.
            ReplayState.setRestoreInProgress(true);

            try {
                FrameSnapshot bottomFrame = snapshot.bottomFrame();
                invokeBottomFrame(bottomFrame, restoredHeap, heapRestorer, snapshot);
            } catch (ai.jacc.durableThreads.exception.ThreadFrozenError e) {
                // Expected — thread was re-frozen
            } catch (Exception e) {
                if (ReflectionHelpers.hasCause(e, ai.jacc.durableThreads.exception.ThreadFrozenError.class)) {
                    // Expected — thread was re-frozen via reflected call
                } else {
                    throw new RuntimeException("Failed to replay thread from snapshot", e);
                }
            }
        }, threadName);

        // Step 6: Start the replay thread and run JDI restore synchronously.
        // The replay thread ends up blocked inside freeze() on the go-latch.
        // The JDI worker sets locals in all frames. The go-latch is captured
        // into RestoredThread for the caller to release.
        replayThread.start();
        final Throwable[] jdiError = {null};
        Thread jdiWorker = new Thread(() -> {
            try {
                runJdiRestore(threadName, snapshot, restoredHeap, heapRestorer);
            } catch (Throwable t) {
                jdiError[0] = t;
            }
        }, "durable-restore-jdi-worker");
        jdiWorker.setDaemon(true);
        jdiWorker.start();

        try {
            jdiWorker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for JDI restore to complete", e);
        }

        if (jdiError[0] != null) {
            throw new RuntimeException("JDI restore failed", jdiError[0]);
        }

        // The replay thread is parked inside freeze() on the goLatch.
        // All JDI work is done. RestoredThread.resume() will count it down,
        // causing freeze() to return normally and user code to continue.
        return new RestoredThread(replayThread, ReplayState.getGoLatch());
    }


    /**
     * Read the pre-computed invoke indices directly from the snapshot.
     * These were computed at freeze time when the exact BCP→index mapping
     * was available from the freezing JVM's InvokeRegistry.
     */
    private static int[] computeResumeIndices(ThreadSnapshot snapshot) {
        int[] indices = new int[snapshot.frameCount()];

        for (int i = 0; i < snapshot.frameCount(); i++) {
            indices[i] = Math.max(0, snapshot.frames().get(i).invokeIndex());
        }

        return indices;
    }

    /**
     * Pre-resolve the receiver ("this") for each frame from the snapshot.
     *
     * <p>Resume stubs need the correct receiver when re-invoking virtual/interface
     * methods during replay. Without this, they would create an uninitialized
     * dummy instance whose fields are all null/0 — which corrupts execution
     * after the freeze point where the original code accesses {@code this}.</p>
     *
     * @return array of receivers indexed by frame (bottom=0), null entries for
     *         static methods or frames where "this" was not captured
     */
    private static Object[] computeFrameReceivers(ThreadSnapshot snapshot,
                                                   HeapRestorer heapRestorer) {
        Object[] receivers = new Object[snapshot.frameCount()];
        for (int i = 0; i < snapshot.frameCount(); i++) {
            FrameSnapshot frame = snapshot.frames().get(i);

            // If this frame was entered through a lambda bridge, create a
            // dynamic proxy as the receiver. The caller frame's resume stub
            // uses this proxy for its interface invoke (e.g., processor.process()).
            // The proxy delegates to the synthetic method whose prologue takes over.
            if (frame.lambdaBridgeInterface() != null) {
                Object proxy = createLambdaBridgeProxy(frame);
                if (proxy != null) {
                    receivers[i] = proxy;
                    continue;
                }
            }

            // Normal case: use "this" from slot 0
            for (ai.jacc.durableThreads.snapshot.LocalVariable local : frame.locals()) {
                if (local.slot() == 0 && "this".equals(local.name())) {
                    receivers[i] = heapRestorer.resolve(local.value());
                    break;
                }
            }
        }
        return receivers;
    }

    /**
     * Create a dynamic proxy that implements the lambda's functional interface
     * and delegates to the target synthetic method.
     *
     * <p>During replay, the caller's resume stub pushes this proxy as the
     * receiver for the interface invoke. The proxy forwards the call to the
     * synthetic method whose replay prologue handles the rest. All captured
     * variables (which become parameters or the enclosing {@code this} of the
     * synthetic method) are set later by JDI in the single-pass restore.</p>
     */
    private static Object createLambdaBridgeProxy(FrameSnapshot syntheticFrame) {
        try {
            Class<?> iface = Class.forName(syntheticFrame.lambdaBridgeInterface());
            String targetClassName = syntheticFrame.className().replace('/', '.');
            Class<?> targetClass = Class.forName(targetClassName);
            String targetMethodName = syntheticFrame.methodName();

            // Find the synthetic method on the enclosing class
            java.lang.reflect.Method targetMethod = null;
            for (java.lang.reflect.Method m : targetClass.getDeclaredMethods()) {
                if (m.getName().equals(targetMethodName)) {
                    targetMethod = m;
                    break;
                }
            }
            if (targetMethod == null) return null;
            targetMethod.setAccessible(true);

            final java.lang.reflect.Method target = targetMethod;
            final boolean isStatic = java.lang.reflect.Modifier.isStatic(target.getModifiers());

            return java.lang.reflect.Proxy.newProxyInstance(
                    targetClass.getClassLoader(),
                    new Class<?>[]{iface},
                    (proxy, method, args) -> {
                        // Delegate to the synthetic method. Its replay prologue
                        // takes over immediately. Args are dummy values during
                        // replay — JDI sets ALL locals (including parameters
                        // from captured variables) in the single-pass restore.
                        Object[] callArgs = args != null ? args : new Object[0];
                        if (isStatic) {
                            // Non-capturing or local-capturing lambda:
                            // static lambda$doWork$0(capturedVars..., params...)
                            // Pad args if the method has more params than the
                            // interface method (captured vars are leading params)
                            int methodParamCount = target.getParameterTypes().length;
                            if (callArgs.length < methodParamCount) {
                                Object[] padded = new Object[methodParamCount];
                                System.arraycopy(callArgs, 0, padded,
                                        methodParamCount - callArgs.length, callArgs.length);
                                callArgs = padded;
                            }
                            return target.invoke(null, callArgs);
                        } else {
                            // this-capturing lambda or method reference:
                            // instance method on the enclosing class
                            Object receiver = ReplayState.dummyInstance(targetClassName);
                            return target.invoke(receiver, callArgs);
                        }
                    });
        } catch (Exception e) {
            System.err.println("[DurableThreads] Warning: failed to create lambda bridge proxy"
                    + " for " + syntheticFrame.className() + "." + syntheticFrame.methodName()
                    + ": " + e.getMessage());
            return null;
        }
    }

    private static void invokeBottomFrame(FrameSnapshot bottomFrame,
                                          Map<Long, Object> restoredHeap,
                                          HeapRestorer heapRestorer,
                                          ThreadSnapshot snapshot) throws Exception {
        String className = bottomFrame.className().replace('/', '.');
        Class<?> clazz = Class.forName(className);

        java.lang.reflect.Method method = ReflectionHelpers.findMethod(clazz, bottomFrame.methodName(),
                bottomFrame.methodSignature());
        if (method == null) {
            throw new RuntimeException("Cannot find method: " + className + "."
                    + bottomFrame.methodName() + bottomFrame.methodSignature());
        }

        method.setAccessible(true);

        Object[] args = ReflectionHelpers.createDummyArgs(method.getParameterTypes());
        Object receiver = null;

        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            receiver = ReflectionHelpers.findOrCreateReceiver(clazz, bottomFrame, restoredHeap, heapRestorer);
        }

        method.invoke(receiver, args);
    }

    /**
     * Run single-pass JDI restore synchronously.
     *
     * <p>Wait for replay thread to block inside {@code freeze()} at
     * {@code awaitGoLatch()}. All frames are in their original code sections
     * (resume stubs jump to BEFORE_INVOKE labels), so all local variables
     * are in scope. Set ALL locals in ALL frames in one pass. The thread
     * remains blocked on the go-latch until {@link RestoredThread#resume()}
     * is called.</p>
     */
    private static void runJdiRestore(String threadName,
                                      ThreadSnapshot snapshot,
                                      Map<Long, Object> restoredHeap,
                                      HeapRestorer heapRestorer) {
        try {
            VirtualMachine vm = JdiHelper.getConnection();

            // Wait for thread to reach awaitGoLatch() inside freeze().
            // The deepest frame's resume stub deactivated replay and jumped
            // to BEFORE_INVOKE, which called freeze(). freeze() detected
            // restoreInProgress and is now blocked on the go-latch.
            ThreadReference tr = waitForThreadAtMethod(vm, threadName,
                    "awaitGoLatch", "ReplayState", 30_000);
            if (tr == null) {
                throw new RuntimeException(
                        "Timeout waiting for replay thread '" + threadName
                        + "' to reach awaitGoLatch(). The thread may have failed "
                        + "during replay prologue execution.");
            }

            // Pre-load all classes referenced by snapshot locals
            tr.suspend();
            try {
                preloadSnapshotClasses(vm, tr, snapshot);
            } finally {
                tr.resume();
            }

            // Set ALL locals in ALL frames in a single pass.
            // All frames are in their original code sections (resume stubs
            // jumped to BEFORE_INVOKE labels), so all locals are in scope.
            tr.suspend();
            try {
                setLocalsViaJdi(vm, tr, snapshot, restoredHeap, heapRestorer);
            } finally {
                tr.resume();
            }

            // Clean up the heap bridge
            HeapObjectBridge.clear();
        } catch (Exception e) {
            System.err.println("[DurableThreads] JDI restore failed: " + e.getMessage());
            e.printStackTrace(System.err);
            ReplayState.signalRestoreError(
                    "JDI restore worker failed: " + e.getMessage());
            // Release the go-latch so the replay thread doesn't hang forever
            CountDownLatch latch = ReplayState.getGoLatch();
            if (latch != null) latch.countDown();
            throw new RuntimeException("JDI restore failed: " + e.getMessage(), e);
        }
    }

    /**
     * Poll JDI until the named thread is WAITING inside the specified method.
     *
     * <p>Simply checking for WAITING status is racy — the thread could be
     * WAITING in some other location (e.g., class loading, lock acquisition).
     * We verify the thread is actually blocked inside the target method by
     * inspecting its top stack frames.</p>
     *
     * @param targetMethodName the method name to look for (e.g. "awaitGoLatch")
     * @param targetClassName  partial class name match (e.g. "ReplayState")
     */
    private static ThreadReference waitForThreadAtMethod(VirtualMachine vm, String threadName,
                                                          String targetMethodName,
                                                          String targetClassName,
                                                          long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (ThreadReference tr : vm.allThreads()) {
                if (tr.name().equals(threadName)) {
                    int status = tr.status();
                    if (status == ThreadReference.THREAD_STATUS_WAIT
                            || status == ThreadReference.THREAD_STATUS_SLEEPING) {
                        if (isAtMethod(tr, targetMethodName, targetClassName)) {
                            return tr;
                        }
                    }
                }
            }
            try {
                Thread.sleep(10); // poll every 10ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Check if a thread is blocked inside the specified method by inspecting
     * its top stack frames. Looks for the target method in the call stack,
     * allowing for CountDownLatch internals above it.
     */
    private static boolean isAtMethod(ThreadReference tr, String targetMethodName,
                                       String targetClassName) {
        try {
            tr.suspend();
            try {
                List<StackFrame> frames = tr.frames(0, Math.min(10, tr.frameCount()));
                // Look for the target method in the stack. It may have
                // CountDownLatch.await / AbstractQueuedSynchronizer frames above it.
                for (StackFrame frame : frames) {
                    String methodName = frame.location().method().name();
                    String className = frame.location().declaringType().name();
                    if (methodName.equals(targetMethodName)
                            && className.contains(targetClassName)) {
                        return true;
                    }
                }
            } finally {
                tr.resume();
            }
        } catch (IncompatibleThreadStateException e) {
            // Can't read frames — not yet in proper state
        }
        return false;
    }

    /**
     * Set local variables in each frame via JDI (single-pass).
     *
     * <p>The JDI call stack looks like:</p>
     * <pre>
     * JDI frame 0:   awaitGoLatch() [or CountDownLatch internals]
     * JDI frame 1:   freeze() [detecting restore mode]
     * JDI frame 2:   deepest user method (in original code section)
     * JDI frame 3:   next method up (in original code section)
     * ...
     * JDI frame K:   bottom user method
     * JDI frame K+1: invokeBottomFrame / reflection / Thread.run
     * </pre>
     *
     * <p>All frames are in their original code sections (resume stubs jumped
     * to BEFORE_INVOKE labels), so all locals are naturally in scope.</p>
     *
     * <p>The snapshot frames are ordered [bottom, ..., top] (indices 0..N-1).
     * We match them by className + methodName, working from the top of the
     * JDI stack downward. We skip JDI frames that belong to ReplayState,
     * CountDownLatch, or other infrastructure.</p>
     *
     */
    private static void setLocalsViaJdi(VirtualMachine vm, ThreadReference threadRef,
                                        ThreadSnapshot snapshot,
                                        Map<Long, Object> restoredHeap,
                                        HeapRestorer heapRestorer) {
        try {
            List<StackFrame> jdiFrames = threadRef.frames();
            List<FrameSnapshot> snapshotFrames = snapshot.frames();

            // Match JDI frames to snapshot frames.
            // JDI is top-to-bottom, snapshot is bottom-to-top.
            int snapshotIdx = snapshotFrames.size() - 1; // start from top (deepest)

            for (int jdiIdx = 0; jdiIdx < jdiFrames.size() && snapshotIdx >= 0; jdiIdx++) {
                StackFrame jdiFrame = jdiFrames.get(jdiIdx);
                Location loc = jdiFrame.location();
                String jdiClassName = loc.declaringType().name().replace('.', '/');
                String jdiMethodName = loc.method().name();

                // Skip infrastructure frames (ReplayState, CountDownLatch, JDK, etc.)
                if (isInfrastructureFrame(jdiClassName)) {
                    continue;
                }

                FrameSnapshot snapFrame = snapshotFrames.get(snapshotIdx);

                // Match by class name and method name
                if (jdiClassName.equals(snapFrame.className())
                        && jdiMethodName.equals(snapFrame.methodName())) {
                        // With the direct-jump architecture, all frames are in their
                    // original code sections, so all locals should be in scope.
                    setFrameLocals(vm, threadRef, jdiFrame, snapFrame,
                            restoredHeap, heapRestorer);
                    snapshotIdx--;
                }
                // If no match, skip this JDI frame (it's infrastructure / reflection)
            }

        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException("Thread not suspended for local variable setting", e);
        }
    }

    /**
     * Set local variables in a single JDI frame from a snapshot frame.
     *
     * <p>Object references resolved from the heap bridge are protected from
     * garbage collection via {@link ObjectReference#disableCollection()} while
     * locals are being set. Without this, the target JVM's GC can collect the
     * mirrored object between resolution and {@code setValue()}, causing a
     * {@code ObjectCollectedException}.</p>
     */
    private static void setFrameLocals(VirtualMachine vm, ThreadReference threadRef,
                                       StackFrame jdiFrame, FrameSnapshot snapFrame,
                                       Map<Long, Object> restoredHeap,
                                       HeapRestorer heapRestorer) {
        Method method = jdiFrame.location().method();

        List<com.sun.jdi.LocalVariable> jdiLocals;
        try {
            jdiLocals = method.variables();
        } catch (AbsentInformationException e) {
            throw new RuntimeException(
                    "No debug info for method " + method.declaringType().name() + "."
                    + method.name() + ". Classes must be compiled with debug info (-g) "
                    + "for thread restore to set local variables.", e);
        }

        // Build a map of JDI locals by name for quick lookup.
        // When multiple variables share the same name and slot (e.g., two
        // for-loops both declaring 'int i'), prefer the one that is VISIBLE
        // at the current frame location. This is critical for the single-pass
        // architecture where the thread is at the freeze point's BCI.
        Map<String, com.sun.jdi.LocalVariable> jdiLocalsByName = new HashMap<>();
        for (com.sun.jdi.LocalVariable jdiLocal : jdiLocals) {
            com.sun.jdi.LocalVariable existing = jdiLocalsByName.get(jdiLocal.name());
            if (existing == null) {
                jdiLocalsByName.put(jdiLocal.name(), jdiLocal);
            } else if (jdiLocal.isVisible(jdiFrame)) {
                // Prefer the visible one (in scope at current BCI)
                jdiLocalsByName.put(jdiLocal.name(), jdiLocal);
            }
        }

        // Pre-resolve all values and pin object references to prevent GC collection.
        // The target JVM's GC can collect objects between resolve and setValue(),
        // causing ObjectCollectedException. disableCollection() prevents this.
        // LocalEntry is defined as a static inner class of ThreadRestorer
        List<LocalEntry> entries = new ArrayList<>();
        List<ObjectReference> pinnedRefs = new ArrayList<>();

        for (ai.jacc.durableThreads.snapshot.LocalVariable snapLocal : snapFrame.locals()) {
            com.sun.jdi.LocalVariable jdiLocal = jdiLocalsByName.get(snapLocal.name());
            if (jdiLocal == null) continue;

            Value jdiValue = convertToJdiValue(vm, snapLocal.value(),
                    restoredHeap, heapRestorer);
            boolean isNull = snapLocal.value() instanceof NullRef;

            if (jdiValue instanceof ObjectReference) {
                ObjectReference objRef = (ObjectReference) jdiValue;
                try {
                    objRef.disableCollection();
                    pinnedRefs.add(objRef);
                } catch (ObjectCollectedException alreadyGone) {
                    // Object was collected before we could pin it — re-resolve
                    jdiValue = convertToJdiValue(vm, snapLocal.value(),
                            restoredHeap, heapRestorer);
                    if (jdiValue instanceof ObjectReference) {
                        ObjectReference retryRef = (ObjectReference) jdiValue;
                        retryRef.disableCollection();
                        pinnedRefs.add(retryRef);
                    }
                }
            }

            if (jdiValue != null || isNull) {
                entries.add(new LocalEntry(jdiLocal, jdiValue, isNull));
            }
        }

        try {
            for (LocalEntry entry : entries) {
                try {
                    jdiFrame.setValue(entry.jdiLocal(), entry.jdiValue());
                } catch (ClassNotLoadedException cnle) {
                    // On Java 8, JDI's classloader lookup can fail for bootstrap-loaded
                    // classes (like TimeUnit) when the declaring class uses the app
                    // classloader. The class IS loaded in the JVM, but JDI's
                    // LocalVariableImpl.findType() only searches the declaring type's
                    // classloader, not parent classloaders. Fixed in Java 9+.
                    //
                    // Bypass JDI's client-side type check by setting the slot value
                    // directly via reflection on JDI internals.
                    if (!setValueBypassTypeCheck(jdiFrame, entry.jdiLocal(), entry.jdiValue())) {
                        // Fallback: skip this local with a warning rather than
                        // crashing the entire restore
                        System.err.println("[DurableThreads] Warning: skipping local '"
                                + entry.jdiLocal().name() + "' — class '"
                                + cnle.className() + "' not resolvable by JDI classloader"
                                + " (Java 8 limitation)");
                    }
                } catch (InvalidTypeException e) {
                    // Lambda instances captured in the heap are restored as
                    // Object placeholders (hidden class can't be found). When
                    // JDI tries to set a local typed as a functional interface
                    // to an Object, the type check fails. Skip these silently —
                    // the frame's lambda dispatch uses the proxy receiver.
                    if (e.getMessage() != null && e.getMessage().contains("java.lang.Object")) {
                        continue;
                    }
                    throw new RuntimeException(
                            "Type mismatch setting local '" + entry.jdiLocal().name() + "' in "
                            + method.declaringType().name() + "." + method.name()
                            + ": " + e.getMessage(), e);
                } catch (IllegalArgumentException e) {
                    throw new RuntimeException(
                            "Cannot set local '" + entry.jdiLocal().name() + "' in "
                            + method.declaringType().name() + "." + method.name()
                            + " — not in scope at current BCP. "
                            + "Thread restore cannot proceed with incomplete state.",
                            e);
                } catch (com.sun.jdi.InternalException e) {
                    // JDWP Error 35 (INVALID_SLOT): variable not in scope at current BCI.
                    throw new RuntimeException(
                            "Failed to set local '" + entry.jdiLocal().name() + "' in "
                            + method.declaringType().name() + "." + method.name()
                            + ": " + e.getMessage(), e);
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Failed to set local '" + entry.jdiLocal().name() + "' in "
                            + method.declaringType().name() + "." + method.name()
                            + ": " + e.getMessage(), e);
                }
            }
        } finally {
            // Re-enable GC on all pinned references
            for (ObjectReference ref : pinnedRefs) {
                try {
                    ref.enableCollection();
                } catch (ObjectCollectedException ignored) {
                    // already collected after setValue — harmless
                }
            }
        }
    }

    /**
     * Bypass JDI's client-side type check and set a stack frame slot value directly.
     *
     * <p>On Java 8, JDI's {@code StackFrameImpl.setValue()} calls
     * {@code LocalVariableImpl.findType()} which searches only the declaring type's
     * classloader. Bootstrap-loaded classes (like {@code TimeUnit}) can't be found
     * when the declaring type uses the app classloader, causing
     * {@code ClassNotLoadedException} even though the class IS loaded.</p>
     *
     * <p>This method uses reflection to access JDI internals and send the
     * JDWP StackFrame.SetValues command directly, bypassing the type check.</p>
     */
    private static boolean setValueBypassTypeCheck(StackFrame frame,
                                                    com.sun.jdi.LocalVariable local,
                                                    Value value) {
        try {
            // Get the slot number from LocalVariableImpl
            java.lang.reflect.Method slotMethod = local.getClass().getDeclaredMethod("slot");
            slotMethod.setAccessible(true);
            int slot = (int) slotMethod.invoke(local);

            // StackFrameImpl has: ThreadReferenceImpl thread, long id
            java.lang.reflect.Field threadField = frame.getClass().getDeclaredField("thread");
            threadField.setAccessible(true);
            Object threadImpl = threadField.get(frame);

            java.lang.reflect.Field idField = frame.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            long frameId = (long) idField.get(frame);

            // Get the VirtualMachineImpl
            java.lang.reflect.Method vmMethod = frame.getClass().getMethod("virtualMachine");
            Object vmImpl = vmMethod.invoke(frame);

            // Build JDWP.StackFrame.SetValues.SlotInfo
            Class<?> slotInfoClass = Class.forName(
                    "com.sun.tools.jdi.JDWP$StackFrame$SetValues$SlotInfo");
            java.lang.reflect.Constructor<?> slotInfoCtor = slotInfoClass.getDeclaredConstructor(
                    int.class, Value.class);
            slotInfoCtor.setAccessible(true);
            Object slotInfo = slotInfoCtor.newInstance(slot, value);

            // Create SlotInfo array
            Object slotInfoArray = java.lang.reflect.Array.newInstance(slotInfoClass, 1);
            java.lang.reflect.Array.set(slotInfoArray, 0, slotInfo);

            // Call JDWP.StackFrame.SetValues.process(vm, thread, frameId, slotInfos)
            // Find the method by name since class hierarchy varies across JDK versions
            Class<?> setValuesClass = Class.forName(
                    "com.sun.tools.jdi.JDWP$StackFrame$SetValues");
            java.lang.reflect.Method processMethod = null;
            for (java.lang.reflect.Method m : setValuesClass.getDeclaredMethods()) {
                if (m.getName().equals("process") && m.getParameterTypes().length == 4) {
                    processMethod = m;
                    break;
                }
            }
            if (processMethod == null) return false;
            processMethod.setAccessible(true);
            processMethod.invoke(null, vmImpl, threadImpl, frameId, slotInfoArray);
            return true;
        } catch (Exception e) {
            // Reflection failed — different JDI internal structure than expected
            return false;
        }
    }

    /**
     * Pre-load all classes referenced by snapshot local variable types.
     * This prevents ClassNotLoadedException during setValue() later, which
     * is critical because forceLoadClass uses invokeMethod (which resumes
     * the thread and invalidates cached StackFrame references).
     */
    private static void preloadSnapshotClasses(VirtualMachine vm, ThreadReference threadRef,
                                                ThreadSnapshot snapshot) {
        Set<String> classNames = new LinkedHashSet<>();
        for (FrameSnapshot frame : snapshot.frames()) {
            for (ai.jacc.durableThreads.snapshot.LocalVariable local : frame.locals()) {
                String sig = local.typeDescriptor();
                if (sig != null && sig.startsWith("L") && sig.endsWith(";")) {
                    // Convert type signature "Ljava/util/concurrent/TimeUnit;" to class name
                    String className = sig.substring(1, sig.length() - 1).replace('/', '.');
                    classNames.add(className);
                }
            }
        }

        for (String className : classNames) {
            // First, load the class locally (we're in the same JVM).
            // This ensures the class is loaded even if JDI's invokeMethod fails
            // (e.g., on Java 8 where invokeMethod fails for WAITING threads).
            try {
                Class.forName(className);
            } catch (ClassNotFoundException ignored) {}

            // Check if JDI can see the class now
            if (!vm.classesByName(className).isEmpty()) continue;

            // Try to force-load via JDI as well (for cross-classloader visibility)
            forceLoadClass(vm, threadRef, className);
        }
    }

    private static void forceLoadClass(VirtualMachine vm, ThreadReference threadRef,
                                        String className) {
        try {
            // Find java.lang.Class in the target JVM
            List<ReferenceType> classTypes = vm.classesByName("java.lang.Class");
            if (classTypes.isEmpty()) return;
            ClassType classType = (ClassType) classTypes.get(0);

            // Find the forName(String) method
            List<Method> forNameMethods = classType.methodsByName("forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;");
            if (forNameMethods.isEmpty()) return;

            // Invoke Class.forName(className) in the target JVM
            StringReference classNameRef = vm.mirrorOf(className);
            classType.invokeMethod(threadRef, forNameMethods.get(0),
                    java.util.Collections.singletonList(classNameRef),
                    ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (Exception e) {
            // Non-fatal: the retry after this will produce a clear error
            System.err.println("[DurableThreads] Warning: failed to force-load class '"
                    + className + "' in target JVM: " + e.getMessage());
        }
    }

    /**
     * Convert a snapshot ObjectRef to a JDI Value.
     */
    private static Value convertToJdiValue(VirtualMachine vm, ObjectRef ref,
                                           Map<Long, Object> restoredHeap,
                                           HeapRestorer heapRestorer) {
        if (ref instanceof NullRef) {
            return null;
        } else if (ref instanceof PrimitiveRef) {
            return convertPrimitiveToJdiValue(vm, ((PrimitiveRef) ref).value());
        } else if (ref instanceof HeapRef) {
            return resolveHeapRefViaJdi(vm, ((HeapRef) ref).id());
        }
        return null;
    }

    /**
     * Resolve a heap object reference to a JDI ObjectReference by reading it
     * from the {@link HeapObjectBridge}.
     *
     * <p>The restored object lives in the same JVM. We stored it in
     * HeapObjectBridge.objects (a static ConcurrentHashMap). JDI can read
     * that map via ReferenceType field access, then call get() to obtain
     * the ObjectReference.</p>
     */
    private static Value resolveHeapRefViaJdi(VirtualMachine vm, long snapshotId) {
        try {
            // Find the HeapObjectBridge class in JDI
            List<ReferenceType> bridgeTypes = vm.classesByName(
                    "ai.jacc.durableThreads.internal.HeapObjectBridge");
            if (bridgeTypes.isEmpty()) {
                throw new RuntimeException("HeapObjectBridge class not found in JDI. "
                        + "Cannot resolve heap reference " + snapshotId);
            }

            ReferenceType bridgeType = bridgeTypes.get(0);

            // Find the static 'objects' field (ConcurrentHashMap<String, Object>)
            com.sun.jdi.Field objectsField = bridgeType.fieldByName("objects");
            if (objectsField == null) {
                throw new RuntimeException("HeapObjectBridge.objects field not found. "
                        + "Cannot resolve heap reference " + snapshotId);
            }

            ObjectReference mapRef = (ObjectReference) bridgeType.getValue(objectsField);
            if (mapRef == null) {
                throw new RuntimeException("HeapObjectBridge.objects map is null. "
                        + "Cannot resolve heap reference " + snapshotId);
            }

            Value result = JdiHelper.getConcurrentHashMapValue(
                    mapRef, String.valueOf(snapshotId));
            if (result == null) {
                throw new RuntimeException("Heap object with snapshot ID " + snapshotId
                        + " not found in HeapObjectBridge. The restored heap may be incomplete.");
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve heap reference " + snapshotId
                    + " via JDI", e);
        }
    }

    /**
     * Convert a boxed primitive to the corresponding JDI Value.
     */
    private static Value convertPrimitiveToJdiValue(VirtualMachine vm, java.io.Serializable value) {
        if (value instanceof Boolean) return vm.mirrorOf((Boolean) value);
        if (value instanceof Byte) return vm.mirrorOf((Byte) value);
        if (value instanceof Character) return vm.mirrorOf((Character) value);
        if (value instanceof Short) return vm.mirrorOf((Short) value);
        if (value instanceof Integer) return vm.mirrorOf((Integer) value);
        if (value instanceof Long) return vm.mirrorOf((Long) value);
        if (value instanceof Float) return vm.mirrorOf((Float) value);
        if (value instanceof Double) return vm.mirrorOf((Double) value);
        if (value instanceof String) return vm.mirrorOf((String) value);
        return null;
    }
}
