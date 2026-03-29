package ai.jacc.durableThreads;

import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * A {@link Consumer} that serializes a {@link ThreadSnapshot} to a file.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Durable.freeze(new SnapshotFileWriter("checkpoint.dat"));
 * }</pre>
 */
public final class SnapshotFileWriter implements Consumer<ThreadSnapshot> {

    private final Path path;

    /**
     * Create a writer that serializes snapshots to the given file path.
     * If filePath is null, the writer is a no-op — this happens during restore
     * when the replay thread re-enters freeze() with dummy (null) arguments
     * before JDI has set the real local variable values.
     *
     * @param filePath the file path as a string, or null for a no-op writer
     */
    public SnapshotFileWriter(String filePath) {
        this.path = filePath != null ? Paths.get(filePath) : null;
    }

    /**
     * @param path the file path, or null for a no-op writer
     * @see #SnapshotFileWriter(String)
     */
    public SnapshotFileWriter(Path path) {
        this.path = path;
    }

    @Override
    public void accept(ThreadSnapshot snapshot) {
        if (path == null) return; // no-op during restore replay
        try (OutputStream fos = Files.newOutputStream(path);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to write snapshot to " + path, e);
        }
    }
}
