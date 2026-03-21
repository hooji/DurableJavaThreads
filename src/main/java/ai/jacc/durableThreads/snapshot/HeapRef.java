package ai.jacc.durableThreads.snapshot;

/**
 * Reference to an object in the snapshot's heap.
 */
public final class HeapRef implements ObjectRef {

    private static final long serialVersionUID = 1L;

    private final long id;

    public HeapRef(long id) {
        this.id = id;
    }

    public long id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HeapRef)) return false;
        HeapRef that = (HeapRef) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "HeapRef[id=" + id + "]";
    }
}
