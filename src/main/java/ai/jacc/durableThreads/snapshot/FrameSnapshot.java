package ai.jacc.durableThreads.snapshot;

import java.io.Serializable;
import java.util.List;

public record FrameSnapshot(
        String className,
        String methodName,
        String methodSignature,
        int bytecodeIndex,
        int invokeIndex,
        byte[] bytecodeHash,
        List<LocalVariable> locals
) implements Serializable {
}
