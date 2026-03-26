package ai.jacc.durableThreads;

import ai.jacc.durableThreads.exception.AgentNotLoadedException;
import ai.jacc.durableThreads.exception.ThreadFrozenError;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
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

    // ===================================================================
    // Freeze
    // ===================================================================

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
        freeze(handler, null);
    }

    /**
     * Freeze the current thread, serializing the snapshot to a file.
     *
     * <p>Equivalent to {@code freeze(new SnapshotFileWriter(filePath))}.</p>
     *
     * @param filePath the file to write the snapshot to
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static void freeze(String filePath) {
        // During restore, args may be dummy nulls — skip to the core freeze
        if (ReplayState.isRestoreInProgress()) {
            ThreadFreezer.freeze(null, null);
            return;
        }
        freeze(new SnapshotFileWriter(filePath), null);
    }

    /**
     * Freeze the current thread, serializing the snapshot to a file.
     *
     * <p>Equivalent to {@code freeze(new SnapshotFileWriter(path))}.</p>
     *
     * @param path the file to write the snapshot to
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static void freeze(Path path) {
        // During restore, args may be dummy nulls — skip to the core freeze
        if (ReplayState.isRestoreInProgress()) {
            ThreadFreezer.freeze(null, null);
            return;
        }
        freeze(new SnapshotFileWriter(path), null);
    }

    /**
     * Freeze the current thread with named heap objects.
     *
     * <p>Named objects are tagged in the snapshot so they can be replaced with
     * live objects during restore. The {@code "this"} reference of the calling
     * frame is always auto-named unless explicitly provided in the map.</p>
     *
     * <p><b>Thread safety:</b> All freeze and restore operations are serialized
     * via {@code synchronized(Durable.class)}. Only one freeze or restore may
     * be in progress at a time. This is a fundamental constraint of the JDI
     * self-attach architecture.</p>
     *
     * @param handler receives the captured snapshot for persistence
     * @param namedObjects map of name → object for objects to tag in the snapshot
     *        (may be null)
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static void freeze(Consumer<ThreadSnapshot> handler, Map<String, Object> namedObjects) {
        // During restore, the deepest frame's code calls freeze(). Detect this
        // BEFORE acquiring the Durable.class monitor — the restore() caller
        // already holds it, so entering synchronized would deadlock.
        if (ReplayState.isRestoreInProgress()) {
            ThreadFreezer.freeze(handler, namedObjects);
            return;
        }

        if (!DurableAgent.isLoaded()) {
            throw new AgentNotLoadedException();
        }

        synchronized (Durable.class) {
            ThreadFreezer.freeze(handler, namedObjects);
        }
    }

    /**
     * Freeze the current thread to a file with named heap objects.
     *
     * @param filePath the file to write the snapshot to
     * @param namedObjects map of name → object for objects to tag in the snapshot
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static void freeze(String filePath, Map<String, Object> namedObjects) {
        if (ReplayState.isRestoreInProgress()) {
            ThreadFreezer.freeze(null, null);
            return;
        }
        freeze(new SnapshotFileWriter(filePath), namedObjects);
    }

    /**
     * Freeze the current thread to a file with named heap objects.
     *
     * @param path the file to write the snapshot to
     * @param namedObjects map of name → object for objects to tag in the snapshot
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static void freeze(Path path, Map<String, Object> namedObjects) {
        if (ReplayState.isRestoreInProgress()) {
            ThreadFreezer.freeze(null, null);
            return;
        }
        freeze(new SnapshotFileWriter(path), namedObjects);
    }

    // ===================================================================
    // Restore — simple overloads (restore, resume, and run to completion)
    // ===================================================================

    public static void restore(ThreadSnapshot snapshot) {
        restore(snapshot, null, true, true);
    }

    public static void restore(String filePath) {
        restore(Paths.get(filePath));
    }

    public static void restore(Path path) {
        restore(loadSnapshot(path), null, true, true);
    }

    public static void restore(ThreadSnapshot snapshot, Map<String, Object> namedReplacements) {
        restore(snapshot, namedReplacements, true, true);
    }

    public static void restore(String filePath, Map<String, Object> namedReplacements) {
        restore(Paths.get(filePath), namedReplacements);
    }

    public static void restore(Path path, Map<String, Object> namedReplacements) {
        restore(loadSnapshot(path), namedReplacements, true, true);
    }

    // ===================================================================
    // Restore — advanced overload with explicit control
    // ===================================================================

    /**
     * Restore a frozen thread with explicit control over resumption and waiting.
     *
     * <p>All JDI work (stack replay, local variable setting) completes within
     * this method. The returned {@link RestoredThread} holds a fully restored
     * thread that is alive but parked on an internal latch.</p>
     *
     * <p><b>Thread safety:</b> All freeze and restore operations are serialized
     * via {@code synchronized(Durable.class)}. Only one freeze or restore may
     * be in progress at a time.</p>
     *
     * @param snapshot the captured thread state
     * @param namedReplacements map of name → live object to substitute (may be null)
     * @param resume if {@code true}, the thread is resumed before returning
     * @param awaitCompletion if {@code true} (and {@code resume} is also true),
     *        blocks until the restored thread completes
     * @return a {@link RestoredThread} handle
     */
    public static RestoredThread restore(ThreadSnapshot snapshot,
                                         Map<String, Object> namedReplacements,
                                         boolean resume, boolean awaitCompletion) {
        if (!DurableAgent.isLoaded()) {
            throw new AgentNotLoadedException();
        }

        RestoredThread restoredThread;
        synchronized (Durable.class) {
            restoredThread = ThreadRestorer.restore(snapshot, namedReplacements);
        }

        if (resume) {
            final Throwable[] threadError = {null};
            if (awaitCompletion) {
                restoredThread.thread().setUncaughtExceptionHandler(
                        new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread t, Throwable e) {
                        threadError[0] = e;
                    }
                });
            }
            restoredThread.resume();
            if (awaitCompletion) {
                try {
                    restoredThread.thread().join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                            "Interrupted while waiting for restored thread", e);
                }
                if (threadError[0] != null) {
                    if (threadError[0] instanceof RuntimeException) {
                        throw (RuntimeException) threadError[0];
                    }
                    throw new RuntimeException("Restored thread failed", threadError[0]);
                }
            }
        }

        return restoredThread;
    }

    // ===================================================================
    // Private helpers
    // ===================================================================

    private static ThreadSnapshot loadSnapshot(Path path) {
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(path))) {
            return (ThreadSnapshot) ois.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read snapshot from " + path, e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "Failed to deserialize snapshot from " + path
                    + ": snapshot class not found", e);
        }
    }
}
