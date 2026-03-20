package com.u1.durableThreads;

import com.sun.jdi.*;
import com.u1.durableThreads.exception.BytecodeMismatchException;
import com.u1.durableThreads.internal.*;
import com.u1.durableThreads.snapshot.*;

import java.util.*;

/**
 * Implements the restore operation: validates a snapshot, rebuilds the heap,
 * and creates a thread that replays the call stack to resume execution from
 * the freeze point.
 */
final class ThreadRestorer {

    private ThreadRestorer() {}

    /**
     * Restore a frozen thread from a snapshot.
     *
     * @param snapshot the captured thread state
     * @return a Thread (not yet started) that will resume from the freeze point
     */
    static Thread restore(ThreadSnapshot snapshot) {
        // Step 0: Sanity-check the snapshot
        if (snapshot.frameCount() == 0) {
            throw new IllegalArgumentException(
                    "Cannot restore a snapshot with 0 frames. "
                    + "This usually means the freeze captured the wrong thread "
                    + "(see JdiHelper.findThread).");
        }

        // Step 1: Force-load all classes referenced in the snapshot.
        // This triggers the agent's ClassFileTransformer, which instruments them
        // and populates InvokeRegistry with their invoke offset maps.
        // Must happen BEFORE computeResumeIndices() which reads InvokeRegistry.
        ensureClassesLoaded(snapshot);

        // Step 2: Validate bytecode hashes
        validateBytecodeHashes(snapshot);

        // Step 2b: Validate class structure hashes for heap objects
        validateClassStructureHashes(snapshot);

        // Step 3: Rebuild the heap
        HeapRestorer heapRestorer = new HeapRestorer();
        Map<Long, Object> restoredHeap = heapRestorer.restoreAll(snapshot.heap());

        // Step 3b: Populate the heap object bridge for JDI access
        HeapObjectBridge.clear();
        for (Map.Entry<Long, Object> entry : restoredHeap.entrySet()) {
            HeapObjectBridge.put(entry.getKey(), entry.getValue());
        }

        // Step 4: Build replay state (now InvokeRegistry has the data)
        int[] resumeIndices = computeResumeIndices(snapshot);

        // Step 4: Create the replay thread
        String threadName = snapshot.threadName() + "-restored";
        Thread replayThread = new Thread(() -> {
            // Activate replay mode with latch — resumePoint() will block
            // until the JDI worker sets locals and releases it
            ReplayState.activateWithLatch(resumeIndices);

            try {
                FrameSnapshot bottomFrame = snapshot.bottomFrame();
                invokeBottomFrame(bottomFrame, restoredHeap, heapRestorer, snapshot);
            } catch (com.u1.durableThreads.exception.ThreadFrozenError e) {
                // Expected — thread was re-frozen
            } catch (Exception e) {
                throw new RuntimeException("Failed to replay thread from snapshot", e);
            }
        }, threadName);

        // Step 5: Configure JDI to set locals when the replay thread hits resumePoint()
        configureJdiRestore(replayThread, threadName, snapshot, restoredHeap, heapRestorer);

        return replayThread;
    }

    private static void validateBytecodeHashes(ThreadSnapshot snapshot) {
        List<String> mismatched = new ArrayList<>();

        for (FrameSnapshot frame : snapshot.frames()) {
            if (frame.bytecodeHash() == null || frame.bytecodeHash().length == 0) {
                continue;
            }

            byte[] classBytecode = InvokeRegistry.getInstrumentedBytecode(frame.className());
            if (classBytecode == null) {
                // Class not instrumented — may be a JDK class that was stripped
                continue;
            }

            byte[] currentHash = BytecodeHasher.hash(
                    classBytecode, frame.methodName(), frame.methodSignature());
            if (currentHash == null || !Arrays.equals(frame.bytecodeHash(), currentHash)) {
                mismatched.add(frame.className().replace('/', '.') + "." + frame.methodName());
            }
        }

        if (!mismatched.isEmpty()) {
            throw new BytecodeMismatchException(mismatched);
        }
    }

