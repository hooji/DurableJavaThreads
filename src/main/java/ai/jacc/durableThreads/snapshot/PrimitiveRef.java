package ai.jacc.durableThreads.snapshot;

import java.io.Serializable;
import java.util.Objects;

/**
 * Boxed primitive value.
 */
public final class PrimitiveRef implements ObjectRef {

    private static final long serialVersionUID = 1L;

    private final Serializable value;

    public PrimitiveRef(Serializable value) {
        this.value = value;
    }

    public Serializable value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PrimitiveRef)) return false;
        PrimitiveRef that = (PrimitiveRef) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "PrimitiveRef[value=" + value + "]";
    }
}
