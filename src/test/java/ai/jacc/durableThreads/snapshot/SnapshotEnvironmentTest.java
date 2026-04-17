package ai.jacc.durableThreads.snapshot;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SnapshotEnvironmentTest {

    @Test
    void classEntryThreeArgCtorDefaultsBytecodeToNull() {
        SnapshotEnvironment.ClassEntry e = new SnapshotEnvironment.ClassEntry(
                "com/example/Foo", "file:/tmp/foo.jar", new byte[]{1, 2, 3});
        assertEquals("com/example/Foo", e.className());
        assertEquals("file:/tmp/foo.jar", e.sourceLocation());
        assertArrayEquals(new byte[]{1, 2, 3}, e.bytecodeHash());
        assertNull(e.bytecode(), "3-arg ctor should leave bytecode null");
    }

    @Test
    void classEntryFourArgCtorCarriesBytecode() {
        byte[] bytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        SnapshotEnvironment.ClassEntry e = new SnapshotEnvironment.ClassEntry(
                "com/example/Foo", null, new byte[]{9}, bytes);
        assertArrayEquals(bytes, e.bytecode());
    }

    @Test
    void environmentRoundTripsEmbeddedBytecode() throws Exception {
        byte[] bytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52};
        SnapshotEnvironment.ClassEntry entry = new SnapshotEnvironment.ClassEntry(
                "com/example/Foo", "file:/tmp/x.jar", new byte[]{1}, bytes);
        SnapshotEnvironment env = new SnapshotEnvironment(
                "test", "21.0.2", "", "Linux", Collections.singletonList(entry));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(env);
        }

        SnapshotEnvironment restored;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            restored = (SnapshotEnvironment) ois.readObject();
        }

        assertEquals(1, restored.classEntries().size());
        SnapshotEnvironment.ClassEntry r = restored.classEntries().get(0);
        assertEquals("com/example/Foo", r.className());
        assertNotNull(r.bytecode());
        assertArrayEquals(bytes, r.bytecode());
    }

    @Test
    void environmentRoundTripsWithoutEmbeddedBytecode() throws Exception {
        // 3-arg ctor — bytecode is null
        SnapshotEnvironment.ClassEntry entry = new SnapshotEnvironment.ClassEntry(
                "com/example/Foo", null, new byte[]{7});
        SnapshotEnvironment env = new SnapshotEnvironment(
                "test", "21.0.2", "", "Linux", Collections.singletonList(entry));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(env);
        }

        SnapshotEnvironment restored;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            restored = (SnapshotEnvironment) ois.readObject();
        }

        SnapshotEnvironment.ClassEntry r = restored.classEntries().get(0);
        assertNull(r.bytecode());
        assertArrayEquals(new byte[]{7}, r.bytecodeHash());
    }

    @Test
    void emptyEntriesListIsImmutable() {
        SnapshotEnvironment env = new SnapshotEnvironment(
                "v", "j", "cp", "os", Arrays.<SnapshotEnvironment.ClassEntry>asList());
        assertEquals(0, env.classEntries().size());
    }

    @Test
    void fiveArgCtorLeavesExtendedJvmFieldsNull() {
        SnapshotEnvironment env = new SnapshotEnvironment(
                "v", "21.0.2", "cp", "Linux",
                Collections.<SnapshotEnvironment.ClassEntry>emptyList());
        assertNull(env.javaRuntimeVersion());
        assertNull(env.javaVendor());
        assertNull(env.javaVmName());
        assertNull(env.javaSpecificationVersion());
        assertNull(env.osArch());
        assertNull(env.archDataModel());
    }

    @Test
    void elevenArgCtorCarriesExtendedJvmFields() {
        SnapshotEnvironment env = new SnapshotEnvironment(
                "v", "21.0.2", "cp", "Linux",
                Collections.<SnapshotEnvironment.ClassEntry>emptyList(),
                "21.0.2+13-LTS", "Eclipse Adoptium",
                "OpenJDK 64-Bit Server VM", "21",
                "aarch64", "64");
        assertEquals("21.0.2+13-LTS", env.javaRuntimeVersion());
        assertEquals("Eclipse Adoptium", env.javaVendor());
        assertEquals("OpenJDK 64-Bit Server VM", env.javaVmName());
        assertEquals("21", env.javaSpecificationVersion());
        assertEquals("aarch64", env.osArch());
        assertEquals("64", env.archDataModel());
    }

    @Test
    void environmentRoundTripsExtendedJvmFields() throws Exception {
        SnapshotEnvironment env = new SnapshotEnvironment(
                "1.4.2", "21.0.2", "cp", "Linux",
                Collections.<SnapshotEnvironment.ClassEntry>emptyList(),
                "21.0.2+13-LTS", "Eclipse Adoptium",
                "OpenJDK 64-Bit Server VM", "21",
                "aarch64", "64");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(env);
        }

        SnapshotEnvironment restored;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            restored = (SnapshotEnvironment) ois.readObject();
        }

        assertEquals("21.0.2+13-LTS", restored.javaRuntimeVersion());
        assertEquals("Eclipse Adoptium", restored.javaVendor());
        assertEquals("OpenJDK 64-Bit Server VM", restored.javaVmName());
        assertEquals("21", restored.javaSpecificationVersion());
        assertEquals("aarch64", restored.osArch());
        assertEquals("64", restored.archDataModel());
    }
}
