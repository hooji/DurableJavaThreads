package ai.jacc.durableThreads.exception;

import java.util.List;

/**
 * Thrown when bytecode hashes in a snapshot don't match the currently loaded classes.
 * This indicates that application code has changed between freeze and restore.
 */
public class BytecodeMismatchException extends RuntimeException {

    private final List<String> changedMethods;

    public BytecodeMismatchException(List<String> changedMethods) {
        super("Bytecode has changed for methods: " + changedMethods);
        this.changedMethods = List.copyOf(changedMethods);
    }

    /** Fully qualified names of methods whose bytecode has changed. */
    public List<String> changedMethods() {
        return changedMethods;
    }
}