    /**
     * Validate class structure hashes for heap objects to detect incompatible
     * class changes (added/removed/renamed fields, type changes).
     */
    private static void validateClassStructureHashes(ThreadSnapshot snapshot) {
        List<String> mismatched = new ArrayList<>();

        for (ObjectSnapshot objSnap : snapshot.heap()) {
            if (objSnap.classStructureHash() == null || objSnap.classStructureHash().length == 0) {
                continue;
            }
            if (objSnap.kind() != ObjectKind.REGULAR) {
                continue;
            }

            try {
                Class<?> clazz = Class.forName(objSnap.className());
                byte[] currentHash = ClassStructureHasher.hashClassStructure(clazz);
                if (!Arrays.equals(objSnap.classStructureHash(), currentHash)) {
                    mismatched.add(objSnap.className());
                }
            } catch (ClassNotFoundException e) {
                mismatched.add(objSnap.className() + " (class not found)");
            }
        }

        if (!mismatched.isEmpty()) {
            throw new BytecodeMismatchException(mismatched);
        }
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

    private static void invokeBottomFrame(FrameSnapshot bottomFrame,
                                          Map<Long, Object> restoredHeap,
                                          HeapRestorer heapRestorer,
                                          ThreadSnapshot snapshot) throws Exception {
        String className = bottomFrame.className().replace('/', '.');
        Class<?> clazz = Class.forName(className);

        java.lang.reflect.Method method = findMethod(clazz, bottomFrame.methodName(),
                bottomFrame.methodSignature());
        if (method == null) {
            throw new RuntimeException("Cannot find method: " + className + "."
                    + bottomFrame.methodName() + bottomFrame.methodSignature());
        }

        method.setAccessible(true);

        Object[] args = createDummyArgs(method.getParameterTypes());
        Object receiver = null;

        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            receiver = findOrCreateReceiver(clazz, bottomFrame, restoredHeap, heapRestorer);
        }

        method.invoke(receiver, args);
    }

    /**
     * Configure two-phase JDI worker for thread restoration.
     *
     * <p><b>Phase 1</b>: Wait for replay thread to block at {@code resumePoint()}.
     * Deactivate replay mode and release the resume latch. The thread wakes,
     * sets {@code __skip}, and re-executes original code (skipping all invokes
     * up to the target).</p>
     *
     * <p><b>Phase 2</b>: Wait for replay thread to block at {@code localsReady()}.
     * At this point the thread is in the original code section where all local
     * variables are in scope. Set locals via JDI, then release the locals latch.
     * The thread resumes past the freeze point with correct local variable values.</p>
     */
    private static void configureJdiRestore(Thread replayThread, String threadName,
                                            ThreadSnapshot snapshot,
                                            Map<Long, Object> restoredHeap,
                                            HeapRestorer heapRestorer) {
        Thread jdiWorker = new Thread(() -> {
            try {
                int port = JdiHelper.detectJdwpPort();
                if (port < 0) {
                    throw new RuntimeException(
                            "JDWP not available for restore. "
                            + "Start with: -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005");
                }

                VirtualMachine vm = JdiHelper.connect(port);
                try {
                    // === PHASE 1: Deactivate replay mode ===
                    // Wait for thread to reach resumePoint()
                    ThreadReference tr = waitForThreadAtMethod(vm, threadName,
                            "resumePoint", "ReplayState", 30_000);
                    if (tr == null) {
                        throw new RuntimeException(
                                "Timeout waiting for replay thread '" + threadName
                                + "' to reach resumePoint(). The thread may have failed "
                                + "during replay prologue execution.");
                    }

                    // Deactivate replay mode and release resume latch.
                    // Thread will wake, set __skip in resume stub, goto original code,
                    // skip all invokes up to target, then block at localsReady().
                    ReplayState.deactivate();
                    ReplayState.releaseResumePoint();

                    // === PHASE 2: Set local variables ===
                    // Wait for thread to reach localsReady() in the original code section.
                    // At this point locals are in scope and can be set via JDI.
                    ThreadReference tr2 = waitForThreadAtMethod(vm, threadName,
                            "localsReady", "ReplayState", 30_000);
                    if (tr2 == null) {
                        throw new RuntimeException(
                                "Timeout waiting for replay thread '" + threadName
                                + "' to reach localsReady(). The thread may have failed "
                                + "during original code re-execution.");
                    }

                    tr2.suspend();
                    try {
                        // Set local variables — now they're in scope
                        setLocalsViaJdi(vm, tr2, snapshot, restoredHeap, heapRestorer);

                        // Release locals latch
                        ReplayState.releaseLocalsReady();

                        // Clean up the heap bridge
                        HeapObjectBridge.clear();
                    } finally {
                        tr2.resume();
                    }
                } finally {
                    vm.dispose();
                }
            } catch (Exception e) {
                System.err.println("[DurableThreads] JDI restore failed: " + e.getMessage());
                e.printStackTrace(System.err);
                ReplayState.signalRestoreError(
                        "JDI restore worker failed: " + e.getMessage());
                // Release both latches so the thread doesn't hang
                ReplayState.releaseResumePoint();
                ReplayState.releaseLocalsReady();
            }
        }, "durable-restore-jdi-worker");
        jdiWorker.setDaemon(true);
        jdiWorker.start();
    }

