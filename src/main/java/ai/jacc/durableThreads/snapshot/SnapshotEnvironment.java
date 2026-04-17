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
 * <p>Older snapshots (pre-1.4.2) deserialize normally: the additional
 * {@code javaRuntimeVersion}, {@code javaVendor}, {@code javaVmName},
 * {@code javaSpecificationVersion}, {@code osArch}, and
 * {@code archDataModel} fields default to {@code null} via Java's standard
 * missing-field handling.</p>
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

    /** {@code java.runtime.version} (e.g. "21.0.2+13-LTS"), nullable on older snapshots. */
    private final String javaRuntimeVersion;

    /** {@code java.vendor} (e.g. "Eclipse Adoptium"), nullable on older snapshots. */
    private final String javaVendor;

    /** {@code java.vm.name} (e.g. "OpenJDK 64-Bit Server VM"), nullable on older snapshots. */
    private final String javaVmName;

    /** {@code java.specification.version} (e.g. "21", "1.8"), nullable on older snapshots. */
    private final String javaSpecificationVersion;

    /** {@code os.arch} (e.g. "amd64", "aarch64"), nullable on older snapshots. */
    private final String osArch;

    /** {@code sun.arch.data.model} ("32" or "64"), nullable on older snapshots. */
    private final String archDataModel;

    /**
     * Backwards-compatible constructor that leaves the extended JDK/OS fields null.
     */
    public SnapshotEnvironment(String libraryVersion, String javaVersion,
                               String classpath, String osName,
                               List<ClassEntry> classEntries) {
        this(libraryVersion, javaVersion, classpath, osName, classEntries,
                null, null, null, null, null, null);
    }

    public SnapshotEnvironment(String libraryVersion, String javaVersion,
                               String classpath, String osName,
                               List<ClassEntry> classEntries,
                               String javaRuntimeVersion,
                               String javaVendor,
                               String javaVmName,
                               String javaSpecificationVersion,
                               String osArch,
                               String archDataModel) {
        this.libraryVersion = libraryVersion;
        this.javaVersion = javaVersion;
        this.classpath = classpath;
        this.osName = osName;
        this.classEntries = classEntries != null
                ? Collections.unmodifiableList(new ArrayList<>(classEntries))
                : Collections.<ClassEntry>emptyList();
        this.javaRuntimeVersion = javaRuntimeVersion;
        this.javaVendor = javaVendor;
        this.javaVmName = javaVmName;
        this.javaSpecificationVersion = javaSpecificationVersion;
        this.osArch = osArch;
        this.archDataModel = archDataModel;
    }

    public String libraryVersion() { return libraryVersion; }
    public String javaVersion() { return javaVersion; }
    public String classpath() { return classpath; }
    public String osName() { return osName; }
    public List<ClassEntry> classEntries() { return classEntries; }

    /** @return {@code java.runtime.version}, or {@code null} on older snapshots. */
    public String javaRuntimeVersion() { return javaRuntimeVersion; }

    /** @return {@code java.vendor}, or {@code null} on older snapshots. */
    public String javaVendor() { return javaVendor; }

    /** @return {@code java.vm.name}, or {@code null} on older snapshots. */
    public String javaVmName() { return javaVmName; }

    /** @return {@code java.specification.version}, or {@code null} on older snapshots. */
    public String javaSpecificationVersion() { return javaSpecificationVersion; }

    /** @return {@code os.arch}, or {@code null} on older snapshots. */
    public String osArch() { return osArch; }

    /** @return {@code sun.arch.data.model} ("32"/"64"), or {@code null} on older snapshots. */
    public String archDataModel() { return archDataModel; }

    @Override
    public String toString() {
        return "SnapshotEnvironment[libraryVersion=" + libraryVersion
                + ", javaVersion=" + javaVersion
                + (javaRuntimeVersion != null ? ", javaRuntimeVersion=" + javaRuntimeVersion : "")
                + (javaVendor != null ? ", javaVendor=" + javaVendor : "")
                + (javaVmName != null ? ", javaVmName=" + javaVmName : "")
                + (osArch != null ? ", osArch=" + osArch : "")
                + ", classes=" + classEntries.size() + "]";
    }

    /**
     * Metadata for a single class referenced by the snapshot.
     *
     * <p>The optional {@code bytecode} field holds the original (pre-
     * instrumentation) class file bytes, enabling portable restore: a JVM
     * that does not have the class on its classpath can still rebuild the
     * state by installing the embedded bytes. Only user classes carry bytes
     * — JDK classes are never embedded.</p>
     */
    public static final class ClassEntry implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Internal class name (slash-separated, e.g. "com/example/Foo"). */
        private final String className;

        /** Source location: jar path, directory, or null if unknown. */
        private final String sourceLocation;

        /** SHA-256 hash of the original (pre-instrumentation) bytecode. */
        private final byte[] bytecodeHash;

        /**
         * Original (pre-instrumentation) class file bytes, or {@code null}
         * when not embedded. Older snapshots deserialize with this null.
         */
        private final byte[] bytecode;

        public ClassEntry(String className, String sourceLocation, byte[] bytecodeHash) {
            this(className, sourceLocation, bytecodeHash, null);
        }

        public ClassEntry(String className, String sourceLocation,
                          byte[] bytecodeHash, byte[] bytecode) {
            this.className = className;
            this.sourceLocation = sourceLocation;
            this.bytecodeHash = bytecodeHash;
            this.bytecode = bytecode;
        }

        public String className() { return className; }
        public String sourceLocation() { return sourceLocation; }
        public byte[] bytecodeHash() { return bytecodeHash; }

        /**
         * Original class file bytes, or {@code null} if this snapshot does
         * not embed them (older format or JDK class).
         */
        public byte[] bytecode() { return bytecode; }

        @Override
        public String toString() {
            return className + (sourceLocation != null ? " from " + sourceLocation : "");
        }
    }
}
