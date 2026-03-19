package com.u1.durableThreads;

import com.u1.durableThreads.exception.AgentNotLoadedException;
import com.u1.durableThreads.exception.ThreadFrozenError;
import com.u1.durableThreads.snapshot.ThreadSnapshot;

import java.util.function.Consumer;

/**
 * Main API for durable threads — freeze, serialize, and resume thread execution.
 *
 * <h2>Freezing</h2>
 * <pre>{@code
 * Durable.freeze(snapshot -> {
 *     persistence.save(workflowId, serialize(snapshot));
 * });
 * // This line ONLY executes in restored instances.
 * continueAfterRestore();
 * }</pre>
 *
 * <h2>Restoring</h2>
 * <pre>{@code
 * ThreadSnapshot snapshot = deserialize(persistence.load(workflowId));
 * Thread restored = Durable.restore(snapshot);
 * restored.start();  // thread resumes from freeze point
 * }</pre>
 */
public final class Durable {

    private Durable() {}

    /**
     * Freeze the current thread.
     *
     * <p>The handler is called (from a different thread) with the captured snapshot.
     * After the handler returns, the calling thread is terminated via
     * {@link ThreadFrozenError}.</p>
     *
     * <p>This method only returns normally in <b>restored</b> threads —
     * the original thread is always terminated after freezing.</p>
     *
     * @param handler receives the captured snapshot for persistence
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static void freeze(Consumer<ThreadSnapshot> handler) {
        if (!DurableAgent.isLoaded()) {
            throw new AgentNotLoadedException();
        }

        ThreadFreezer.freeze(handler);
    }

    /**
     * Restore a frozen thread from a snapshot.
     *
     * <p>Returns a {@link Thread} that can be started. When started, the thread
     * replays the call stack from the snapshot and resumes execution from the
     * point where {@link #freeze(Consumer)} was called.</p>
     *
     * @param snapshot the captured thread state
     * @return a Thread (not yet started) that will resume from the freeze point
     * @throws com.u1.durableThreads.exception.BytecodeMismatchException if bytecode has changed
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static Thread restore(ThreadSnapshot snapshot) {
        if (!DurableAgent.isLoaded()) {
            throw new AgentNotLoadedException();
        }

        return ThreadRestorer.restore(snapshot);
    }
}
