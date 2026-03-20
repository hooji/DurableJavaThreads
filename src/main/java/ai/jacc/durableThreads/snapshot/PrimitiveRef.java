package ai.jacc.durableThreads.snapshot;

import java.io.Serializable;

/**
 * Boxed primitive value.
 */
public record PrimitiveRef(Serializable value) implements ObjectRef {
}
