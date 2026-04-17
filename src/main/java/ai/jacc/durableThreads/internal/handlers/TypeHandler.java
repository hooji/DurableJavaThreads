package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.snapshot.ObjectSnapshot;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;

/**
 * A paired capture + restore strategy for one category of object.
 *
 * <p>Each JDK weirdness (collections, boxed primitives, {@code java.time.*},
 * enums, unmodifiable-collection wrappers, etc.) lives in its own handler.
 * The {@link TypeHandlerRegistry} iterates handlers in priority order; the
 * first match wins on both the capture and the restore side.</p>
 *
 * <p>The handler model covers only "regular object" capture — primitives,
 * strings, arrays, and {@code null} are dispatched directly by the heap
 * walker and restorer because their paths are trivial.</p>
 */
public interface TypeHandler {

    // --- Capture side ---

    /**
     * Does this handler claim the given object at capture time?
     *
     * @param objRef    JDI reference to the object
     * @param refType   its reference type (may differ from {@code objRef.referenceType()}
     *                  only for historical reasons; callers pass the same value)
     * @param className {@code refType.name()} — supplied to avoid re-fetching
     */
    boolean capturesType(ObjectReference objRef, ReferenceType refType, String className);

    /**
     * Produce an {@link ObjectSnapshot} representing the object. The handler
     * may recurse via {@link CaptureContext#capture} on any child values it
     * needs to embed (fields, elements).
     *
     * @param ctx       callback surface for recursive capture
     * @param snapId    pre-allocated snapshot id (already reserved in the walker's cycle map)
     * @param objRef    JDI reference
     * @param refType   its reference type
     * @param className {@code refType.name()}
     * @param name      user-assigned name from the named-object map, or {@code null}
     */
    ObjectSnapshot capture(CaptureContext ctx, long snapId, ObjectReference objRef,
                           ReferenceType refType, String className, String name);

    // --- Restore side ---

    /**
     * Does this handler claim the given snapshot at restore time?
     *
     * <p>Must be consistent with {@link #capturesType}: a snapshot produced by
     * this handler must be claimed by this handler. Ordering in the registry
     * resolves ambiguity.</p>
     */
    boolean restoresSnapshot(ObjectSnapshot snap);

    /**
     * Allocate the restored object without populating its fields/elements
     * (pass 1). Many simple types can be fully built here — only those whose
     * fields may reference other heap objects defer work to {@link #populate}.
     */
    Object allocate(RestoreContext ctx, ObjectSnapshot snap);

    /**
     * Populate fields/elements that reference other heap objects (pass 2).
     * Default is a no-op for types fully constructed in {@link #allocate}.
     */
    default void populate(RestoreContext ctx, Object obj, ObjectSnapshot snap) {
    }
}
