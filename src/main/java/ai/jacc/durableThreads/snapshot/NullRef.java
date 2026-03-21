package ai.jacc.durableThreads.snapshot;

import java.util.Objects;

/**
 * Null reference.
 */
public final class NullRef implements ObjectRef {

    private static final long serialVersionUID = 1L;

    public NullRef() {
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
