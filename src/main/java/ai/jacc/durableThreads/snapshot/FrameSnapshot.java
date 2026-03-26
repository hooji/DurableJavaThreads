package ai.jacc.durableThreads.snapshot;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class FrameSnapshot implements Serializable {

    private static final long serialVersionUID = 2L;

    private final String className;
    private final String methodName;
    private final String methodSignature;
    private final int bytecodeIndex;
    private final int invokeIndex;
    private final byte[] bytecodeHash;
    private final List<LocalVariable> locals;

    /**
     * If this frame was entered through a lambda dispatch ($$Lambda), this is
     * the fully-qualified interface name that the lambda implements (e.g.,
     * "java.lang.Runnable"). Null for normal (non-lambda) frames.
     *
     * <p>At restore time, a dynamic proxy implementing this interface is created
     * and used as the receiver for the caller frame's interface invoke.</p>
     */
    private final String lambdaBridgeInterface;

    public FrameSnapshot(String className, String methodName, String methodSignature,
                         int bytecodeIndex, int invokeIndex, byte[] bytecodeHash,
                         List<LocalVariable> locals) {
        this(className, methodName, methodSignature, bytecodeIndex, invokeIndex,
                bytecodeHash, locals, null);
    }

    public FrameSnapshot(String className, String methodName, String methodSignature,
                         int bytecodeIndex, int invokeIndex, byte[] bytecodeHash,
                         List<LocalVariable> locals, String lambdaBridgeInterface) {
        this.className = className;
        this.methodName = methodName;
        this.methodSignature = methodSignature;
        this.bytecodeIndex = bytecodeIndex;
        this.invokeIndex = invokeIndex;
        this.bytecodeHash = bytecodeHash;
        this.locals = locals;
        this.lambdaBridgeInterface = lambdaBridgeInterface;
    }

    public String className() {
        return className;
    }

    public String methodName() {
        return methodName;
    }

    public String methodSignature() {
        return methodSignature;
    }

    public int bytecodeIndex() {
        return bytecodeIndex;
    }

    public int invokeIndex() {
        return invokeIndex;
    }

    public byte[] bytecodeHash() {
        return bytecodeHash;
    }

    public List<LocalVariable> locals() {
        return locals;
    }

    /**
     * Returns the functional interface name if this frame was entered through
     * a lambda dispatch, or null for normal frames.
     */
    public String lambdaBridgeInterface() {
        return lambdaBridgeInterface;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FrameSnapshot)) return false;
        FrameSnapshot that = (FrameSnapshot) o;
        return bytecodeIndex == that.bytecodeIndex
                && invokeIndex == that.invokeIndex
                && Objects.equals(className, that.className)
                && Objects.equals(methodName, that.methodName)
                && Objects.equals(methodSignature, that.methodSignature)
                && Arrays.equals(bytecodeHash, that.bytecodeHash)
                && Objects.equals(locals, that.locals);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(className, methodName, methodSignature,
                bytecodeIndex, invokeIndex, locals);
        result = 31 * result + Arrays.hashCode(bytecodeHash);
        return result;
    }

    @Override
    public String toString() {
        return "FrameSnapshot[className=" + className
                + ", methodName=" + methodName
                + ", methodSignature=" + methodSignature
                + ", bytecodeIndex=" + bytecodeIndex
                + ", invokeIndex=" + invokeIndex
                + ", bytecodeHash=" + Arrays.toString(bytecodeHash)
                + ", locals=" + locals + "]";
    }
}
