package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.snapshot.ObjectSnapshot;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ordered list of {@link TypeHandler}s. The first handler whose
 * {@code capturesType}/{@code restoresSnapshot} returns {@code true} wins.
 *
 * <p>Order is deliberate and load-bearing:</p>
 * <ol>
 *   <li>{@link CollectionHandler} — recognize collections before the enum /
 *       immutable / opaque checks can fire on {@code java.util.*} classes.</li>
 *   <li>{@link StringBuilderHandler} — before opaque.</li>
 *   <li>{@link BoxedPrimitiveHandler} — before opaque.</li>
 *   <li>{@link ImmutableJdkHandler} — before opaque and before enum (URI /
 *       BigDecimal / {@code java.time.*} types are not enums but live in
 *       {@code java.*}).</li>
 *   <li>{@link EnumHandler} — before opaque, because JDK enums live in
 *       {@code java.*} packages.</li>
 *   <li>{@link PlainObjectHandler} — {@code java.lang.Object} sentinel.</li>
 *   <li>{@link OpaqueJdkHandler} — fail-fast on any other {@code java.*},
 *       {@code javax.*}, {@code jdk.*}, {@code sun.*}, {@code com.sun.*}.</li>
 *   <li>{@link RegularObjectHandler} — final fallback for app objects.</li>
 * </ol>
 */
public final class TypeHandlerRegistry {

    private static final List<TypeHandler> HANDLERS = Collections.unmodifiableList(Arrays.asList(
            new CollectionHandler(),
            new StringBuilderHandler(),
            new BoxedPrimitiveHandler(),
            new ImmutableJdkHandler(),
            new EnumHandler(),
            new PlainObjectHandler(),
            new OpaqueJdkHandler(),
            new RegularObjectHandler()
    ));

    private TypeHandlerRegistry() {}

    public static List<TypeHandler> handlers() {
        return HANDLERS;
    }

    /**
     * Find the first handler that claims the given object for capture.
     * Never returns null — {@link RegularObjectHandler} is the final fallback.
     */
    public static TypeHandler forCapture(ObjectReference objRef, ReferenceType refType, String className) {
        for (TypeHandler h : HANDLERS) {
            if (h.capturesType(objRef, refType, className)) return h;
        }
        throw new IllegalStateException("No handler matched for capture: " + className);
    }

    /**
     * Find the first handler that claims the given snapshot for restore.
     * Returns null if no handler matches (callers decide how to fall back —
     * e.g., the plain {@code String} path is dispatched outside this chain).
     */
    public static TypeHandler forRestore(ObjectSnapshot snap) {
        for (TypeHandler h : HANDLERS) {
            if (h.restoresSnapshot(snap)) return h;
        }
        return null;
    }
}
