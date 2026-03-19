package com.u1.durableThreads;

import com.sun.jdi.*;
import com.u1.durableThreads.exception.LambdaFrameException;
import com.u1.durableThreads.exception.NonEmptyStackException;
import com.u1.durableThreads.exception.ThreadFrozenError;
import com.u1.durableThreads.internal.*;
import com.u1.durableThreads.snapshot.*;

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

    private ThreadFreezer() {}

    /**
     * Freeze the calling thread. This method does not return normally in the
     * original thread — only in restored instances.
     */
    static void freeze(Consumer<ThreadSnapshot> handler) {
        Thread callerThread = Thread.currentThread();
        Object lock = new Object();
        Throwable[] error = new Throwable[1];

        // Spawn the freeze worker (Thread B)
        Thread worker = new Thread(() -> {
            try {
                performFreeze(callerThread, handler);
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
                lock.wait(30_000); // 30 second timeout
            } catch (InterruptedException e) {
                // This is the expected freeze termination path.
                // Thread B interrupted us after capturing the snapshot.
                // Throw ThreadFrozenError to cleanly terminate this thread.
                if (FreezeFlag.isFrozen(Thread.currentThread())) {
                    throw new ThreadFrozenError();
                }
                // If we weren't marked as frozen, this was an unexpected interrupt
                Thread.currentThread().interrupt();
                throw new RuntimeException("Freeze interrupted unexpectedly", e);
            }
        }

        // If we reach here, either:
        // - The worker failed (check error)
        // - We timed out
        // - This is a restored thread returning from freeze()
        if (error[0] != null) {
            throw new RuntimeException("Freeze failed", error[0]);
        }

        // If we're still alive and no error, this is the restored thread returning.
        // (The original thread would have been killed by ThreadFrozenError.)
    }

    private static void performFreeze(Thread targetThread, Consumer<ThreadSnapshot> handler) {
        // Connect to this JVM via JDI
        int port = JdiHelper.detectJdwpPort();
        if (port < 0) {
            throw new RuntimeException(
                    "JDWP not enabled. Start with: -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005");
        }

        VirtualMachine vm = JdiHelper.connect(port);
        try {
            // Find the target thread in JDI
            ThreadReference threadRef = JdiHelper.findThread(vm, targetThread);

            // Suspend the target thread
            threadRef.suspend();
            try {
                // Capture the thread's state
                ThreadSnapshot snapshot = captureSnapshot(threadRef, targetThread.getName());

                // Call the handler with the snapshot
                handler.accept(snapshot);

            } finally {
                // Resume the thread so we can terminate it
                threadRef.resume();
            }

            // Terminate the target thread by interrupting it.
            // The thread is waiting on lock.wait(), so interrupt will wake it.
            // We set a flag that causes ThreadFrozenError to be thrown.
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

        } finally {
            vm.dispose();
        }
    }

    /** Prefixes of classes whose frames should be excluded from the snapshot. */
    private static final String[] EXCLUDED_FRAME_PREFIXES = {
            "java/", "javax/", "jdk/", "sun/", "com/sun/",
    };

    /** Specific library classes to exclude (not the whole package — user subpackages may exist). */
    private static final String[] EXCLUDED_FRAME_CLASSES = {
            "com/u1/durableThreads/Durable",
            "com/u1/durableThreads/ThreadFreezer",
            "com/u1/durableThreads/ThreadRestorer",
            "com/u1/durableThreads/ReplayState",
    };

    /**
     * Check if a frame is a JDK/library infrastructure frame that should be
     * silently skipped (not captured). These are frames that get recreated
     * naturally during restore (Thread.run, reflection, etc.).
     */
    private static boolean isInfrastructureFrame(String className) {
        for (String prefix : EXCLUDED_FRAME_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        for (String excluded : EXCLUDED_FRAME_CLASSES) {
            if (className.equals(excluded) || className.startsWith(excluded + "$")) return true;
        }
        return false;
    }

    /**
     * Check if a frame is a lambda-generated class.
     * Lambda frames cannot be replayed and are a hard error.
     */
    private static boolean isLambdaFrame(String className) {
        return className.contains("$$Lambda");
    }

    private static ThreadSnapshot captureSnapshot(ThreadReference threadRef, String threadName) {
        try {
            List<StackFrame> jdiFrames = threadRef.frames();
            List<FrameSnapshot> frameSnapshots = new ArrayList<>();
            JdiHeapWalker heapWalker = new JdiHeapWalker();

            // Walk frames bottom to top (JDI gives top to bottom).
            // Filter out JDK and library-internal frames — they can't be replayed
            // (not instrumented) and are infrastructure that gets recreated naturally.
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

                // Lambda frames are a hard error — they can't be replayed
                if (isLambdaFrame(className)) {
                    // Find the enclosing method name for the error message
                    String enclosing = className.contains("/")
                            ? className.substring(0, className.indexOf("$$Lambda"))
                            : className;
                    throw new LambdaFrameException(className, enclosing + "." + methodName);
                }

                int bcp = (int) location.codeIndex();

                // Compute bytecode hash
                byte[] classBytecode = InvokeRegistry.getInstrumentedBytecode(className);
                byte[] hash = classBytecode != null
                        ? BytecodeHasher.hash(classBytecode, methodName, methodSig)
                        : new byte[0];

                // Validate operand stack is empty at this call site
                if (classBytecode != null) {
                    String stackError = OperandStackChecker.checkStackAtInvoke(
                            classBytecode, methodName, methodSig, bcp);
                    if (stackError != null) {
                        throw new NonEmptyStackException(stackError);
                    }
                }

                // Compute the invoke index from the BCP — this is used at restore
                // time to set __skip so the replay skips the freeze invoke.
                String invokeKey = InvokeRegistry.key(className, methodName, methodSig);
                int invokeIndex = InvokeRegistry.getInvokeIndex(invokeKey, bcp);

                // Capture local variables
                List<com.u1.durableThreads.snapshot.LocalVariable> locals = captureLocals(jdiFrame, heapWalker);

                frameSnapshots.add(new FrameSnapshot(
                        className, methodName, methodSig, bcp, invokeIndex, hash, locals));
            }

            if (frameSnapshots.isEmpty()) {
                throw new RuntimeException(
                        "Captured 0 user frames for thread '" + threadName + "'. "
                        + "This usually means the wrong thread was suspended "
                        + "(JDI found a thread whose stack contains only infrastructure frames).");
            }

            return new ThreadSnapshot(
                    Instant.now(),
                    threadName,
                    frameSnapshots,
                    heapWalker.getSnapshots());

        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException("Thread not properly suspended for capture", e);
        }
    }

    private static List<com.u1.durableThreads.snapshot.LocalVariable> captureLocals(StackFrame frame, JdiHeapWalker heapWalker) {
        List<com.u1.durableThreads.snapshot.LocalVariable> result = new ArrayList<>();
        try {
            Location location = frame.location();
            Method method = location.method();

            List<com.sun.jdi.LocalVariable> jdiLocals;
            try {
                jdiLocals = method.variables();
            } catch (AbsentInformationException e) {
                // No debug info available — can't capture local variables
                return result;
            }

            for (com.sun.jdi.LocalVariable jdiLocal : jdiLocals) {
                try {
                    if (!jdiLocal.isVisible(frame)) continue;

                    Value value = frame.getValue(jdiLocal);
                    ObjectRef ref = heapWalker.capture(value);
                    // JDI LocalVariable doesn't expose slot index directly;
                    // use hashCode as a proxy, or get it from the variable table
                    int slot = 0;
                    try {
                        // Attempt to get the slot via reflection on JDI impl
                        var slotMethod = jdiLocal.getClass().getMethod("slot");
                        slot = (int) slotMethod.invoke(jdiLocal);
                    } catch (Exception ignored) {}
                    result.add(new com.u1.durableThreads.snapshot.LocalVariable(
                            slot,
                            jdiLocal.name(),
                            jdiLocal.signature(),
                            ref));
                } catch (Exception e) {
                    // Skip locals that can't be captured
                }
            }
        } catch (Exception e) {
            // Return what we have so far
        }
        return result;
    }

    /**
     * Thread-local flag to signal that a thread has been frozen and should terminate.
     */
    static final class FreezeFlag {
        private static final Set<Long> frozenThreads = Collections.synchronizedSet(new HashSet<>());

        static void markFrozen(Thread t) {
            frozenThreads.add(t.threadId());
        }

        static boolean isFrozen(Thread t) {
            return frozenThreads.remove(t.threadId());
        }
    }
}
