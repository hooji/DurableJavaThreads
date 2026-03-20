package ai.jacc.durableThreads.snapshot;

import java.io.Serializable;

public record LocalVariable(
        int slot,
        String name,
        String typeDescriptor,
        ObjectRef value
) implements Serializable {
}
