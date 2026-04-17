package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.snapshot.ObjectRef;

/**
 * Callback surface exposed to a {@link TypeHandler} during restore.
 *
 * <p>Handlers need to resolve child {@code ObjectRef}s during the populate
 * pass without knowing how the restorer indexes its allocated-object map.</p>
 */
public interface RestoreContext {

    /**
     * Resolve a reference to the live Java object it points at.
     *
     * <p>Returns {@code null} for {@code NullRef}, the wrapped value for
     * {@code PrimitiveRef}, or the already-allocated object for
     * {@code HeapRef} (pass 1 is guaranteed to have allocated all heap
     * objects before pass 2 runs).</p>
     */
    Object resolve(ObjectRef ref);
}
