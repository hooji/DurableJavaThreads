package ai.jacc.durableThreads;

import com.sun.jdi.*;
import ai.jacc.durableThreads.internal.*;
import ai.jacc.durableThreads.snapshot.*;

import java.util.List;

/**
 * Stateless JDI value conversion: converts snapshot {@link ObjectRef} instances
 * to JDI {@link Value} instances suitable for {@code StackFrame.setValue()}.
 *
 * <p>Extracted from ThreadRestorer (Stage 3 refactoring).</p>
 */
final class JdiValueConverter {

    private JdiValueConverter() {}

    /**
     * Convert a snapshot ObjectRef to a JDI Value.
     */
    static Value convertToJdiValue(VirtualMachine vm, ObjectRef ref,
                                   java.util.Map<Long, Object> restoredHeap,
                                   HeapRestorer heapRestorer) {
        if (ref instanceof NullRef) {
            return null;
        } else if (ref instanceof PrimitiveRef) {
            return convertPrimitiveToJdiValue(vm, ((PrimitiveRef) ref).value());
        } else if (ref instanceof HeapRef) {
            return resolveHeapRefViaJdi(vm, ((HeapRef) ref).id());
        }
        return null;
    }

    /**
     * Convert a boxed primitive to the corresponding JDI Value.
     */
    static Value convertPrimitiveToJdiValue(VirtualMachine vm, java.io.Serializable value) {
        if (value instanceof Boolean) return vm.mirrorOf((Boolean) value);
        if (value instanceof Byte) return vm.mirrorOf((Byte) value);
        if (value instanceof Character) return vm.mirrorOf((Character) value);
        if (value instanceof Short) return vm.mirrorOf((Short) value);
        if (value instanceof Integer) return vm.mirrorOf((Integer) value);
        if (value instanceof Long) return vm.mirrorOf((Long) value);
        if (value instanceof Float) return vm.mirrorOf((Float) value);
        if (value instanceof Double) return vm.mirrorOf((Double) value);
        if (value instanceof String) return vm.mirrorOf((String) value);
        return null;
    }

    /**
     * Resolve a heap object reference to a JDI ObjectReference by reading it
     * from the {@link HeapObjectBridge}.
     *
     * <p>The restored object lives in the same JVM. We stored it in
     * HeapObjectBridge.objects (a static ConcurrentHashMap). JDI can read
     * that map via ReferenceType field access, then call get() to obtain
     * the ObjectReference.</p>
     */
    static Value resolveHeapRefViaJdi(VirtualMachine vm, long snapshotId) {
        try {
            // Find the HeapObjectBridge class in JDI
            List<ReferenceType> bridgeTypes = vm.classesByName(
                    "ai.jacc.durableThreads.internal.HeapObjectBridge");
            if (bridgeTypes.isEmpty()) {
                throw new RuntimeException("HeapObjectBridge class not found in JDI. "
                        + "Cannot resolve heap reference " + snapshotId);
            }

            ReferenceType bridgeType = bridgeTypes.get(0);

            // Find the static 'objects' field (ConcurrentHashMap<String, Object>)
            com.sun.jdi.Field objectsField = bridgeType.fieldByName("objects");
            if (objectsField == null) {
                throw new RuntimeException("HeapObjectBridge.objects field not found. "
                        + "Cannot resolve heap reference " + snapshotId);
            }

            ObjectReference mapRef = (ObjectReference) bridgeType.getValue(objectsField);
            if (mapRef == null) {
                throw new RuntimeException("HeapObjectBridge.objects map is null. "
                        + "Cannot resolve heap reference " + snapshotId);
            }

            Value result = JdiHelper.getConcurrentHashMapValue(
                    mapRef, String.valueOf(snapshotId));
            if (result == null) {
                throw new RuntimeException("Heap object with snapshot ID " + snapshotId
                        + " not found in HeapObjectBridge. The restored heap may be incomplete.");
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve heap reference " + snapshotId
                    + " via JDI", e);
        }
    }
}
