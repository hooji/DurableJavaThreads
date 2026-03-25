package ai.jacc.durableThreads;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
     * Lock that serializes latch operations. Both the replay thread (localsReady,
     * resumePoint) and the JDI worker (release, activate) must hold this lock
     * when reading or writing latch references. This prevents the race where the
     * replay thread recreates a latch after the JDI worker already counted down
     * the old one, causing the next frame's localsReady() to block forever.
     */
    private static final Object LATCH_LOCK = new Object();

    /**
     * Maximum time (in seconds) that the replay thread will wait for the JDI
     * worker to release a latch before throwing a timeout error. This prevents
     * the replay thread from blocking forever if the JDI worker crashes or
     * fails to connect.
     *
     * <p>The default is 5 minutes, which is generous enough for restoring
     * frames with many local variables and large object graphs (each local
     * requires individual JDI setValue calls). Override via the system property
     * {@code durable.restore.timeout.seconds} for workloads that need more time.</p>
     */
    private static final long LATCH_TIMEOUT_SECONDS = getLatchTimeoutSeconds();

    private static long getLatchTimeoutSeconds() {
        String prop = System.getProperty("durable.restore.timeout.seconds");
        if (prop != null) {
            try {
                long val = Long.parseLong(prop.trim());
                if (val > 0) return val;
            } catch (NumberFormatException ignored) {
            }
        }
        return 300; // 5 minutes
    }

    /**
     * Latch that the replay thread blocks on inside {@link #resumePoint()}.
     * The JDI worker counts it down after deactivating replay mode (Phase 1).
     * Guarded by {@link #LATCH_LOCK}.
     */
    private static volatile CountDownLatch resumeLatch;

    /**
     * Latch that the replay thread blocks on inside {@link #localsReady()}.
     * The JDI worker counts it down after setting local variables via JDI (Phase 2).
     * Guarded by {@link #LATCH_LOCK}.
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
        /**
         * Pre-resolved receiver ("this") for each frame, bottom to top.
         * Used by resume stubs so they push the correct heap-restored receiver
         * instead of a dummy instance. May be null if receivers were not provided
         * (e.g. in unit tests). Individual entries may also be null for static
         * methods or frames where "this" was not captured.
         */
        final Object[] frameReceivers;

        ReplayData(int[] resumeIndices) {
            this(resumeIndices, null);
        }

        ReplayData(int[] resumeIndices, Object[] frameReceivers) {
            this.resumeIndices = resumeIndices;
            this.frameCount = resumeIndices.length;
            this.currentFrame = 0;
            this.frameReceivers = frameReceivers;
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
                if (!latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new RuntimeException(
                            "Thread restore timed out: JDI worker did not release "
                            + "resumePoint latch within " + LATCH_TIMEOUT_SECONDS
                            + " seconds. The JDI worker may have crashed or "
                            + "failed to connect.");
                }
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
     * Thread-local flag that controls whether the next {@link #localsReady()} call
     * should block. Armed by the resume stub before jumping to the post-invoke label.
     * This ensures that only the TARGET invoke's post-invoke localsReady() blocks —
     * subsequent invokes in the same frame's original code do NOT block.
     */
    private static final ThreadLocal<Boolean> localsAwaitArmed = ThreadLocal.withInitial(() -> false);

    /**
     * Arm the localsReady gate for the current thread. Called by resume stubs
     * before jumping to the post-invoke label in the original code.
     */
    public static void armLocalsAwait() {
        localsAwaitArmed.set(true);
    }

    /**
     * Called at every post-invoke label in the original code. Only blocks when
     * the localsReady gate is armed (by the resume stub). After blocking, the
     * gate is disarmed so subsequent post-invoke calls in the same frame's
     * original code pass through immediately.
     *
     * <p>During normal execution (no replay), the gate is never armed, so this
     * is a single thread-local read — effectively zero cost.</p>
     */
    public static void localsReady() {
        if (!localsAwaitArmed.get()) return;
        localsAwaitArmed.set(false);

        // Read the latch under lock, then await WITHOUT holding the lock
        // (holding it during await would deadlock with releaseLocalsReady).
        CountDownLatch latch;
        synchronized (LATCH_LOCK) {
            latch = localsLatch;
        }
        if (latch != null) {
            try {
                if (!latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new RuntimeException(
                            "Thread restore timed out: JDI worker did not release "
                            + "localsReady latch within " + LATCH_TIMEOUT_SECONDS
                            + " seconds. The JDI worker may have crashed or "
                            + "failed to set local variables.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Recreate the latch under lock so releaseLocalsReady() can't
            // race between this recreation and the next frame's await.
            synchronized (LATCH_LOCK) {
                localsLatch = new CountDownLatch(1);
            }
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
        synchronized (LATCH_LOCK) {
            CountDownLatch latch = localsLatch;
            if (latch != null) {
                latch.countDown();
            }
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
        activateWithLatch(resumeIndices, null);
    }

    /**
     * Activate replay mode with blocking latches and pre-resolved frame receivers.
     *
     * @param resumeIndices invoke-index for each frame, bottom (0) to top
     * @param frameReceivers pre-resolved receiver ("this") for each frame, or null
     */
    public static void activateWithLatch(int[] resumeIndices, Object[] frameReceivers) {
        synchronized (LATCH_LOCK) {
            restoreError = null;
            resumeLatch = new CountDownLatch(1);
            localsLatch = new CountDownLatch(1);
        }
        REPLAY.set(new ReplayData(resumeIndices, frameReceivers));
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

    /** Package-private access to latch lock for ThreadRestorer. */
    static Object getLatchLock() {
        return LATCH_LOCK;
    }

    /** Package-private access to locals latch for ThreadRestorer (go-latch capture). */
    static java.util.concurrent.CountDownLatch getLocalsLatch() {
        return localsLatch;
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
     * Resolve the receiver for the current frame during replay.
     *
     * <p>Resume stubs call this instead of {@link #dummyInstance(String)} to get
     * the correct heap-restored receiver for virtual/interface method calls.
     * Falls back to {@link #dummyInstance(String)} if no receiver was pre-stored
     * (e.g. in unit tests or for frames where "this" was not captured).</p>
     *
     * <p>Must be called AFTER {@link #advanceFrame()} — the current frame index
     * should point to the frame being re-invoked, not the caller.</p>
     *
     * @param className fully qualified class name (dot-separated), used as fallback
     * @return the pre-stored receiver, or a dummy instance as fallback
     */
    public static Object resolveReceiver(String className) {
        ReplayData data = REPLAY.get();
        if (data != null && data.frameReceivers != null
                && data.currentFrame < data.frameReceivers.length) {
            Object receiver = data.frameReceivers[data.currentFrame];
            if (receiver != null) return receiver;
        }
        return dummyInstance(className);
    }

    /**
     * Create a dummy (uninitialized) instance of the named class.
     * Used as a fallback when no pre-stored receiver is available.
     *
     * @param className fully qualified class name (dot-separated)
     * @return an uninitialized instance
     * @throws RuntimeException if the class cannot be found or instantiated
     */
    public static Object dummyInstance(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            return unsafe.allocateInstance(clazz);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to create dummy receiver instance for replay of class '"
                    + className + "'. This class must be available on the classpath "
                    + "for thread restoration to proceed.", e);
        }
    }
}
