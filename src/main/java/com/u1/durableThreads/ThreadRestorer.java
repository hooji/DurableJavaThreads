package com.u1.durableThreads;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
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
        // Step 1: Validate bytecode hashes
        validateBytecodeHashes(snapshot);

        // Step 2: Rebuild the heap
        HeapRestorer heapRestorer = new HeapRestorer();
        Map<Long, Object> restoredHeap = heapRestorer.restoreAll(snapshot.heap());

        // Step 3: Build replay state
        int[] resumeIndices = computeResumeIndices(snapshot);

        // Step 4: Create the replay thread
        String threadName = snapshot.threadName() + "-restored";
        Thread replayThread = new Thread(() -> {
            // Activate replay mode
            ReplayState.activate(resumeIndices);

            Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
                if (!(e instanceof com.u1.durableThreads.exception.ThreadFrozenError)) {
                    System.err.println("Uncaught exception in restored thread: " + e);
                    e.printStackTrace();
                }
            });

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

    private static int[] computeResumeIndices(ThreadSnapshot snapshot) {
        int[] indices = new int[snapshot.frameCount()];

        for (int i = 0; i < snapshot.frameCount(); i++) {
            FrameSnapshot frame = snapshot.frames().get(i);
            String key = InvokeRegistry.key(
                    frame.className(), frame.methodName(), frame.methodSignature());
            int invokeIndex = InvokeRegistry.getInvokeIndex(key, frame.bytecodeIndex());
            indices[i] = Math.max(0, invokeIndex);
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

    private static void configureJdiRestore(Thread replayThread, String threadName,
                                            ThreadSnapshot snapshot,
                                            Map<Long, Object> restoredHeap,
                                            HeapRestorer heapRestorer) {
        Thread jdiWorker = new Thread(() -> {
            try {
                int port = JdiHelper.detectJdwpPort();
                if (port < 0) {
                    System.err.println("[DurableThreads] JDWP not available for restore");
                    return;
                }

                VirtualMachine vm = JdiHelper.connect(port);
                try {
                    // Set a breakpoint on ReplayState.resumePoint()
                    List<ReferenceType> types = vm.classesByName("com.u1.durableThreads.ReplayState");
                    if (types.isEmpty()) {
                        System.err.println("[DurableThreads] ReplayState not loaded");
                        return;
                    }

                    ReferenceType replayStateType = types.get(0);
                    Method resumePointMethod = replayStateType.methodsByName("resumePoint").get(0);
                    Location bpLocation = resumePointMethod.location();

                    BreakpointRequest bpReq = vm.eventRequestManager()
                            .createBreakpointRequest(bpLocation);
                    bpReq.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    bpReq.enable();

                    // Wait for the breakpoint in the replay thread
                    EventQueue eventQueue = vm.eventQueue();
                    boolean handled = false;
                    while (!handled) {
                        EventSet eventSet = eventQueue.remove(30_000); // 30s timeout
                        if (eventSet == null) {
                            System.err.println("[DurableThreads] Timeout waiting for resumePoint breakpoint");
                            break;
                        }

                        for (Event event : eventSet) {
                            if (event instanceof BreakpointEvent bpEvent) {
                                ThreadReference tr = bpEvent.thread();
                                if (tr.name().equals(threadName)) {
                                    // Set local variables in all frames
                                    setLocalsViaJdi(vm, tr, snapshot, restoredHeap, heapRestorer);

                                    // Deactivate replay mode
                                    deactivateReplayViaJdi(tr, replayStateType);

                                    // Cleanup and resume
                                    bpReq.disable();
                                    vm.eventRequestManager().deleteEventRequest(bpReq);
                                    eventSet.resume();
                                    handled = true;
                                    break;
                                }
                            }
                        }
                        if (!handled) {
                            eventSet.resume();
                        }
                    }
                } finally {
                    vm.dispose();
                }
            } catch (Exception e) {
                System.err.println("[DurableThreads] JDI restore failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }, "durable-restore-jdi-worker");
        jdiWorker.setDaemon(true);
        jdiWorker.start();
    }

    /**
     * Set local variables in each frame via JDI.
     *
     * <p>The JDI call stack at the breakpoint looks like:</p>
     * <pre>
     * JDI frame 0:   resumePoint()
     * JDI frame 1:   deepest user method (in resume stub)
     * JDI frame 2:   next method up (in resume stub)
     * ...
     * JDI frame K:   bottom user method (in resume stub)
     * JDI frame K+1: invokeBottomFrame / reflection / Thread.run
     * </pre>
     *
     * <p>The snapshot frames are ordered [bottom, ..., top] (indices 0..N-1).
     * We match them by className + methodName, working from the top of the
     * JDI stack downward.</p>
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
            // Start from JDI frame 1 (skip resumePoint at index 0).
            int snapshotIdx = snapshotFrames.size() - 1; // start from top (deepest)

            for (int jdiIdx = 1; jdiIdx < jdiFrames.size() && snapshotIdx >= 0; jdiIdx++) {
                StackFrame jdiFrame = jdiFrames.get(jdiIdx);
                Location loc = jdiFrame.location();
                String jdiClassName = loc.declaringType().name().replace('.', '/');
                String jdiMethodName = loc.method().name();

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
        try {
            Method method = jdiFrame.location().method();

            List<com.sun.jdi.LocalVariable> jdiLocals;
            try {
                jdiLocals = method.variables();
            } catch (AbsentInformationException e) {
                // No debug info — can't set locals
                return;
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
                    // Check if the local is visible at the current location
                    // During replay, the frame is in the resume stub, not the original code.
                    // The local may not be "visible" at the resume stub's BCP.
                    // We try anyway — JDI implementations often allow setting any variable
                    // that's in the method's local variable table.
                    Value jdiValue = convertToJdiValue(vm, snapLocal.value(),
                            restoredHeap, heapRestorer);
                    if (jdiValue != null || snapLocal.value() instanceof NullRef) {
                        jdiFrame.setValue(jdiLocal, jdiValue);
                    }
                } catch (InvalidTypeException | ClassNotLoadedException e) {
                    // Type mismatch — skip this local
                } catch (Exception e) {
                    // Some locals can't be set (e.g., in certain frame states)
                    // Continue with best effort
                }
            }
        } catch (Exception e) {
            // Best effort — continue with other frames
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
            case HeapRef h -> {
                // For heap objects, we'd need to mirror the restored Java object
                // into a JDI ObjectReference. This requires the object to exist in
                // the target VM (which it does, since we restored it via HeapRestorer).
                // However, JDI doesn't provide a direct way to get an ObjectReference
                // from a local Java object in the same VM.
                // For now, we use null for heap references — the application code
                // can reinitialize object references after freeze() returns.
                yield null;
            }
        };
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

        try {
            org.objenesis.ObjenesisStd objenesis = new org.objenesis.ObjenesisStd(true);
            return objenesis.newInstance(clazz);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create instance of " + clazz.getName(), e);
        }
    }
}
