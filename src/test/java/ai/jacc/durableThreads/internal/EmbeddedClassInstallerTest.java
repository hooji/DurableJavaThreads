package ai.jacc.durableThreads.internal;

import ai.jacc.durableThreads.snapshot.SnapshotEnvironment;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class EmbeddedClassInstallerTest {

    @Test
    void nullEnvironmentIsNoOp() {
        assertDoesNotThrow(() -> EmbeddedClassInstaller.installIfNeeded(null));
    }

    @Test
    void emptyEntriesListIsNoOp() {
        SnapshotEnvironment env = new SnapshotEnvironment(
                "v", "j", "", "os",
                Collections.<SnapshotEnvironment.ClassEntry>emptyList());
        assertDoesNotThrow(() -> EmbeddedClassInstaller.installIfNeeded(env));
    }

    @Test
    void entryWithoutBytecodeIsNoOp() {
        SnapshotEnvironment.ClassEntry entry = new SnapshotEnvironment.ClassEntry(
                "com/example/Foo", null, new byte[]{1}, null);
        SnapshotEnvironment env = new SnapshotEnvironment(
                "v", "j", "", "os", Collections.singletonList(entry));
        assertDoesNotThrow(() -> EmbeddedClassInstaller.installIfNeeded(env));
    }

    @Test
    void alreadyLoadedClassIsNoOp() {
        // java.lang.String is trivially loadable — installer should skip even
        // though bytecode is present.
        SnapshotEnvironment.ClassEntry entry = new SnapshotEnvironment.ClassEntry(
                "java/lang/String", null, new byte[]{1}, new byte[]{2, 3, 4});
        SnapshotEnvironment env = new SnapshotEnvironment(
                "v", "j", "", "os",
                Arrays.asList(entry));
        // Should NOT attempt to append because the class is already loadable
        assertDoesNotThrow(() -> EmbeddedClassInstaller.installIfNeeded(env));
    }
}
