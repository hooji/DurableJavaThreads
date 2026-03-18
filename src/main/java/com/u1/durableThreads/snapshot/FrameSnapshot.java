package com.u1.durableThreads.snapshot;

import java.io.Serializable;
import java.util.List;

public record FrameSnapshot(
        String className,
        String methodName,
        String methodSignature,
        int bytecodeIndex,
        byte[] bytecodeHash,
        List<LocalVariable> locals
) implements Serializable {
}
