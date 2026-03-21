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
     * Freeze the current thread, serializing the snapshot to a file.
     *
     * <p>Equivalent to {@code freeze(new SnapshotFileWriter(filePath))}.</p>
     *
     * @param filePath the file to write the snapshot to
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static void freeze(String filePath) {
        freeze(new SnapshotFileWriter(filePath));
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
        freeze(new SnapshotFileWriter(path));
    }

    /**
     * Freeze the current thread with named heap objects.
     *
     * <p>Named objects are tagged in the snapshot so they can be replaced with
     * live objects during restore. The {@code "this"} reference of the calling
     * frame is always auto-named unless explicitly provided in the map.</p>
     *
     * @param handler receives the captured snapshot for persistence
     * @param namedObjects map of name → object for objects to tag in the snapshot
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static void freeze(Consumer<ThreadSnapshot> handler, Map<String, Object> namedObjects) {
        if (!DurableAgent.isLoaded()) {
            throw new AgentNotLoadedException();
        }

        ThreadFreezer.freeze(handler, namedObjects);
    }

    /**
     * Freeze the current thread to a file with named heap objects.
     *
     * @param filePath the file to write the snapshot to
     * @param namedObjects map of name → object for objects to tag in the snapshot
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static void freeze(String filePath, Map<String, Object> namedObjects) {
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
        freeze(new SnapshotFileWriter(path), namedObjects);
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
     * @throws ai.jacc.durableThreads.exception.BytecodeMismatchException if bytecode has changed
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static Thread restore(ThreadSnapshot snapshot) {
        if (!DurableAgent.isLoaded()) {
            throw new AgentNotLoadedException();
        }

        return ThreadRestorer.restore(snapshot);
    }

    /**
     * Restore a frozen thread from a snapshot, optionally starting it.
     *
     * @param snapshot the captured thread state
     * @param startThread if {@code true}, the thread is started before returning
     * @return the restored Thread
     * @throws ai.jacc.durableThreads.exception.BytecodeMismatchException if bytecode has changed
     * @throws AgentNotLoadedException if the durable agent is not loaded
     */
    public static Thread restore(ThreadSnapshot snapshot, boolean startThread) {
        Thread thread = restore(snapshot);
        if (startThread) {
            thread.start();
        }
        return thread;
    }

    /**
     * Restore a frozen thread from a snapshot, optionally starting it and
     * waiting for it to finish.
     *
     * <p>If the restored thread throws an exception, it is rethrown as a
     * {@link RuntimeException} from this method.</p>
     *
     * @param snapshot the captured thread state
     * @param startThread if {@code true}, the thread is started
     * @param waitForThreadToFinish if {@code true} (and {@code startThread} is also
     *        {@code true}), blocks until the restored thread completes
     * @return the restored Thread
     * @throws ai.jacc.durableThreads.exception.BytecodeMismatchException if bytecode has changed
     * @throws AgentNotLoadedException if the durable agent is not loaded
     * @throws RuntimeException if the restored thread failed during execution
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static Thread restore(ThreadSnapshot snapshot, boolean startThread,
                                 boolean waitForThreadToFinish) throws InterruptedException {
        Thread thread = restore(snapshot);
        final Throwable[] threadError = {null};
        if (startThread && waitForThreadToFinish) {
            thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    threadError[0] = e;
                }
            });
        }
        if (startThread) {
            thread.start();
        }
        if (startThread && waitForThreadToFinish) {
            thread.join();
            if (threadError[0] != null) {
                if (threadError[0] instanceof RuntimeException) {
                    throw (RuntimeException) threadError[0];
                }
                throw new RuntimeException("Restored thread failed", threadError[0]);
            }
        }
        return thread;
    }

    /**
     * Restore a frozen thread from a snapshot file.
     *
     * <p>Deserializes the {@link ThreadSnapshot} from the file and restores it.</p>
     *
     * @param filePath the file containing the serialized snapshot
     * @return a Thread (not yet started) that will resume from the freeze point
     * @throws ai.jacc.durableThreads.exception.BytecodeMismatchException if bytecode has changed
     * @throws AgentNotLoadedException if the durable agent is not loaded
     * @throws UncheckedIOException if the file cannot be read
     */
    public static Thread restore(String filePath) {
        return restore(Paths.get(filePath));
    }

    /**
     * Restore a frozen thread from a snapshot file, optionally starting it.
     *
     * @param filePath the file containing the serialized snapshot
     * @param startThread if {@code true}, the thread is started before returning
     * @return the restored Thread
     * @throws ai.jacc.durableThreads.exception.BytecodeMismatchException if bytecode has changed
     * @throws AgentNotLoadedException if the durable agent is not loaded
     * @throws UncheckedIOException if the file cannot be read
     */
    public static Thread restore(String filePath, boolean startThread) {
        return restore(Paths.get(filePath), startThread);
    }

    /**
     * Restore a frozen thread from a snapshot file, optionally starting it and
     * waiting for it to finish.
     *
     * @param filePath the file containing the serialized snapshot
     * @param startThread if {@code true}, the thread is started
     * @param waitForThreadToFinish if {@code true} (and {@code startThread} is also
     *        {@code true}), blocks until the restored thread completes
     * @return the restored Thread
     * @throws ai.jacc.durableThreads.exception.BytecodeMismatchException if bytecode has changed
     * @throws AgentNotLoadedException if the durable agent is not loaded
     * @throws UncheckedIOException if the file cannot be read
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static Thread restore(String filePath, boolean startThread,
                                 boolean waitForThreadToFinish) throws InterruptedException {
        return restore(Paths.get(filePath), startThread, waitForThreadToFinish);
    }

    /**
     * Restore a frozen thread from a snapshot file.
     *
     * <p>Deserializes the {@link ThreadSnapshot} from the file and restores it.</p>
     *
     * @param path the file containing the serialized snapshot
     * @return a Thread (not yet started) that will resume from the freeze point
     * @throws ai.jacc.durableThreads.exception.BytecodeMismatchException if bytecode has changed
     * @throws AgentNotLoadedException if the durable agent is not loaded
     * @throws UncheckedIOException if the file cannot be read
     */
    public static Thread restore(Path path) {
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(path))) {
            ThreadSnapshot snapshot = (ThreadSnapshot) ois.readObject();
            return restore(snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read snapshot from " + path, e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "Failed to deserialize snapshot from " + path
                    + ": snapshot class not found", e);
        }
    }

    /**
     * Restore a frozen thread from a snapshot file, optionally starting it.
     *
     * @param path the file containing the serialized snapshot
     * @param startThread if {@code true}, the thread is started before returning
     * @return the restored Thread
     * @throws ai.jacc.durableThreads.exception.BytecodeMismatchException if bytecode has changed
     * @throws AgentNotLoadedException if the durable agent is not loaded
     * @throws UncheckedIOException if the file cannot be read
     */
    public static Thread restore(Path path, boolean startThread) {
        Thread thread = restore(path);
        if (startThread) {
            thread.start();
        }
        return thread;
    }

    /**
     * Restore a frozen thread from a snapshot file, optionally starting it and
     * waiting for it to finish.
     *
     * @param path the file containing the serialized snapshot
     * @param startThread if {@code true}, the thread is started
     * @param waitForThreadToFinish if {@code true} (and {@code startThread} is also
     *        {@code true}), blocks until the restored thread completes
     * @return the restored Thread
     * @throws ai.jacc.durableThreads.exception.BytecodeMismatchException if bytecode has changed
     * @throws AgentNotLoadedException if the durable agent is not loaded
     * @throws UncheckedIOException if the file cannot be read
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static Thread restore(Path path, boolean startThread,
                                 boolean waitForThreadToFinish) throws InterruptedException {
        try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(
                Files.newInputStream(path))) {
            ThreadSnapshot snapshot = (ThreadSnapshot) ois.readObject();
            return restore(snapshot, startThread, waitForThreadToFinish);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(
                    "Failed to read snapshot from " + path, e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "Failed to deserialize snapshot from " + path
                    + ": snapshot class not found", e);
        }
    }

    // ===================================================================
    // Restore overloads with named replacement objects
    // ===================================================================

    /**
     * Restore a frozen thread from a snapshot with named replacement objects.
     *
     * <p>Named objects in the snapshot that match keys in {@code namedReplacements}
     * are replaced with the live objects from the map instead of being recreated.
     * This allows the restored thread to reference existing live objects.</p>
     *
     * @param snapshot the captured thread state
     * @param namedReplacements map of name → live object to substitute
     * @return a Thread (not yet started) that will resume from the freeze point
     * @throws RuntimeException if namedReplacements contains names not in the snapshot
     */
    public static Thread restore(ThreadSnapshot snapshot, Map<String, Object> namedReplacements) {
        if (!DurableAgent.isLoaded()) {
            throw new AgentNotLoadedException();
        }
        return ThreadRestorer.restore(snapshot, namedReplacements);
    }

    /**
     * Restore a frozen thread from a snapshot with named replacements, optionally
     * starting it and waiting for it to finish.
     *
     * @param snapshot the captured thread state
     * @param namedReplacements map of name → live object to substitute
     * @param startThread if {@code true}, the thread is started
     * @param waitForThreadToFinish if {@code true} (and {@code startThread} is also
     *        {@code true}), blocks until the restored thread completes
     * @return the restored Thread
     * @throws RuntimeException if namedReplacements contains names not in the snapshot
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static Thread restore(ThreadSnapshot snapshot, Map<String, Object> namedReplacements,
                                 boolean startThread,
                                 boolean waitForThreadToFinish) throws InterruptedException {
        Thread thread = restore(snapshot, namedReplacements);
        final Throwable[] threadError = {null};
        if (startThread && waitForThreadToFinish) {
            thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    threadError[0] = e;
                }
            });
        }
        if (startThread) {
            thread.start();
        }
        if (startThread && waitForThreadToFinish) {
            thread.join();
            if (threadError[0] != null) {
                if (threadError[0] instanceof RuntimeException) {
                    throw (RuntimeException) threadError[0];
                }
                throw new RuntimeException("Restored thread failed", threadError[0]);
            }
        }
        return thread;
    }

    /**
     * Restore from a file with named replacement objects.
     *
     * @param filePath the file containing the serialized snapshot
     * @param namedReplacements map of name → live object to substitute
     * @return a Thread (not yet started) that will resume from the freeze point
     */
    public static Thread restore(String filePath, Map<String, Object> namedReplacements) {
        return restore(Paths.get(filePath), namedReplacements);
    }

    /**
     * Restore from a file with named replacement objects, optionally starting
     * and waiting.
     *
     * @param filePath the file containing the serialized snapshot
     * @param namedReplacements map of name → live object to substitute
     * @param startThread if {@code true}, the thread is started
     * @param waitForThreadToFinish if {@code true}, blocks until complete
     * @return the restored Thread
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static Thread restore(String filePath, Map<String, Object> namedReplacements,
                                 boolean startThread,
                                 boolean waitForThreadToFinish) throws InterruptedException {
        return restore(Paths.get(filePath), namedReplacements, startThread, waitForThreadToFinish);
    }

    /**
     * Restore from a file with named replacement objects.
     *
     * @param path the file containing the serialized snapshot
     * @param namedReplacements map of name → live object to substitute
     * @return a Thread (not yet started) that will resume from the freeze point
     */
    public static Thread restore(Path path, Map<String, Object> namedReplacements) {
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(path))) {
            ThreadSnapshot snapshot = (ThreadSnapshot) ois.readObject();
            return restore(snapshot, namedReplacements);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read snapshot from " + path, e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "Failed to deserialize snapshot from " + path
                    + ": snapshot class not found", e);
        }
    }

    /**
     * Restore from a file with named replacement objects, optionally starting
     * and waiting.
     *
     * @param path the file containing the serialized snapshot
     * @param namedReplacements map of name → live object to substitute
     * @param startThread if {@code true}, the thread is started
     * @param waitForThreadToFinish if {@code true}, blocks until complete
     * @return the restored Thread
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static Thread restore(Path path, Map<String, Object> namedReplacements,
                                 boolean startThread,
                                 boolean waitForThreadToFinish) throws InterruptedException {
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(path))) {
            ThreadSnapshot snapshot = (ThreadSnapshot) ois.readObject();
            return restore(snapshot, namedReplacements, startThread, waitForThreadToFinish);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read snapshot from " + path, e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "Failed to deserialize snapshot from " + path
                    + ": snapshot class not found", e);
        }
    }
}
