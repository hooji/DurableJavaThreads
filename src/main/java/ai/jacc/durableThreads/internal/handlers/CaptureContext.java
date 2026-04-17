package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.snapshot.ObjectRef;
import com.sun.jdi.Value;

/**
 * Callback surface exposed to a {@link TypeHandler} during capture.
 *
 * <p>Handlers need to recursively capture child JDI values (field values,
 * array elements, collection contents) without knowing how the heap walker
 * tracks ids or cycle detection.</p>
 */
public interface CaptureContext {

    /**
     * Recursively capture a child JDI value. Returns a leaf ({@code NullRef},
     * {@code PrimitiveRef}) for primitives/strings/null, or a {@code HeapRef}
     * into the snapshot heap for an object.
     */
    ObjectRef capture(Value value);
}
