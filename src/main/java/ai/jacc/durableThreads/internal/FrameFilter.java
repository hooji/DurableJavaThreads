package ai.jacc.durableThreads.internal;

/**
 * Shared logic for classifying stack frames as infrastructure (JDK, library)
 * or user code. Used by both freeze and restore to ensure consistent filtering.
 */
public final class FrameFilter {

    private FrameFilter() {}

    /** Prefixes of classes whose frames should be excluded from snapshots. */
    private static final String[] EXCLUDED_FRAME_PREFIXES = {
            "java/", "javax/", "jdk/", "sun/", "com/sun/",
    };

    /** Specific library classes to exclude (not the whole package — user subpackages may exist). */
    private static final String[] EXCLUDED_FRAME_CLASSES = {
            "ai/jacc/durableThreads/Durable",
            "ai/jacc/durableThreads/ReflectionHelpers",
            "ai/jacc/durableThreads/ThreadFreezer",
            "ai/jacc/durableThreads/ThreadRestorer",
            "ai/jacc/durableThreads/ReplayState",
    };

    /**
     * Check if a frame belongs to JDK or library infrastructure that should be
     * silently skipped (not captured or matched). These are frames that get
     * recreated naturally during restore (Thread.run, reflection, etc.).
     *
     * @param className internal class name (slash-separated, e.g. "java/lang/Thread")
     * @return true if the frame should be skipped
     */
    public static boolean isInfrastructureFrame(String className) {
        for (String prefix : EXCLUDED_FRAME_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        for (String excluded : EXCLUDED_FRAME_CLASSES) {
            if (className.equals(excluded) || className.startsWith(excluded + "$")) return true;
        }
        return false;
    }
}
