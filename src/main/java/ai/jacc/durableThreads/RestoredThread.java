package ai.jacc.durableThreads;

/**
 * Handle to a restored thread whose call stack and local variables have been
 * fully reconstructed but which has not yet resumed executing user code.
 *
 * <p>The underlying thread is alive but parked on an internal latch. All JDI
 * work (stack replay, local variable setting) is complete by the time this
 * object is created. Call {@link #resume()} to release the thread and let it
 * continue from the freeze point.</p>
 *
 * <p>This handle is returned by the advanced restore overload
 * ({@link Durable#restore(ai.jacc.durableThreads.snapshot.ThreadSnapshot, java.util.Map, boolean, boolean)}).
 * The simple restore overloads automatically resume and join the thread.</p>
 */
public final class RestoredThread {

    private final Thread thread;
    private final java.util.concurrent.CountDownLatch goLatch;

    RestoredThread(Thread thread, java.util.concurrent.CountDownLatch goLatch) {
        this.thread = thread;
        this.goLatch = goLatch;
    }

    /**
     * Returns the underlying thread. The thread is alive but blocked until
     * {@link #resume()} is called.
     */
    public Thread thread() {
        return thread;
    }

    /**
     * Release the thread to continue executing user code from the freeze point.
     * This method returns immediately — the thread runs asynchronously.
     *
     * <p>Can only be called once (subsequent calls are harmless no-ops since
     * the latch is already at zero).</p>
     */
    public void resume() {
        goLatch.countDown();
    }
}
