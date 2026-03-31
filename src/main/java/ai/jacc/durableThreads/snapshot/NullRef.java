package ai.jacc.durableThreads.snapshot;

import java.util.Objects;

/**
 * Null reference. Use {@link #INSTANCE} instead of creating new instances.
 */
public final class NullRef implements ObjectRef {

    private static final long serialVersionUID = 1L;

    /** Shared singleton instance. */
    public static final NullRef INSTANCE = new NullRef();

    public NullRef() {
    }

    /** Preserve singleton identity after deserialization. */
    private Object readResolve() {
        return INSTANCE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof NullRef;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public String toString() {
        return "NullRef[]";
    }
}
