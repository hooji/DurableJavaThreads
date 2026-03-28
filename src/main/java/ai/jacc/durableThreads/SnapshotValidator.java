package ai.jacc.durableThreads;

import ai.jacc.durableThreads.exception.BytecodeMismatchException;
import ai.jacc.durableThreads.internal.BytecodeHasher;
import ai.jacc.durableThreads.internal.ClassStructureHasher;
import ai.jacc.durableThreads.internal.InvokeRegistry;
import ai.jacc.durableThreads.snapshot.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Validates a {@link ThreadSnapshot} before restore by checking that the
 * current JVM's classes match the classes that were present at freeze time.
 *
 * <p>Two kinds of validation are performed:</p>
 * <ul>
 *   <li><b>Bytecode hashes:</b> SHA-256 of each method's instrumented bytecode
 *       must match between freeze and restore. Detects code changes that would
 *       cause the replay prologue to dispatch to the wrong invoke index.</li>
 *   <li><b>Class structure hashes:</b> SHA-256 of each heap object's class field
 *       layout (names, types, declaring class) must match. Detects added/removed/
 *       renamed fields or type changes that would cause silent data corruption.</li>
 * </ul>
 */
final class SnapshotValidator {

    private SnapshotValidator() {}

    /**
     * Force-load all classes referenced in the snapshot frames.
     * This triggers the agent's ClassFileTransformer which populates
     * InvokeRegistry with invoke offset maps needed for restore.
     */
    static void ensureClassesLoaded(ThreadSnapshot snapshot) {
        for (FrameSnapshot frame : snapshot.frames()) {
            String className = frame.className().replace('/', '.');
            try {
                Class.forName(className);
            } catch (ClassNotFoundException e) {
                // Class not available in this JVM — will fail later at invoke time
            }
        }
    }

    /**
     * Validate that method bytecode has not changed between freeze and restore.
     *
     * @throws BytecodeMismatchException if any method's bytecode hash differs
     */
    static void validateBytecodeHashes(ThreadSnapshot snapshot) {
        List<String> mismatched = new ArrayList<>();

        for (FrameSnapshot frame : snapshot.frames()) {
            if (frame.bytecodeHash() == null || frame.bytecodeHash().length == 0) {
                continue;
            }

            byte[] classBytecode = InvokeRegistry.getInstrumentedBytecode(frame.className());
            if (classBytecode == null) {
                // Class not instrumented — may be a JDK class that was stripped
                continue;
            }

            byte[] currentHash = BytecodeHasher.hash(
                    classBytecode, frame.methodName(), frame.methodSignature());
            if (currentHash == null || !Arrays.equals(frame.bytecodeHash(), currentHash)) {
                mismatched.add(frame.className().replace('/', '.') + "." + frame.methodName());
            }
        }

        if (!mismatched.isEmpty()) {
            throw new BytecodeMismatchException(mismatched);
        }
    }

    /**
     * Validate class structure hashes for heap objects to detect incompatible
     * class changes (added/removed/renamed fields, type changes).
     *
     * @throws BytecodeMismatchException if any class's field layout has changed
     */
    static void validateClassStructureHashes(ThreadSnapshot snapshot) {
        List<String> mismatched = new ArrayList<>();

        for (ObjectSnapshot objSnap : snapshot.heap()) {
            if (objSnap.classStructureHash() == null || objSnap.classStructureHash().length == 0) {
                continue;
            }
            if (objSnap.kind() != ObjectKind.REGULAR) {
                continue;
            }

            // Skip lambda/hidden classes — their names are JVM-generated and
            // non-deterministic, so Class.forName will fail. They're captured
            // as heap objects when reachable from locals but don't need
            // structure validation (their layout is trivial).
            if (objSnap.className().contains("$$Lambda")) {
                continue;
            }

            try {
                Class<?> clazz = Class.forName(objSnap.className());
                byte[] currentHash = ClassStructureHasher.hashClassStructure(clazz);
                if (!Arrays.equals(objSnap.classStructureHash(), currentHash)) {
                    mismatched.add(objSnap.className());
                }
            } catch (ClassNotFoundException e) {
                mismatched.add(objSnap.className() + " (class not found)");
            }
        }

        if (!mismatched.isEmpty()) {
            throw new BytecodeMismatchException(mismatched);
        }
    }
}
