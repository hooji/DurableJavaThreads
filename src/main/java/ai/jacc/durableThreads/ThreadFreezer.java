package ai.jacc.durableThreads;

import com.sun.jdi.*;
import ai.jacc.durableThreads.exception.LambdaFrameException;
import ai.jacc.durableThreads.exception.NonEmptyStackException;
import ai.jacc.durableThreads.exception.ThreadFrozenError;
import ai.jacc.durableThreads.internal.*;
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

        // We woke from lock.wait() without InterruptedException.
        // This can happen if lock.notifyAll() in the finally block
        // raced ahead of targetThread.interrupt() in performFreeze.
        // Check the freeze flag — if set, we were successfully frozen.
        if (FreezeFlag.isFrozen(Thread.currentThread())) {
            throw new ThreadFrozenError();
        }

        if (error[0] != null) {
            throw new RuntimeException("Freeze failed", error[0]);
        }

        // Safety net: if we somehow reach this point in the ORIGINAL thread
        // (not a restored thread), block forever. This should never happen —
        // the freeze flag check above should catch it — but a frozen thread
        // must NEVER be allowed to continue executing user code.
        //
        // For restored threads this code is unreachable: the replay prologue
        // skips past the freeze() call entirely and resumes after it.
        //
        // We sleep in a loop (re-checking the freeze flag on each wake)
        // and fall back to a busy spin as an absolute last resort.
        if (Thread.currentThread().isInterrupted()) {
            // Clear the flag so sleep doesn't immediately throw
            Thread.interrupted();
            throw new ThreadFrozenError();
        }
        blockForever();
    }

    /**
     * Block the current thread indefinitely. Called as a safety net to
     * guarantee that a frozen thread can never continue past freeze().
     */
    private static void blockForever() {
        // Phase 1: sleep loop (low CPU cost)
        for (int i = 0; i < 100; i++) {
            if (FreezeFlag.isFrozen(Thread.currentThread())) {
                throw new ThreadFrozenError();
            }
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                // Re-check flag on each wake
                if (FreezeFlag.isFrozen(Thread.currentThread())) {
                    throw new ThreadFrozenError();
                }
            }
        }
        // Phase 2: busy spin (should truly never be reached)
        //noinspection InfiniteLoopStatement
        while (true) {
            Thread.onSpinWait();
        }
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

                // Call the handler with the snapshot.
                // This happens while the target thread is still suspended, so the
                // handler can safely write the snapshot without racing the target.
                handler.accept(snapshot);

            } finally {
                // Resume the thread so we can terminate it
                threadRef.resume();
            }

        } finally {
            // Do NOT call vm.dispose() — disconnecting causes JDWP to re-listen
            // and print a confusing "Listening for transport..." message on stderr.
            // The connection is cleaned up automatically when the JVM exits.
        }

        // Terminate the target thread AFTER all JDI housekeeping is complete.
        // This is critical: if the target is the main thread, killing it may
        // terminate the JVM. We must ensure the handler has finished before
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

    /** Prefixes of classes whose frames should be excluded from the snapshot. */
    private static final String[] EXCLUDED_FRAME_PREFIXES = {
            "java/", "javax/", "jdk/", "sun/", "com/sun/",
    };

    /** Specific library classes to exclude (not the whole package — user subpackages may exist). */
    private static final String[] EXCLUDED_FRAME_CLASSES = {
            "ai/jacc/durableThreads/Durable",
            "ai/jacc/durableThreads/ThreadFreezer",
            "ai/jacc/durableThreads/ThreadRestorer",
            "ai/jacc/durableThreads/ReplayState",
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

        for (com.sun.jdi.LocalVariable jdiLocal : jdiLocals) {
            if (!jdiLocal.isVisible(frame)) continue;

            Value value = frame.getValue(jdiLocal);
            ObjectRef ref = heapWalker.capture(value);
            // JDI LocalVariable doesn't expose slot index directly;
            // use hashCode as a proxy, or get it from the variable table
            int slot = 0;
            try {
                // Attempt to get the slot via reflection on JDI impl
                java.lang.reflect.Method slotMethod = jdiLocal.getClass().getMethod("slot");
                slot = (int) slotMethod.invoke(jdiLocal);
            } catch (Exception ignored) {}
            result.add(new ai.jacc.durableThreads.snapshot.LocalVariable(
                    slot,
                    jdiLocal.name(),
                    jdiLocal.signature(),
                    ref));
        }
        return result;
    }

    /**
     * Thread-local flag to signal that a thread has been frozen and should terminate.
     */
    static final class FreezeFlag {
        private static final Set<Long> frozenThreads = Collections.synchronizedSet(new HashSet<>());

        static void markFrozen(Thread t) {
            frozenThreads.add(t.getId());
        }

        static boolean isFrozen(Thread t) {
            return frozenThreads.remove(t.getId());
        }
    }
}
