package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.snapshot.ObjectKind;
import ai.jacc.durableThreads.snapshot.ObjectRef;
import ai.jacc.durableThreads.snapshot.ObjectSnapshot;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;

import java.util.Collections;
import java.util.Map;

/**
 * Bare {@code java.lang.Object} instances — typically the {@code PRESENT}
 * sentinel inside {@code HashSet}. Captured and restored as empty, since
 * {@code Object} has no fields to preserve.
 */
public final class PlainObjectHandler implements TypeHandler {

    @Override
    public boolean capturesType(ObjectReference objRef, ReferenceType refType, String className) {
        return "java.lang.Object".equals(className);
    }

    @Override
    public ObjectSnapshot capture(CaptureContext ctx, long snapId, ObjectReference objRef,
                                   ReferenceType refType, String className, String name) {
        Map<String, ObjectRef> empty = Collections.emptyMap();
        return new ObjectSnapshot(snapId, "java.lang.Object", ObjectKind.REGULAR,
                empty, null, null, name);
    }

    @Override
    public boolean restoresSnapshot(ObjectSnapshot snap) {
        return snap.kind() == ObjectKind.REGULAR
                && "java.lang.Object".equals(snap.className());
    }

    @Override
    public Object allocate(RestoreContext ctx, ObjectSnapshot snap) {
        return new Object();
    }
}
