package ai.jacc.durableThreads.snapshot;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ThreadSnapshotTest {

    @Test
    void snapshotPreservesAllFields() {
        Instant now = Instant.now();
        List<LocalVariable> locals = Arrays.asList(
                new LocalVariable(0, "this", "Lcom/example/Foo;", new NullRef()),
                new LocalVariable(1, "x", "I", new PrimitiveRef(42))
        );
        FrameSnapshot frame = new FrameSnapshot(
                "com/example/Foo", "doWork", "()V",
                42, 0, new byte[]{1, 2, 3}, locals);

        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("field1", new PrimitiveRef(10));

        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, "com.example.Foo", ObjectKind.REGULAR,
                        fields, null)
        );
        ThreadSnapshot snapshot = new ThreadSnapshot(now, "worker-1", Arrays.asList(frame), heap);

        assertEquals(now, snapshot.capturedAt());
        assertEquals("worker-1", snapshot.threadName());
        assertEquals(1, snapshot.frameCount());
        assertSame(snapshot.bottomFrame(), snapshot.topFrame()); // single frame
        assertEquals("com/example/Foo", snapshot.topFrame().className());
        assertEquals("doWork", snapshot.topFrame().methodName());
        assertEquals(42, snapshot.topFrame().bytecodeIndex());
        assertEquals(2, snapshot.topFrame().locals().size());
        assertEquals(1, snapshot.heap().size());
    }

    @Test
    void multipleFramesOrderedBottomToTop() {
        FrameSnapshot bottom = new FrameSnapshot("com/example/Main", "main", "([Ljava/lang/String;)V",
                10, 0, new byte[0], Collections.<LocalVariable>emptyList());
        FrameSnapshot middle = new FrameSnapshot("com/example/Service", "process", "()V",
                25, 0, new byte[0], Collections.<LocalVariable>emptyList());
        FrameSnapshot top = new FrameSnapshot("com/example/Service", "compute", "(I)I",
                87, 0, new byte[0], Collections.<LocalVariable>emptyList());

        ThreadSnapshot snapshot = new ThreadSnapshot(Instant.now(), "t1",
                Arrays.asList(bottom, middle, top), Collections.<ObjectSnapshot>emptyList());

        assertEquals(3, snapshot.frameCount());
        assertEquals("main", snapshot.bottomFrame().methodName());
        assertEquals("compute", snapshot.topFrame().methodName());
    }

    @Test
    void objectRefSealedHierarchy() {
        ObjectRef heapRef = new HeapRef(42L);
        ObjectRef nullRef = new NullRef();
        ObjectRef primRef = new PrimitiveRef("hello");

        assertInstanceOf(HeapRef.class, heapRef);
        assertInstanceOf(NullRef.class, nullRef);
        assertInstanceOf(PrimitiveRef.class, primRef);

        assertEquals(42L, ((HeapRef) heapRef).id());
        assertEquals("hello", ((PrimitiveRef) primRef).value());
    }

    @Test
    void objectSnapshotRegular() {
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("com.example.Foo.name", new PrimitiveRef("test"));
        fields.put("com.example.Foo.count", new PrimitiveRef(5));

        ObjectSnapshot snap = new ObjectSnapshot(1L, "com.example.Foo", ObjectKind.REGULAR, fields, null);

        assertEquals(1L, snap.id());
        assertEquals(ObjectKind.REGULAR, snap.kind());
        assertEquals(2, snap.fields().size());
        assertNull(snap.arrayElements());
    }

    @Test
    void objectSnapshotArray() {
        ObjectRef[] elements = {new PrimitiveRef(1), new PrimitiveRef(2), new PrimitiveRef(3)};
        ObjectSnapshot snap = new ObjectSnapshot(2L, "[I", ObjectKind.ARRAY, Collections.<String, ObjectRef>emptyMap(), elements);

        assertEquals(ObjectKind.ARRAY, snap.kind());
        assertEquals(3, snap.arrayElements().length);
    }

    @Test
    void objectSnapshotString() {
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef("hello world"));
        ObjectSnapshot snap = new ObjectSnapshot(3L, "java.lang.String", ObjectKind.STRING, fields, null);

        assertEquals(ObjectKind.STRING, snap.kind());
        assertEquals("hello world", ((PrimitiveRef) snap.fields().get("value")).value());
    }
}