    /**
     * Poll JDI until the named thread is WAITING inside the specified method.
     *
     * <p>Simply checking for WAITING status is racy — the thread could be
     * WAITING in some other location (e.g., class loading, lock acquisition).
     * We verify the thread is actually blocked inside the target method by
     * inspecting its top stack frames.</p>
     *
     * @param targetMethodName the method name to look for (e.g. "resumePoint", "localsReady")
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
     * Set local variables in each frame via JDI.
     *
     * <p>In Phase 2, the JDI call stack looks like:</p>
     * <pre>
     * JDI frame 0:   localsReady() [or CountDownLatch internals]
     * JDI frame 1:   deepest user method (in original code section, at skip-check)
     * JDI frame 2:   next method up (called from original code)
     * ...
     * JDI frame K:   bottom user method
     * JDI frame K+1: invokeBottomFrame / reflection / Thread.run
     * </pre>
     *
     * <p>The snapshot frames are ordered [bottom, ..., top] (indices 0..N-1).
     * We match them by className + methodName, working from the top of the
     * JDI stack downward. We skip JDI frames that belong to ReplayState,
     * CountDownLatch, or other infrastructure.</p>
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
                if (jdiClassName.startsWith("com/u1/durableThreads/ReplayState")
                        || jdiClassName.startsWith("java/")
                        || jdiClassName.startsWith("jdk/")
                        || jdiClassName.startsWith("sun/")) {
                    continue;
                }

                FrameSnapshot snapFrame = snapshotFrames.get(snapshotIdx);

                // Match by class name and method name
                if (jdiClassName.equals(snapFrame.className())
                        && jdiMethodName.equals(snapFrame.methodName())) {

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

        // Build a map of JDI locals by name for quick lookup
        Map<String, com.sun.jdi.LocalVariable> jdiLocalsByName = new HashMap<>();
        for (com.sun.jdi.LocalVariable jdiLocal : jdiLocals) {
            // Use the first variable with each name (shadowed variables are rare)
            jdiLocalsByName.putIfAbsent(jdiLocal.name(), jdiLocal);
        }

        for (com.u1.durableThreads.snapshot.LocalVariable snapLocal : snapFrame.locals()) {
            com.sun.jdi.LocalVariable jdiLocal = jdiLocalsByName.get(snapLocal.name());
            if (jdiLocal == null) continue;

            try {
                Value jdiValue = convertToJdiValue(vm, snapLocal.value(),
                        restoredHeap, heapRestorer);
                if (jdiValue != null || snapLocal.value() instanceof NullRef) {
                    jdiFrame.setValue(jdiLocal, jdiValue);
                }
            } catch (InvalidTypeException | ClassNotLoadedException e) {
                throw new RuntimeException(
                        "Type mismatch setting local '" + snapLocal.name() + "' in "
                        + method.declaringType().name() + "." + method.name()
                        + ": " + e.getMessage(), e);
            } catch (IllegalArgumentException e) {
                // In the two-phase approach, locals should be in scope at localsReady().
                // If this still happens, log it for debugging.
                System.err.println("[DurableThreads] WARNING: Cannot set local '"
                        + snapLocal.name() + "' in "
                        + method.declaringType().name() + "." + method.name()
                        + " — not in scope at current BCP: " + e.getMessage());
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to set local '" + snapLocal.name() + "' in "
                        + method.declaringType().name() + "." + method.name()
                        + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Convert a snapshot ObjectRef to a JDI Value.
     */
    private static Value convertToJdiValue(VirtualMachine vm, ObjectRef ref,
                                           Map<Long, Object> restoredHeap,
                                           HeapRestorer heapRestorer) {
        return switch (ref) {
            case NullRef ignored -> null;
            case PrimitiveRef p -> convertPrimitiveToJdiValue(vm, p.value());
            case HeapRef h -> resolveHeapRefViaJdi(vm, h.id());
        };
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
                    "com.u1.durableThreads.internal.HeapObjectBridge");
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

