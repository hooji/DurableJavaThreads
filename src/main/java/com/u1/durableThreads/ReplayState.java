package com.u1.durableThreads;

/**
 * Thread-local state that drives the replay prologue during thread restoration.
 *
 * <p>During normal execution, {@link #isReplayThread()} returns false and the prologue
 * is a single not-taken branch. During replay, it provides the resume index for each
 * frame so the prologue can dispatch to the correct resume stub.</p>
 */
public final class ReplayState {

    private static final ThreadLocal<ReplayData> REPLAY = new ThreadLocal<>();

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
     * Called at the deepest frame during replay. This is where JDI sets a breakpoint,
     * suspends the thread, writes local variables into all frames, deactivates replay
     * mode, and resumes the thread.
     */
    public static void resumePoint() {
        // Intentionally empty. JDI uses this as a breakpoint location.
        // After JDI sets locals and deactivates replay, the thread resumes
        // and execution continues from after the invoke in the deepest frame's
        // resume stub, which gotos the original code.
    }

    /**
     * Activate replay mode for the current thread.
     *
     * @param resumeIndices invoke-index for each frame, bottom (0) to top
     */
    public static void activate(int[] resumeIndices) {
        REPLAY.set(new ReplayData(resumeIndices));
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
     * @return an uninitialized instance, or null if allocation fails
     */
    public static Object dummyInstance(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            // Use sun.misc.Unsafe to allocate without calling a constructor
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            return unsafe.allocateInstance(clazz);
        } catch (Exception e) {
            // If we can't allocate, return null and hope for the best.
            // The invoked method's prologue will fire before any fields are accessed.
            return null;
        }
    }
}
