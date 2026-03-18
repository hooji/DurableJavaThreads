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
        Thread replayThread = new Thread(() -> {
            // Activate replay mode
            ReplayState.activate(resumeIndices);

            // Set up uncaught exception handler to handle ThreadFrozenError silently
            Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
                if (!(e instanceof com.u1.durableThreads.exception.ThreadFrozenError)) {
                    System.err.println("Uncaught exception in restored thread: " + e);
                    e.printStackTrace();
                }
            });

            try {
                // Enter the bottom frame's method via reflection.
                // This triggers the prologue which dispatches to the resume stub,
                // which recursively rebuilds the entire call stack.
                FrameSnapshot bottomFrame = snapshot.bottomFrame();
                invokeBottomFrame(bottomFrame, restoredHeap, heapRestorer, snapshot);
            } catch (com.u1.durableThreads.exception.ThreadFrozenError e) {
                // Expected — thread was re-frozen
            } catch (Exception e) {
                throw new RuntimeException("Failed to replay thread from snapshot", e);
            }
        }, snapshot.threadName() + "-restored");

        // Configure JDI-based local variable setting
        // This happens asynchronously when the thread hits resumePoint()
        configureJdiRestore(replayThread, snapshot, restoredHeap, heapRestorer);

        return replayThread;
    }

    /**
     * Validate that all methods in the snapshot still have the same bytecode.
     */
    private static void validateBytecodeHashes(ThreadSnapshot snapshot) {
        List<String> mismatched = new ArrayList<>();

        for (FrameSnapshot frame : snapshot.frames()) {
            if (frame.bytecodeHash() == null || frame.bytecodeHash().length == 0) {
                continue; // No hash stored — skip validation
            }

            byte[] classBytecode = InvokeRegistry.getInstrumentedBytecode(frame.className());
            if (classBytecode == null) {
                mismatched.add(frame.className().replace('/', '.') + "." + frame.methodName());
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
     * Compute the invoke index for each frame based on the bytecodeIndex
     * stored in the snapshot.
     */
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

    /**
     * Invoke the bottom frame's method to start the replay chain.
     */
    private static void invokeBottomFrame(FrameSnapshot bottomFrame,
                                          Map<Long, Object> restoredHeap,
                                          HeapRestorer heapRestorer,
                                          ThreadSnapshot snapshot) throws Exception {
        String className = bottomFrame.className().replace('/', '.');
        Class<?> clazz = Class.forName(className);

        // Find the method
        java.lang.reflect.Method method = findMethod(clazz, bottomFrame.methodName(),
                bottomFrame.methodSignature());
        if (method == null) {
            throw new RuntimeException("Cannot find method: " + className + "."
                    + bottomFrame.methodName() + bottomFrame.methodSignature());
        }

        method.setAccessible(true);

        // Prepare arguments (dummies — the real values are set via JDI)
        Object[] args = createDummyArgs(method.getParameterTypes());
        Object receiver = null;

        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            // Need a receiver instance. Try to find it in the restored heap,
            // otherwise create a dummy instance.
            receiver = findOrCreateReceiver(clazz, bottomFrame, restoredHeap, heapRestorer);
        }

        method.invoke(receiver, args);
    }

    private static java.lang.reflect.Method findMethod(Class<?> clazz, String name, String desc) {
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && descriptorMatches(m, desc)) {
                return m;
            }
        }
        // Check superclasses
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
        // Look for 'this' in the frame's locals (slot 0 for instance methods)
        for (com.u1.durableThreads.snapshot.LocalVariable local : frame.locals()) {
            if (local.slot() == 0 && local.name().equals("this")) {
                Object resolved = heapRestorer.resolve(local.value());
                if (resolved != null) return resolved;
            }
        }

        // Create a dummy instance via Objenesis
        try {
            org.objenesis.ObjenesisStd objenesis = new org.objenesis.ObjenesisStd(true);
            return objenesis.newInstance(clazz);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create instance of " + clazz.getName(), e);
        }
    }

    /**
     * Configure JDI to intercept the replay thread when it hits resumePoint(),
     * set local variables, and deactivate replay mode.
     */
    private static void configureJdiRestore(Thread replayThread, ThreadSnapshot snapshot,
                                            Map<Long, Object> restoredHeap,
                                            HeapRestorer heapRestorer) {
        // This runs in a background thread that monitors the replay thread
        Thread jdiWorker = new Thread(() -> {
            try {
                int port = JdiHelper.detectJdwpPort();
                if (port < 0) return;

                VirtualMachine vm = JdiHelper.connect(port);
                try {
                    // Set a breakpoint on ReplayState.resumePoint()
                    List<ReferenceType> types = vm.classesByName("com.u1.durableThreads.ReplayState");
                    if (types.isEmpty()) return;

                    ReferenceType replayStateType = types.get(0);
                    Method resumePointMethod = replayStateType.methodsByName("resumePoint").get(0);
                    Location bpLocation = resumePointMethod.location();

                    BreakpointRequest bpReq = vm.eventRequestManager()
                            .createBreakpointRequest(bpLocation);
                    bpReq.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    bpReq.enable();

                    // Wait for the breakpoint to hit in the replay thread
                    EventQueue eventQueue = vm.eventQueue();
                    while (true) {
                        EventSet eventSet = eventQueue.remove(10_000); // 10s timeout
                        if (eventSet == null) break;

                        for (Event event : eventSet) {
                            if (event instanceof BreakpointEvent bpEvent) {
                                ThreadReference tr = bpEvent.thread();
                                // Verify this is our replay thread
                                if (tr.name().equals(replayThread.getName())) {
                                    // Set local variables via JDI
                                    setLocalsViaJdi(tr, snapshot, restoredHeap, heapRestorer);

                                    // Deactivate replay mode by invoking ReplayState.deactivate()
                                    // via JDI method invocation
                                    deactivateReplayViaJdi(tr, replayStateType);

                                    // Remove breakpoint and resume
                                    bpReq.disable();
                                    vm.eventRequestManager().deleteEventRequest(bpReq);
                                    eventSet.resume();
                                    return;
                                }
                            }
                        }
                        eventSet.resume();
                    }
                } finally {
                    vm.dispose();
                }
            } catch (Exception e) {
                System.err.println("[DurableThreads] JDI restore failed: " + e.getMessage());
            }
        }, "durable-restore-jdi-worker");
        jdiWorker.setDaemon(true);
        jdiWorker.start();
    }

    /**
     * Set local variables in each frame via JDI.
     */
    private static void setLocalsViaJdi(ThreadReference threadRef, ThreadSnapshot snapshot,
                                        Map<Long, Object> restoredHeap,
                                        HeapRestorer heapRestorer) {
        try {
            List<StackFrame> jdiFrames = threadRef.frames();
            List<FrameSnapshot> snapshotFrames = snapshot.frames();

            // Frames in snapshot are bottom-to-top, JDI gives top-to-bottom.
            // Match them up and set locals.
            // The top JDI frame is resumePoint() — skip it.
            // Then frames correspond to snapshot frames in reverse order.

            // For now, log that we'd set locals here.
            // Full implementation requires mapping JDI Values back from our heap.
            // TODO: Implement full local variable setting via JDI

        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException("Thread not suspended for local variable setting", e);
        }
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
}