            Value result = getObjectFromBridgeArray(vm, bridgeType, snapshotId);
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
     * Get a restored object from the bridge by walking ConcurrentHashMap internals via JDI.
     * Returns null only if the key genuinely doesn't exist in the map.
     */
    private static Value getObjectFromBridgeArray(VirtualMachine vm,
                                                   ReferenceType bridgeType,
                                                   long snapshotId) {
        // Read the static 'objects' map field
        com.sun.jdi.Field objectsField = bridgeType.fieldByName("objects");
        if (objectsField == null) {
            throw new RuntimeException("HeapObjectBridge.objects field not found via JDI");
        }

        ObjectReference mapRef = (ObjectReference) bridgeType.getValue(objectsField);
        if (mapRef == null) {
            throw new RuntimeException("HeapObjectBridge.objects map is null via JDI");
        }

        ReferenceType mapType = mapRef.referenceType();

        com.sun.jdi.Field tableField = findField(mapType, "table");
        if (tableField == null) {
            throw new RuntimeException(
                    "Cannot find 'table' field in ConcurrentHashMap via JDI. "
                    + "Cannot resolve heap reference " + snapshotId);
        }

        ArrayReference table = (ArrayReference) mapRef.getValue(tableField);
        if (table == null) return null; // empty map — key genuinely missing

        String targetKey = String.valueOf(snapshotId);

        // Walk the hash table buckets
        for (int i = 0; i < table.length(); i++) {
            ObjectReference node = (ObjectReference) table.getValue(i);
            while (node != null) {
                // Read key and val fields from the Node
                com.sun.jdi.Field keyField = findField(node.referenceType(), "key");
                com.sun.jdi.Field valField = findField(node.referenceType(), "val");
                com.sun.jdi.Field nextField = findField(node.referenceType(), "next");

                if (keyField == null || valField == null) break;

                Value keyVal = node.getValue(keyField);
                if (keyVal instanceof StringReference sr && sr.value().equals(targetKey)) {
                    return node.getValue(valField);
                }

                // Follow the chain
                if (nextField != null) {
                    Value nextVal = node.getValue(nextField);
                    node = (nextVal instanceof ObjectReference or) ? or : null;
                } else {
                    break;
                }
            }
        }
        return null; // key not found in map
    }

    /**
     * Find a field by name in a type or its supertypes.
     */
    private static com.sun.jdi.Field findField(ReferenceType type, String name) {
        com.sun.jdi.Field f = type.fieldByName(name);
        if (f != null) return f;
        if (type instanceof ClassType ct && ct.superclass() != null) {
            return findField(ct.superclass(), name);
        }
        return null;
    }

