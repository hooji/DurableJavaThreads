package ai.jacc.durableThreads.snapshot;

import java.io.Serializable;
import java.util.Objects;

public final class LocalVariable implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int slot;
    private final String name;
    private final String typeDescriptor;
    private final ObjectRef value;

    public LocalVariable(int slot, String name, String typeDescriptor, ObjectRef value) {
        this.slot = slot;
        this.name = name;
        this.typeDescriptor = typeDescriptor;
        this.value = value;
    }

    public int slot() {
        return slot;
    }

    public String name() {
        return name;
    }

    public String typeDescriptor() {
        return typeDescriptor;
    }

    public ObjectRef value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocalVariable)) return false;
        LocalVariable that = (LocalVariable) o;
        return slot == that.slot
                && Objects.equals(name, that.name)
                && Objects.equals(typeDescriptor, that.typeDescriptor)
                && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slot, name, typeDescriptor, value);
    }

    @Override
    public String toString() {
        return "LocalVariable[slot=" + slot
                + ", name=" + name
                + ", typeDescriptor=" + typeDescriptor
                + ", value=" + value + "]";
    }
}
