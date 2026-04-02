package ai.jacc.durableThreads.snapshot;

import java.io.Serializable;
import java.util.*;

/**
 * Metadata about the JVM environment at freeze time.
 *
 * <p>Captures classpath information, per-class source locations, and JVM
 * details so that restore infrastructure can reconstruct a compatible
 * environment or provide actionable diagnostics when classes are missing
 * or have changed.</p>
 *
 * <p>This is tier 1 of the portable snapshot feature — metadata only,
 * no embedded bytecodes. Tier 2 (embedding class files inside the snapshot
 * for fully self-contained restoration) will build on this foundation.</p>
 */
public final class SnapshotEnvironment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The library version that created this snapshot. */
    private final String libraryVersion;

    /** Java version of the freeze JVM (e.g. "21.0.2"). */
    private final String javaVersion;

    /** The java.class.path system property at freeze time. */
    private final String classpath;

    /** OS name at freeze time (e.g. "Linux", "Mac OS X"). */
    private final String osName;

    /** Per-class metadata for classes referenced by the snapshot. */
    private final List<ClassEntry> classEntries;

    public SnapshotEnvironment(String libraryVersion, String javaVersion,
                               String classpath, String osName,
                               List<ClassEntry> classEntries) {
        this.libraryVersion = libraryVersion;
        this.javaVersion = javaVersion;
        this.classpath = classpath;
        this.osName = osName;
        this.classEntries = classEntries != null
                ? Collections.unmodifiableList(new ArrayList<>(classEntries))
                : Collections.<ClassEntry>emptyList();
    }

    public String libraryVersion() { return libraryVersion; }
    public String javaVersion() { return javaVersion; }
    public String classpath() { return classpath; }
    public String osName() { return osName; }
    public List<ClassEntry> classEntries() { return classEntries; }

    @Override
    public String toString() {
        return "SnapshotEnvironment[libraryVersion=" + libraryVersion
                + ", javaVersion=" + javaVersion
                + ", classes=" + classEntries.size() + "]";
    }

    /**
     * Metadata for a single class referenced by the snapshot.
     */
    public static final class ClassEntry implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Internal class name (slash-separated, e.g. "com/example/Foo"). */
        private final String className;

        /** Source location: jar path, directory, or null if unknown. */
        private final String sourceLocation;

        /** SHA-256 hash of the original (pre-instrumentation) bytecode. */
        private final byte[] bytecodeHash;

        public ClassEntry(String className, String sourceLocation, byte[] bytecodeHash) {
            this.className = className;
            this.sourceLocation = sourceLocation;
            this.bytecodeHash = bytecodeHash;
        }

        public String className() { return className; }
        public String sourceLocation() { return sourceLocation; }
        public byte[] bytecodeHash() { return bytecodeHash; }

        @Override
        public String toString() {
            return className + (sourceLocation != null ? " from " + sourceLocation : "");
        }
    }
}
