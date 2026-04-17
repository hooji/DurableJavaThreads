package ai.jacc.durableThreads.internal;

import ai.jacc.durableThreads.DurableAgent;
import ai.jacc.durableThreads.snapshot.SnapshotEnvironment;
import ai.jacc.durableThreads.snapshot.SnapshotEnvironment.ClassEntry;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Installs embedded class bytes from a snapshot's {@link SnapshotEnvironment}
 * so that user classes not present on the restore JVM's classpath can still
 * be found by {@code Class.forName}.
 *
 * <p>Strategy: for every {@link ClassEntry} that carries original bytes and
 * is not already loadable, write a temp jar and call
 * {@code Instrumentation.appendToSystemClassLoaderSearch}. Classes loaded
 * through that jar path pass through the agent's {@code ClassFileTransformer}
 * normally, so they get instrumented like any other user class.</p>
 *
 * <p>Runs once per restore, before {@link ai.jacc.durableThreads.SnapshotValidator}
 * does its {@code Class.forName} walk. Classes already present on the
 * classpath are left untouched (the embedded bytes are ignored).</p>
 */
public final class EmbeddedClassInstaller {

    private EmbeddedClassInstaller() {}

    /**
     * Install any embedded classes from the snapshot that are not already
     * available on the classpath. No-op if the environment is null, empty,
     * or contains no embedded bytes.
     */
    public static void installIfNeeded(SnapshotEnvironment env) {
        if (env == null) return;
        List<ClassEntry> toInstall = new ArrayList<>();
        for (ClassEntry entry : env.classEntries()) {
            if (entry.bytecode() == null || entry.bytecode().length == 0) continue;
            if (isClassAvailable(entry.className())) continue;
            toInstall.add(entry);
        }
        if (toInstall.isEmpty()) return;

        Instrumentation inst = DurableAgent.getInstrumentation();
        if (inst == null) {
            throw new RuntimeException(
                    "Snapshot contains " + toInstall.size()
                    + " embedded class(es) not on this JVM's classpath, but the "
                    + "Durable agent is not loaded — cannot install them. "
                    + "Add -javaagent:durable-threads.jar to restore on a JVM "
                    + "that lacks the original classpath.");
        }

        try {
            File jar = writeTempJar(toInstall);
            inst.appendToSystemClassLoaderSearch(new JarFile(jar));
            // The temp jar is NOT deleted — the system classloader keeps it
            // open for the lifetime of the JVM. Schedule deletion on JVM exit.
            jar.deleteOnExit();
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to install " + toInstall.size()
                    + " embedded class(es) from snapshot", e);
        }
    }

    private static boolean isClassAvailable(String internalClassName) {
        String dotName = internalClassName.replace('/', '.');
        try {
            Class.forName(dotName, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            // The class was found but couldn't be linked (e.g., bytecode version
            // mismatch). Prefer the classpath's broken class over a silent
            // substitution — don't try to install.
            return true;
        }
    }

    private static File writeTempJar(List<ClassEntry> entries) throws IOException {
        File jar = Files.createTempFile("durable-embedded-", ".jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(
                new BufferedOutputStream(new FileOutputStream(jar)))) {
            for (ClassEntry entry : entries) {
                String path = entry.className().replace('.', '/') + ".class";
                JarEntry je = new JarEntry(path);
                jos.putNextEntry(je);
                jos.write(entry.bytecode());
                jos.closeEntry();
            }
        }
        return jar;
    }
}
