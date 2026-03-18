package com.u1.durableThreads.snapshot;

import java.io.Serializable;
import java.util.Map;

public record ObjectSnapshot(
        long id,
        String className,
        ObjectKind kind,
        Map<String, ObjectRef> fields,
        ObjectRef[] arrayElements
) implements Serializable {
}
