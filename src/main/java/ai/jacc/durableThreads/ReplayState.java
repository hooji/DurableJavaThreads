package ai.jacc.durableThreads;

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
     * The JDI worker counts it down after deactivating replay mode (Phase 1).
     */
    private static volatile CountDownLatch resumeLatch;

    /**
     * Latch that the replay thread blocks on inside {@link #localsReady()}.
     * The JDI worker counts it down after setting local variables via JDI (Phase 2).
     */
    private static volatile CountDownLatch localsLatch;

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
     * Release the resume latch, allowing the replay thread to continue past
     * {@link #resumePoint()}. Called by the JDI worker after deactivating
     * replay mode (Phase 1).
     */
    public static void releaseResumePoint() {
        CountDownLatch latch = resumeLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * Called at the target invoke during replay. After the resume stub sets
     * {@code __skip} and jumps to original code, all invokes up to the target
     * are skipped. When the target invoke is reached, this method blocks until
     * the JDI worker has set local variables (Phase 2).
     *
     * <p>At this point the thread is executing in the original code section,
     * so all local variables are in scope and can be set via JDI.</p>
     */
    public static void localsReady() {
        CountDownLatch latch = localsLatch;
        if (latch != null) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Recreate the latch so the NEXT frame's localsReady() call blocks.
            // Each frame in the restored call stack hits localsReady() exactly once
            // as the stack unwinds (deepest first, then progressively shallower).
            localsLatch = new CountDownLatch(1);
        }
        // Check if the JDI worker signalled a restore failure
        String error = restoreError;
        if (error != null) {
            restoreError = null;
            throw new RuntimeException("Thread restore failed (Phase 2): " + error);
        }
    }

    /**
     * Release the locals latch, allowing the replay thread to continue past
     * {@link #localsReady()}. Called by the JDI worker after setting local
     * variables via JDI (Phase 2).
     */
    public static void releaseLocalsReady() {
        CountDownLatch latch = localsLatch;
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
        localsLatch = null;
        REPLAY.set(new ReplayData(resumeIndices));
    }

    /**
     * Activate replay mode with blocking latches for two-phase restore.
     * <ul>
     *   <li>Phase 1: The replay thread blocks inside {@link #resumePoint()} until
     *       the JDI worker deactivates replay and calls {@link #releaseResumePoint()}.</li>
     *   <li>Phase 2: The replay thread blocks inside {@link #localsReady()} until
     *       the JDI worker sets locals and calls {@link #releaseLocalsReady()}.</li>
     * </ul>
     *
     * @param resumeIndices invoke-index for each frame, bottom (0) to top
     */
    public static void activateWithLatch(int[] resumeIndices) {
        restoreError = null;
        resumeLatch = new CountDownLatch(1);
        localsLatch = new CountDownLatch(1);
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

    // --- Boxing/unboxing helpers ---
    // These MUST live in ReplayState so that RawBytecodeScanner filters them out.
    // If boxing/unboxing were done via direct calls to java.lang.Integer.valueOf() etc.
    // in the injected skip-check code, the scanner would count them as original-code
    // invokes, corrupting the invoke index mapping between freeze and restore.

    public static Object boxBoolean(boolean v) { return Boolean.valueOf(v); }
    public static Object boxByte(byte v) { return Byte.valueOf(v); }
    public static Object boxChar(char v) { return Character.valueOf(v); }
    public static Object boxShort(short v) { return Short.valueOf(v); }
    public static Object boxInt(int v) { return Integer.valueOf(v); }
    public static Object boxLong(long v) { return Long.valueOf(v); }
    public static Object boxFloat(float v) { return Float.valueOf(v); }
    public static Object boxDouble(double v) { return Double.valueOf(v); }

    public static boolean unboxBoolean(Object o) { return ((Boolean) o).booleanValue(); }
    public static byte unboxByte(Object o) { return ((Byte) o).byteValue(); }
    public static char unboxChar(Object o) { return ((Character) o).charValue(); }
    public static short unboxShort(Object o) { return ((Short) o).shortValue(); }
    public static int unboxInt(Object o) { return ((Integer) o).intValue(); }
    public static long unboxLong(Object o) { return ((Long) o).longValue(); }
    public static float unboxFloat(Object o) { return ((Float) o).floatValue(); }
    public static double unboxDouble(Object o) { return ((Double) o).doubleValue(); }

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
