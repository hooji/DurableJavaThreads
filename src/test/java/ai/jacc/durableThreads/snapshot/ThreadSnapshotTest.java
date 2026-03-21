package ai.jacc.durableThreads.snapshot;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThreadSnapshotTest {

    @Test
    void snapshotPreservesAllFields() {
        Instant now = Instant.now();
        var locals = List.of(
                new LocalVariable(0, "this", "Lcom/example/Foo;", new NullRef()),
                new LocalVariable(1, "x", "I", new PrimitiveRef(42))
        );
        var frame = new FrameSnapshot(
                "com/example/Foo", "doWork", "()V",
                42, 0, new byte[]{1, 2, 3}, locals);
        var heap = List.of(
                new ObjectSnapshot(1L, "com.example.Foo", ObjectKind.REGULAR,
                        Map.of("field1", new PrimitiveRef(10)), null)
        );
        var snapshot = new ThreadSnapshot(now, "worker-1", List.of(frame), heap);

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
        var bottom = new FrameSnapshot("com/example/Main", "main", "([Ljava/lang/String;)V",
                10, 0, new byte[0], List.of());
        var middle = new FrameSnapshot("com/example/Service", "process", "()V",
                25, 0, new byte[0], List.of());
        var top = new FrameSnapshot("com/example/Service", "compute", "(I)I",
                87, 0, new byte[0], List.of());

        var snapshot = new ThreadSnapshot(Instant.now(), "t1",
                List.of(bottom, middle, top), List.of());

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
        var fields = Map.<String, ObjectRef>of(
                "com.example.Foo.name", new PrimitiveRef("test"),
                "com.example.Foo.count", new PrimitiveRef(5)
        );
        var snap = new ObjectSnapshot(1L, "com.example.Foo", ObjectKind.REGULAR, fields, null);

        assertEquals(1L, snap.id());
        assertEquals(ObjectKind.REGULAR, snap.kind());
        assertEquals(2, snap.fields().size());
        assertNull(snap.arrayElements());
    }

    @Test
    void objectSnapshotArray() {
        ObjectRef[] elements = {new PrimitiveRef(1), new PrimitiveRef(2), new PrimitiveRef(3)};
        var snap = new ObjectSnapshot(2L, "[I", ObjectKind.ARRAY, Map.of(), elements);

        assertEquals(ObjectKind.ARRAY, snap.kind());
        assertEquals(3, snap.arrayElements().length);
    }

    @Test
    void objectSnapshotString() {
        var fields = Map.<String, ObjectRef>of("value", new PrimitiveRef("hello world"));
        var snap = new ObjectSnapshot(3L, "java.lang.String", ObjectKind.STRING, fields, null);

        assertEquals(ObjectKind.STRING, snap.kind());
        assertEquals("hello world", ((PrimitiveRef) snap.fields().get("value")).value());
    }
}
