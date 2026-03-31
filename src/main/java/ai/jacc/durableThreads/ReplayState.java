package ai.jacc.durableThreads;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Thread-local state that drives the replay prologue during thread restoration.
 *
 * <p>During normal execution, {@link #isReplayThread()} returns false and the prologue
 * is a single not-taken branch. During replay, it provides the resume index for each
 * frame so the prologue can dispatch to the correct resume stub.</p>
 *
 * <h3>Why goLatch, restoreError, and restoreInProgress are static</h3>
 *
 * <p>During restore, the replay thread re-enters the user's original
 * {@code freeze()} call site. That call was compiled by the user — it has
 * no reference to any internal session or restore context object. The call
 * chain is: user code → {@code Durable.freeze()} → {@code ThreadFreezer.freeze()}
 * → {@code ReplayState.awaitGoLatch()}. There is no place to inject an
 * instance reference through this path, so these fields must be static
 * (or accessed via ThreadLocal).</p>
 *
 * <p><b>Thread safety:</b> This is safe because all freeze and restore
 * operations are serialized via {@code synchronized(Durable.class)}.
 * The entire {@code ThreadRestorer.restore()} call — including JDI worker
 * completion — runs under that monitor. By the time the monitor is released
 * and a second restore could begin, the first restore's JDI worker has
 * finished and the go-latch has been captured by value into the
 * {@link RestoredThread} instance. The replay thread's
 * {@code awaitGoLatch()} reads the latch into a local variable before
 * calling {@code await()}, so it is unaffected by any subsequent overwrite
 * of the static field by a later restore.</p>
 */
public final class ReplayState {

    private static final ThreadLocal<ReplayData> REPLAY = new ThreadLocal<>();

    /**
     * Maximum time (in seconds) that the replay thread will wait for the JDI
     * worker to release the go-latch before throwing a timeout error. This
     * prevents the replay thread from blocking forever if the JDI worker
     * crashes or fails to connect.
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
     * Go-latch: the replay thread blocks on this inside {@code freeze()} during
     * restore. After JDI sets all locals, this latch is captured by
     * {@link RestoredThread} and counted down by {@link RestoredThread#resume()}.
     */
    private static volatile CountDownLatch goLatch;

    /**
     * Thread-local flag indicating that the current thread is being restored
     * (not executing a real freeze). Checked by {@code freeze()} to decide
     * whether to block on the go-latch instead of actually freezing.
     */
    private static final ThreadLocal<Boolean> restoreInProgress = ThreadLocal.withInitial(() -> false);

    /**
     * If the JDI worker encounters a fatal error, it stores the message here
     * before releasing the go-latch. The replay thread checks this after waking
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
     * If true, the deepest frame's resume stub deactivates replay and jumps
     * into the original code where freeze() is called.
     */
    public static boolean isLastFrame() {
        ReplayData data = REPLAY.get();
        return data.currentFrame >= data.frameCount - 1;
    }

    /**
     * Activate replay mode for the current thread.
     * The go-latch will NOT block (suitable for in-process replay tests).
     *
     * @param resumeIndices invoke-index for each frame, bottom (0) to top
     */
    public static void activate(int[] resumeIndices) {
        REPLAY.set(new ReplayData(resumeIndices));
    }

    /**
     * Activate replay mode with a go-latch for single-pass restore.
     *
     * <p>The replay thread rebuilds the call stack via instrumented prologues,
     * then blocks inside {@code freeze()} on the go-latch. The JDI worker sets
     * all locals in all frames while the thread is blocked. When
     * {@link RestoredThread#resume()} releases the go-latch, {@code freeze()}
     * returns and user code continues.</p>
     *
     * @param resumeIndices invoke-index for each frame, bottom (0) to top
     */
    public static void activateWithLatch(int[] resumeIndices) {
        activateWithLatch(resumeIndices, null);
    }

    /**
     * Activate replay mode with a go-latch and pre-resolved frame receivers.
     *
     * @param resumeIndices invoke-index for each frame, bottom (0) to top
     * @param frameReceivers pre-resolved receiver ("this") for each frame, or null
     */
    public static void activateWithLatch(int[] resumeIndices, Object[] frameReceivers) {
        restoreError = null;
        goLatch = new CountDownLatch(1);
        REPLAY.set(new ReplayData(resumeIndices, frameReceivers));
    }

    /**
     * Signal a restore failure. The replay thread will throw after waking
     * from the go-latch instead of continuing with incorrect state.
     */
    public static void signalRestoreError(String message) {
        restoreError = message;
    }

    /**
     * Deactivate replay mode for the current thread.
     * Called by the deepest frame's resume stub before jumping to original code.
     */
    public static void deactivate() {
        REPLAY.remove();
    }

    /**
     * Mark the current thread as being in a restore operation.
     * Called before the replay thread starts so that {@code freeze()} knows
     * to block on the go-latch instead of actually freezing.
     */
    public static void setRestoreInProgress(boolean value) {
        restoreInProgress.set(value);
    }

    /**
     * Check if the current thread is being restored (not a real freeze).
     */
    public static boolean isRestoreInProgress() {
        return restoreInProgress.get();
    }

    /**
     * Block on the go-latch. Called by {@code freeze()} when it detects
     * that it's being called during a restore operation. Blocks until
     * {@link RestoredThread#resume()} counts the latch down.
     */
    public static void awaitGoLatch() {
        CountDownLatch latch = goLatch;
        if (latch != null) {
            try {
                if (!latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new RuntimeException(
                            "Thread restore timed out: go-latch was not released within "
                            + LATCH_TIMEOUT_SECONDS + " seconds.");
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

    /** Package-private access to go-latch for ThreadRestorer. */
    static CountDownLatch getGoLatch() {
        return goLatch;
    }

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
            return ai.jacc.durableThreads.internal.ObjenesisHolder.get().newInstance(clazz);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to create dummy receiver instance for replay of class '"
                    + className + "'. This class must be available on the classpath "
                    + "for thread restoration to proceed.", e);
        }
    }
}
