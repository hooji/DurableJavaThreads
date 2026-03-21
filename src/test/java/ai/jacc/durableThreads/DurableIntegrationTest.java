package ai.jacc.durableThreads;

import ai.jacc.durableThreads.exception.AgentNotLoadedException;
import ai.jacc.durableThreads.snapshot.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Durable API that can run without the agent.
 * These verify error handling and snapshot serialization.
 *
 * Full freeze/restore integration tests require a child JVM with the agent
 * and JDWP enabled — see {@link FreezeRestoreIT} for those.
 */
class DurableIntegrationTest {

    @Test
    void freezeWithoutAgentThrowsAgentNotLoadedException() {
        // The agent is not loaded in the test JVM
        assertThrows(AgentNotLoadedException.class, () ->
                Durable.freeze(snapshot -> {}));
    }

    @Test
    void restoreWithoutAgentThrowsAgentNotLoadedException() {
        ThreadSnapshot snapshot = new ThreadSnapshot(
                Instant.now(), "test-thread",
                Collections.<FrameSnapshot>emptyList(),
                Collections.<ObjectSnapshot>emptyList());

        assertThrows(AgentNotLoadedException.class, () ->
                Durable.restore(snapshot));
    }

    @Test
    void snapshotIsSerializable() throws Exception {
        // Build a snapshot with all ref types
        List<LocalVariable> locals = Arrays.asList(
                new LocalVariable(0, "this", "Lcom/example/Foo;", new HeapRef(1L)),
                new LocalVariable(1, "x", "I", new PrimitiveRef(42)),
                new LocalVariable(2, "name", "Ljava/lang/String;", new NullRef())
        );
        FrameSnapshot frame = new FrameSnapshot(
                "com/example/Foo", "doWork", "()V",
                42, 0, new byte[]{1, 2, 3, 4}, locals);

        Map<String, ObjectRef> obj1Fields = new LinkedHashMap<>();
        obj1Fields.put("com.example.Foo.value", new PrimitiveRef(100));

        Map<String, ObjectRef> obj2Fields = Collections.emptyMap();

        Map<String, ObjectRef> obj3Fields = new LinkedHashMap<>();
        obj3Fields.put("value", new PrimitiveRef("hello"));

        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, "com.example.Foo", ObjectKind.REGULAR,
                        obj1Fields,
                        null),
                new ObjectSnapshot(2L, "[I", ObjectKind.ARRAY,
                        obj2Fields,
                        new ObjectRef[]{new PrimitiveRef(1), new PrimitiveRef(2)}),
                new ObjectSnapshot(3L, "java.lang.String", ObjectKind.STRING,
                        obj3Fields,
                        null)
        );
        ThreadSnapshot original = new ThreadSnapshot(Instant.now(), "worker-1",
                Arrays.asList(frame), heap);

        // Serialize
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
            bytes = baos.toByteArray();
        }

        // Deserialize
        ThreadSnapshot restored;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            restored = (ThreadSnapshot) ois.readObject();
        }

        // Verify round-trip
        assertEquals(original.capturedAt(), restored.capturedAt());
        assertEquals(original.threadName(), restored.threadName());
        assertEquals(original.frameCount(), restored.frameCount());
        assertEquals(original.topFrame().className(), restored.topFrame().className());
        assertEquals(original.topFrame().methodName(), restored.topFrame().methodName());
        assertEquals(original.topFrame().bytecodeIndex(), restored.topFrame().bytecodeIndex());
        assertArrayEquals(original.topFrame().bytecodeHash(), restored.topFrame().bytecodeHash());

        // Verify locals survived serialization
        assertEquals(3, restored.topFrame().locals().size());
        assertInstanceOf(HeapRef.class, restored.topFrame().locals().get(0).value());
        assertInstanceOf(PrimitiveRef.class, restored.topFrame().locals().get(1).value());
        assertInstanceOf(NullRef.class, restored.topFrame().locals().get(2).value());

        // Verify heap survived serialization
        assertEquals(3, restored.heap().size());
        assertEquals(ObjectKind.REGULAR, restored.heap().get(0).kind());
        assertEquals(ObjectKind.ARRAY, restored.heap().get(1).kind());
        assertEquals(ObjectKind.STRING, restored.heap().get(2).kind());
    }

    @Test
    void snapshotSerializationSizeIsReasonable() throws Exception {
        // A simple snapshot shouldn't serialize to an enormous size
        ThreadSnapshot snapshot = new ThreadSnapshot(Instant.now(), "t1",
                Arrays.asList(new FrameSnapshot("Foo", "bar", "()V", 0, 0, new byte[32],
                        Arrays.asList(new LocalVariable(0, "x", "I", new PrimitiveRef(1))))),
                Collections.<ObjectSnapshot>emptyList());

        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            bytes = baos.toByteArray();
        }

        // A simple snapshot should be well under 10KB
        assertTrue(bytes.length < 10_000,
                "Simple snapshot serialized to " + bytes.length + " bytes (expected < 10KB)");
    }

}
