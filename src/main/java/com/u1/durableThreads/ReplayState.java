package com.u1.durableThreads;

import java.util.concurrent.CountDownLatch;

/**
 * Thread-local state that drives the replay prologue during thread restoration.
 *
 * <p>During normal execution, {@link #isReplayThread()} returns false and the prologue
 * is a single not-taken branch. During replay, it provides the resume index for each
 * frame so the prologue can dispatch to the correct resume stub.</p>
 */
public final class ReplayState {

    private static final ThreadLocal<ReplayData> REPLAY = new ThreadLocal<>();

    /**
     * Latch that the replay thread blocks on inside {@link #resumePoint()}.
     * The JDI worker counts it down after setting locals and deactivating replay.
     */
    private static volatile CountDownLatch resumeLatch;

    /**
     * If the JDI worker encounters a fatal error, it stores the message here
     * before releasing the latch. The replay thread checks this after waking
     * and throws instead of continuing with incorrect state.
     */
    private static volatile String restoreError;

    /** Data held per-thread during replay. */
    static final class ReplayData {
        /** Resume invoke-index for each frame, bottom to top. */
        final int[] resumeIndices;
        /** Current frame depth (0 = bottom, increments as we go deeper). */
        int currentFrame;
        /** Total number of frames to replay. */
        final int frameCount;

        ReplayData(int[] resumeIndices) {
            this.resumeIndices = resumeIndices;
            this.frameCount = resumeIndices.length;
            this.currentFrame = 0;
        }
    }

    private ReplayState() {}

    /**
     * Check if the current thread is in replay mode.
     * Called at the top of every instrumented method's prologue.
     * During normal execution this returns false — a single not-taken branch.
     */
    public static boolean isReplayThread() {
        return REPLAY.get() != null;
    }

    /**
     * Get the resume invoke-index for the current frame during replay.
     * The prologue uses this to dispatch via tableswitch/lookupswitch.
     */
    public static int currentResumeIndex() {
        ReplayData data = REPLAY.get();
        return data.resumeIndices[data.currentFrame];
    }

    /**
     * Advance to the next (deeper) frame in the replay chain.
     * Called by resume stubs before invoking the next method.
     */
    public static void advanceFrame() {
        REPLAY.get().currentFrame++;
    }

    /**
     * Check if we've reached the deepest (top) frame.
     * If true, the resume stub should call {@link #resumePoint()} instead of
     * re-invoking the next method.
     */
    public static boolean isLastFrame() {
        ReplayData data = REPLAY.get();
        return data.currentFrame >= data.frameCount - 1;
    }

    /**
     * Called at the deepest frame during replay. This method <b>blocks</b> until
     * the JDI worker has:
     * <ol>
     *   <li>Suspended this thread</li>
     *   <li>Set local variables in all frames</li>
     *   <li>Deactivated replay mode</li>
     *   <li>Released the latch</li>
     * </ol>
     *
     * <p>After this method returns, the calling resume stub's {@code __skip} value
     * causes execution to skip the freeze invoke and continue with the restored
     * locals in the original code.</p>
     */
    public static void resumePoint() {
        CountDownLatch latch = resumeLatch;
        if (latch != null) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Check if the JDI worker signalled a restore failure
        String error = restoreError;
        if (error != null) {
            restoreError = null;
            throw new RuntimeException("Thread restore failed: " + error);
        }
    }

    /**
     * Release the latch, allowing the replay thread to continue past
     * {@link #resumePoint()}. Called by the JDI worker after setting locals
     * and deactivating replay mode.
     */
    public static void releaseResumePoint() {
        CountDownLatch latch = resumeLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * Activate replay mode for the current thread.
     * The resume point will NOT block (suitable for in-process replay tests).
     *
     * @param resumeIndices invoke-index for each frame, bottom (0) to top
     */
    public static void activate(int[] resumeIndices) {
        resumeLatch = null;
        REPLAY.set(new ReplayData(resumeIndices));
    }

    /**
     * Activate replay mode with a blocking resume point.
     * The replay thread will block inside {@link #resumePoint()} until
     * {@link #releaseResumePoint()} is called by the JDI worker.
     *
     * @param resumeIndices invoke-index for each frame, bottom (0) to top
     */
    public static void activateWithLatch(int[] resumeIndices) {
        restoreError = null;
        resumeLatch = new CountDownLatch(1);
        REPLAY.set(new ReplayData(resumeIndices));
    }

    /**
     * Signal a restore failure. The replay thread will throw after waking
     * from {@link #resumePoint()} instead of continuing with incorrect state.
     * Must be called BEFORE {@link #releaseResumePoint()}.
     */
    public static void signalRestoreError(String message) {
        restoreError = message;
    }

    /**
     * Deactivate replay mode for the current thread.
     * Called by JDI after all locals have been set.
     */
    public static void deactivate() {
        REPLAY.remove();
    }

    /**
     * Create a dummy (uninitialized) instance of the named class.
     * Used by resume stubs to provide a non-null receiver for virtual/interface calls
     * during replay. The instance is never actually used — the called method's prologue
     * will immediately dispatch to replay logic.
     *
     * @param className fully qualified class name (dot-separated)
     * @return an uninitialized instance
     * @throws RuntimeException if the class cannot be found or instantiated
     */
    public static Object dummyInstance(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            return unsafe.allocateInstance(clazz);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to create dummy receiver instance for replay of class '"
                    + className + "'. This class must be available on the classpath "
                    + "for thread restoration to proceed.", e);
        }
    }
}