    /**
     * Convert a boxed primitive to the corresponding JDI Value.
     */
    private static Value convertPrimitiveToJdiValue(VirtualMachine vm, java.io.Serializable value) {
        if (value instanceof Boolean b) return vm.mirrorOf(b);
        if (value instanceof Byte b) return vm.mirrorOf(b);
        if (value instanceof Character c) return vm.mirrorOf(c);
        if (value instanceof Short s) return vm.mirrorOf(s);
        if (value instanceof Integer i) return vm.mirrorOf(i);
        if (value instanceof Long l) return vm.mirrorOf(l);
        if (value instanceof Float f) return vm.mirrorOf(f);
        if (value instanceof Double d) return vm.mirrorOf(d);
        if (value instanceof String s) return vm.mirrorOf(s);
        return null;
    }

    private static void deactivateReplayViaJdi(ThreadReference threadRef,
                                               ReferenceType replayStateType) {
        try {
            Method deactivateMethod = replayStateType.methodsByName("deactivate").get(0);
            threadRef.invokeMethod(threadRef, deactivateMethod,
                    Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (Exception e) {
            System.err.println("[DurableThreads] Failed to deactivate replay via JDI: " + e);
        }
    }

    private static void releaseResumePointViaJdi(ThreadReference threadRef,
                                                  ReferenceType replayStateType) {
        try {
            Method releaseMethod = replayStateType.methodsByName("releaseResumePoint").get(0);
            threadRef.invokeMethod(threadRef, releaseMethod,
                    Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (Exception e) {
            System.err.println("[DurableThreads] Failed to release resume point via JDI: " + e);
        }
    }

    /**
     * Force-load all classes referenced in the snapshot frames.
     * This triggers the agent's ClassFileTransformer which populates
     * InvokeRegistry with invoke offset maps needed by computeResumeIndices().
     */
    private static void ensureClassesLoaded(ThreadSnapshot snapshot) {
        for (FrameSnapshot frame : snapshot.frames()) {
            String className = frame.className().replace('/', '.');
            try {
                Class.forName(className);
            } catch (ClassNotFoundException e) {
                // Class not available in this JVM — will fail later at invoke time
            }
        }
    }

    // --- Reflection helpers ---

    private static java.lang.reflect.Method findMethod(Class<?> clazz, String name, String desc) {
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && descriptorMatches(m, desc)) {
                return m;
            }
        }
        if (clazz.getSuperclass() != null) {
            return findMethod(clazz.getSuperclass(), name, desc);
        }
        return null;
    }

    private static boolean descriptorMatches(java.lang.reflect.Method m, String desc) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> param : m.getParameterTypes()) {
            sb.append(typeToDescriptor(param));
        }
        sb.append(")");
        sb.append(typeToDescriptor(m.getReturnType()));
        return sb.toString().equals(desc);
    }

    private static String typeToDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static Object[] createDummyArgs(Class<?>[] paramTypes) {
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = defaultValue(paramTypes[i]);
        }
        return args;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    private static Object findOrCreateReceiver(Class<?> clazz, FrameSnapshot frame,
                                               Map<Long, Object> restoredHeap,
                                               HeapRestorer heapRestorer) {
        for (com.u1.durableThreads.snapshot.LocalVariable local : frame.locals()) {
            if (local.slot() == 0 && local.name().equals("this")) {
                Object resolved = heapRestorer.resolve(local.value());
                if (resolved != null) return resolved;
            }
        }

        // 'this' was not captured or could not be resolved from the heap.
        // This is expected for anonymous inner classes and other cases where
        // JDI's method.variables() doesn't include the receiver. Fall back to
        // creating an uninitialized instance via Objenesis — the actual field
        // values will be set later by the JDI worker.
        try {
            org.objenesis.ObjenesisStd objenesis = new org.objenesis.ObjenesisStd(true);
            return objenesis.newInstance(clazz);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create receiver instance of " + clazz.getName()
                    + ". The 'this' reference was not captured in the snapshot and Objenesis"
                    + " fallback failed.", e);
        }
    }
}
