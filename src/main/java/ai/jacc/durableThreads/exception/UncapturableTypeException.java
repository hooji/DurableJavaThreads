package ai.jacc.durableThreads.exception;

/**
 * Thrown when a thread being frozen contains an object of a type that cannot
 * be captured and restored with 100% semantic correctness.
 *
 * <p>This fail-fast behavior prevents silent data loss: rather than freezing
 * a snapshot that would produce incorrect behavior on restore, we abort the
 * freeze and tell the user exactly which type caused the problem and how to
 * fix it.</p>
 *
 * <p>Common causes:</p>
 * <ul>
 *   <li>JDK-internal types whose fields cannot be read via JDI
 *       (e.g., {@code java.util.Optional}, {@code java.util.regex.Pattern})</li>
 *   <li>Unmodifiable collection wrappers whose immutability contract cannot
 *       be preserved (e.g., {@code Collections.unmodifiableList()})</li>
 *   <li>Types with native resources that cannot be serialized
 *       (e.g., {@code java.lang.Thread}, {@code java.io.FileInputStream})</li>
 * </ul>
 */
public class UncapturableTypeException extends RuntimeException {

    private final String typeName;

    public UncapturableTypeException(String typeName, String advice) {
        super("Cannot freeze thread: encountered object of type '" + typeName
                + "' which cannot be captured and restored with correct semantics. "
                + advice);
        this.typeName = typeName;
    }

    /** The fully qualified class name of the unsupported type. */
    public String typeName() {
        return typeName;
    }
}
