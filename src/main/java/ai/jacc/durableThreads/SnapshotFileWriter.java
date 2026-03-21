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
     *
     * @param filePath the file path as a string
     */
    public SnapshotFileWriter(String filePath) {
        this(Paths.get(filePath));
    }

    /**
     * Create a writer that serializes snapshots to the given file path.
     *
     * @param path the file path
     */
    public SnapshotFileWriter(Path path) {
        this.path = path;
    }

    @Override
    public void accept(ThreadSnapshot snapshot) {
        try (OutputStream fos = Files.newOutputStream(path);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to write snapshot to " + path, e);
        }
    }
}
