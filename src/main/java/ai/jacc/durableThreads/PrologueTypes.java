package ai.jacc.durableThreads;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.Objects;

/**
 * Shared data types used during bytecode buffering and emission by the
 * prologue injection pipeline.
 */
final class PrologueTypes {

    private PrologueTypes() {}

    /** Metadata about an invoke instruction found during buffering. */
    static final class InvokeInfo {
        final int index;
        final int opcode;
        final String owner;
        final String name;
        final String descriptor;
        final boolean isInterface;

        InvokeInfo(int index, int opcode, String owner, String name,
                   String descriptor, boolean isInterface) {
            this.index = index;
            this.opcode = opcode;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.isInterface = isInterface;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InvokeInfo)) return false;
            InvokeInfo that = (InvokeInfo) o;
            return index == that.index && opcode == that.opcode
                    && isInterface == that.isInterface
                    && Objects.equals(owner, that.owner)
                    && Objects.equals(name, that.name)
                    && Objects.equals(descriptor, that.descriptor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public String toString() {
            return "InvokeInfo[index=" + index + ", opcode=" + opcode
                    + ", owner=" + owner + ", name=" + name
                    + ", descriptor=" + descriptor + ", isInterface=" + isInterface + "]";
        }
    }

    /** Marker in the buffered ops list for a method invoke instruction. */
    static final class InvokeMarker implements Runnable {
        final int index;
        final int opcode;
        final String owner;
        final String name;
        final String descriptor;
        final boolean isInterface;

        InvokeMarker(int index, int opcode, String owner, String name,
                     String descriptor, boolean isInterface) {
            this.index = index;
            this.opcode = opcode;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.isInterface = isInterface;
        }

        @Override public void run() {
            throw new IllegalStateException("InvokeMarker must be handled by emitter");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InvokeMarker)) return false;
            InvokeMarker that = (InvokeMarker) o;
            return index == that.index && opcode == that.opcode
                    && isInterface == that.isInterface
                    && Objects.equals(owner, that.owner)
                    && Objects.equals(name, that.name)
                    && Objects.equals(descriptor, that.descriptor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public String toString() {
            return "InvokeMarker[index=" + index + ", opcode=" + opcode
                    + ", owner=" + owner + ", name=" + name
                    + ", descriptor=" + descriptor + ", isInterface=" + isInterface + "]";
        }
    }

    /** Marker in the buffered ops list for an invokedynamic instruction. */
    static final class InvokeDynamicMarker implements Runnable {
        final int index;
        final String name;
        final String descriptor;
        final Handle bootstrapMethodHandle;
        final Object[] bootstrapMethodArguments;

        InvokeDynamicMarker(int index, String name, String descriptor,
                            Handle bootstrapMethodHandle,
                            Object[] bootstrapMethodArguments) {
            this.index = index;
            this.name = name;
            this.descriptor = descriptor;
            this.bootstrapMethodHandle = bootstrapMethodHandle;
            this.bootstrapMethodArguments = bootstrapMethodArguments;
        }

        @Override public void run() {
            throw new IllegalStateException("InvokeDynamicMarker must be handled by emitter");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InvokeDynamicMarker)) return false;
            InvokeDynamicMarker that = (InvokeDynamicMarker) o;
            return index == that.index
                    && Objects.equals(name, that.name)
                    && Objects.equals(descriptor, that.descriptor)
                    && Objects.equals(bootstrapMethodHandle, that.bootstrapMethodHandle)
                    && Arrays.equals(bootstrapMethodArguments, that.bootstrapMethodArguments);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(index, name, descriptor, bootstrapMethodHandle);
            result = 31 * result + Arrays.hashCode(bootstrapMethodArguments);
            return result;
        }

        @Override
        public String toString() {
            return "InvokeDynamicMarker[index=" + index + ", name=" + name
                    + ", descriptor=" + descriptor + "]";
        }
    }

    /**
     * Marker in the buffered ops list for store instructions.
     * Records the opcode and slot so buildPerInvokeScopeMaps can infer slot types.
     */
    static final class StoreRecord implements Runnable {
        final int opcode;
        final int slot;

        StoreRecord(int opcode, int slot) {
            this.opcode = opcode;
            this.slot = slot;
        }

        char typeCategory() {
            switch (opcode) {
                case Opcodes.ISTORE: return 'I';
                case Opcodes.LSTORE: return 'J';
                case Opcodes.FSTORE: return 'F';
                case Opcodes.DSTORE: return 'D';
                case Opcodes.ASTORE: return 'A';
                default: return 'A';
            }
        }

        @Override public void run() { /* no-op marker — skipped during emit */ }
    }

    /** Marker in the buffered ops list for a label. */
    static final class LabelOp implements Runnable {
        final Label label;
        private final MethodVisitor target;

        LabelOp(Label label, MethodVisitor target) {
            this.label = label;
            this.target = target;
        }

        @Override public void run() { target.visitLabel(label); }
    }

    /** Collected local variable debug info entry. */
    static final class LocalVarInfo {
        private final String name;
        private final String desc;
        private final String sig;
        private final Label start;
        private final Label end;
        private final int index;

        LocalVarInfo(String name, String desc, String sig,
                     Label start, Label end, int index) {
            this.name = name;
            this.desc = desc;
            this.sig = sig;
            this.start = start;
            this.end = end;
            this.index = index;
        }

        String name() { return name; }
        String desc() { return desc; }
        String sig() { return sig; }
        Label start() { return start; }
        Label end() { return end; }
        int index() { return index; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LocalVarInfo)) return false;
            LocalVarInfo that = (LocalVarInfo) o;
            return index == that.index
                    && Objects.equals(name, that.name)
                    && Objects.equals(desc, that.desc)
                    && Objects.equals(sig, that.sig)
                    && Objects.equals(start, that.start)
                    && Objects.equals(end, that.end);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, desc, sig, start, end, index);
        }

        @Override
        public String toString() {
            return "LocalVarInfo[name=" + name + ", desc=" + desc + ", index=" + index + "]";
        }
    }
}
