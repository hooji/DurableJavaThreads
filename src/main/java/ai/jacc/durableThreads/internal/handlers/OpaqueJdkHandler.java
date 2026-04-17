package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.exception.UncapturableTypeException;
import ai.jacc.durableThreads.snapshot.ObjectSnapshot;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;

/**
 * Fail-fast terminator for JDK-internal types that were not matched by any
 * earlier handler (Optional, unmodifiable collection wrappers, Thread /
 * ThreadLocal, concurrency primitives, executors, classloaders, I/O streams,
 * sockets, regex Pattern, etc.). Produces an {@link UncapturableTypeException}
 * with targeted advice for each category.
 *
 * <p>Restore side never matches — snapshots never contain an opaque entry.</p>
 */
public final class OpaqueJdkHandler implements TypeHandler {

    private static final String[] OPAQUE_PACKAGES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun."
    };

    @Override
    public boolean capturesType(ObjectReference objRef, ReferenceType refType, String className) {
        for (String prefix : OPAQUE_PACKAGES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    public ObjectSnapshot capture(CaptureContext ctx, long snapId, ObjectReference objRef,
                                   ReferenceType refType, String className, String name) {
        throw new UncapturableTypeException(className, adviceFor(className));
    }

    @Override
    public boolean restoresSnapshot(ObjectSnapshot snap) {
        return false;
    }

    @Override
    public Object allocate(RestoreContext ctx, ObjectSnapshot snap) {
        throw new IllegalStateException("Opaque handler has no restore path");
    }

    private static String adviceFor(String className) {
        if (className.equals("java.util.Optional")
                || className.startsWith("java.util.Optional")) {
            return "Optional<T> cannot be frozen because its final 'value' field "
                    + "cannot be set after construction. Use T directly (with null for "
                    + "empty) instead of wrapping in Optional.";
        }
        if (className.startsWith("java.util.Collections$Unmodifiable")
                || className.startsWith("java.util.Collections$Singleton")
                || className.startsWith("java.util.Collections$Empty")
                || className.startsWith("java.util.ImmutableCollections$")) {
            return "Unmodifiable/immutable collection wrappers cannot be frozen "
                    + "because their immutability contract cannot be preserved on "
                    + "restore. Use a mutable collection (ArrayList, HashMap, etc.) "
                    + "in the frozen thread instead.";
        }
        if (className.equals("java.lang.Thread")
                || className.equals("java.lang.ThreadGroup")
                || className.equals("java.lang.ThreadLocal")
                || className.equals("java.lang.InheritableThreadLocal")) {
            return "Thread/ThreadLocal objects cannot be frozen. Remove references "
                    + "to these types from local variables reachable by the frozen thread.";
        }
        if (className.startsWith("java.util.concurrent.locks.")
                || className.startsWith("java.util.concurrent.atomic.")
                || className.equals("java.util.concurrent.CountDownLatch")
                || className.equals("java.util.concurrent.CyclicBarrier")
                || className.equals("java.util.concurrent.Semaphore")
                || className.equals("java.util.concurrent.Phaser")
                || className.equals("java.util.concurrent.Exchanger")) {
            return "Concurrency primitives (locks, latches, barriers, atomics) cannot "
                    + "be frozen because they hold thread-specific state. Remove these "
                    + "references from local variables reachable by the frozen thread.";
        }
        if (className.contains("ExecutorService")
                || className.contains("ThreadPool")
                || className.contains("ForkJoinPool")
                || className.equals("java.util.concurrent.ScheduledThreadPoolExecutor")) {
            return "Thread pool / executor types cannot be frozen. Remove executor "
                    + "references from local variables reachable by the frozen thread, "
                    + "or use named objects to substitute a fresh executor at restore time.";
        }
        if (className.contains("ClassLoader")) {
            return "ClassLoader objects cannot be frozen. Remove ClassLoader "
                    + "references from local variables reachable by the frozen thread.";
        }
        if (className.startsWith("java.io.") || className.startsWith("java.nio.")) {
            return "I/O types (streams, channels, files) hold native resources "
                    + "that cannot be serialized. Remove I/O object references from "
                    + "local variables reachable by the frozen thread, or close them "
                    + "before freezing.";
        }
        if (className.startsWith("java.net.") && !className.equals("java.net.URI")) {
            return "Network types (sockets, connections) hold native resources "
                    + "that cannot be serialized. Remove network object references "
                    + "from local variables reachable by the frozen thread.";
        }
        if (className.equals("java.util.regex.Pattern")) {
            return "Pattern cannot be frozen because its compiled internal state "
                    + "cannot be restored. Store the pattern string instead and "
                    + "recompile with Pattern.compile() after restore.";
        }
        return "This is a JDK-internal type whose fields cannot be read or "
                + "restored correctly. Avoid using this type in local variables or "
                + "fields reachable from the frozen thread. If you believe this type "
                + "should be supported, please file an issue.";
    }
}
