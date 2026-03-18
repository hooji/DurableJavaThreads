package com.u1.durableThreads.internal;

import com.u1.durableThreads.snapshot.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeapRoundTripTest {

    // Simple test class for heap capture/restore
    static class Person {
        String name;
        int age;
        Person friend;

        Person() {}

        Person(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }

    @Test
    void capturePrimitive() {
        HeapWalker walker = new HeapWalker();
        ObjectRef ref = walker.capture(42);
        assertInstanceOf(PrimitiveRef.class, ref);
        assertEquals(42, ((PrimitiveRef) ref).value());
    }

    @Test
    void captureNull() {
        HeapWalker walker = new HeapWalker();
        ObjectRef ref = walker.capture(null);
        assertInstanceOf(NullRef.class, ref);
    }

    @Test
    void captureString() {
        HeapWalker walker = new HeapWalker();
        ObjectRef ref = walker.capture("hello");
        assertInstanceOf(HeapRef.class, ref);

        List<ObjectSnapshot> snapshots = walker.getSnapshots();
        assertEquals(1, snapshots.size());
        assertEquals(ObjectKind.STRING, snapshots.get(0).kind());
    }

    @Test
    void captureSimpleObject() {
        Person person = new Person("Alice", 30);
        HeapWalker walker = new HeapWalker();
        ObjectRef ref = walker.capture(person);

        assertInstanceOf(HeapRef.class, ref);
        List<ObjectSnapshot> snapshots = walker.getSnapshots();
        // Should have at least the person and the string "Alice"
        assertTrue(snapshots.size() >= 2);

        // Find the person snapshot
        long personId = ((HeapRef) ref).id();
        ObjectSnapshot personSnap = snapshots.stream()
                .filter(s -> s.id() == personId)
                .findFirst().orElseThrow();

        assertEquals(ObjectKind.REGULAR, personSnap.kind());
        assertEquals(Person.class.getName(), personSnap.className());
    }

    @Test
    void captureHandlesCircularReferences() {
        Person alice = new Person("Alice", 30);
        Person bob = new Person("Bob", 25);
        alice.friend = bob;
        bob.friend = alice;

        HeapWalker walker = new HeapWalker();
        ObjectRef ref = walker.capture(alice);

        // Should not infinite loop — circular refs handled via visited set
        assertInstanceOf(HeapRef.class, ref);
        List<ObjectSnapshot> snapshots = walker.getSnapshots();
        assertTrue(snapshots.size() >= 2); // at least alice and bob
    }

    @Test
    void captureIntArray() {
        int[] arr = {10, 20, 30};
        HeapWalker walker = new HeapWalker();
        ObjectRef ref = walker.capture(arr);

        assertInstanceOf(HeapRef.class, ref);
        List<ObjectSnapshot> snapshots = walker.getSnapshots();
        assertEquals(1, snapshots.size());

        ObjectSnapshot arraySnap = snapshots.get(0);
        assertEquals(ObjectKind.ARRAY, arraySnap.kind());
        assertEquals(3, arraySnap.arrayElements().length);

        // Verify values
        assertEquals(10, ((PrimitiveRef) arraySnap.arrayElements()[0]).value());
        assertEquals(20, ((PrimitiveRef) arraySnap.arrayElements()[1]).value());
        assertEquals(30, ((PrimitiveRef) arraySnap.arrayElements()[2]).value());
    }

    @Test
    void captureObjectArray() {
        String[] arr = {"hello", "world"};
        HeapWalker walker = new HeapWalker();
        ObjectRef ref = walker.capture(arr);

        assertInstanceOf(HeapRef.class, ref);
        List<ObjectSnapshot> snapshots = walker.getSnapshots();

        long arrId = ((HeapRef) ref).id();
        ObjectSnapshot arraySnap = snapshots.stream()
                .filter(s -> s.id() == arrId)
                .findFirst().orElseThrow();
        assertEquals(ObjectKind.ARRAY, arraySnap.kind());
        assertEquals(2, arraySnap.arrayElements().length);
    }

    @Test
    void restoreString() {
        var heap = List.of(
                new ObjectSnapshot(1L, "java.lang.String", ObjectKind.STRING,
                        Map.of("value", new PrimitiveRef("hello")), null)
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        assertEquals("hello", restored.get(1L));
    }

    @Test
    void restoreIntArray() {
        ObjectRef[] elements = {
                new PrimitiveRef(10), new PrimitiveRef(20), new PrimitiveRef(30)
        };
        var heap = List.of(
                new ObjectSnapshot(1L, "[I", ObjectKind.ARRAY, Map.of(), elements)
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        Object arr = restored.get(1L);
        assertInstanceOf(int[].class, arr);
        assertArrayEquals(new int[]{10, 20, 30}, (int[]) arr);
    }

    @Test
    void restoreSimpleObject() {
        var personFields = Map.<String, ObjectRef>of(
                Person.class.getName() + ".name", new HeapRef(2L),
                Person.class.getName() + ".age", new PrimitiveRef(30)
        );
        var heap = List.of(
                new ObjectSnapshot(1L, Person.class.getName(), ObjectKind.REGULAR,
                        personFields, null),
                new ObjectSnapshot(2L, "java.lang.String", ObjectKind.STRING,
                        Map.of("value", new PrimitiveRef("Alice")), null)
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        Object obj = restored.get(1L);
        assertInstanceOf(Person.class, obj);
        Person person = (Person) obj;
        assertEquals("Alice", person.name);
        assertEquals(30, person.age);
    }

    @Test
    void restoreCircularReferences() {
        var aliceFields = Map.<String, ObjectRef>of(
                Person.class.getName() + ".name", new HeapRef(3L),
                Person.class.getName() + ".age", new PrimitiveRef(30),
                Person.class.getName() + ".friend", new HeapRef(2L)
        );
        var bobFields = Map.<String, ObjectRef>of(
                Person.class.getName() + ".name", new HeapRef(4L),
                Person.class.getName() + ".age", new PrimitiveRef(25),
                Person.class.getName() + ".friend", new HeapRef(1L)
        );
        var heap = List.of(
                new ObjectSnapshot(1L, Person.class.getName(), ObjectKind.REGULAR,
                        aliceFields, null),
                new ObjectSnapshot(2L, Person.class.getName(), ObjectKind.REGULAR,
                        bobFields, null),
                new ObjectSnapshot(3L, "java.lang.String", ObjectKind.STRING,
                        Map.of("value", new PrimitiveRef("Alice")), null),
                new ObjectSnapshot(4L, "java.lang.String", ObjectKind.STRING,
                        Map.of("value", new PrimitiveRef("Bob")), null)
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        Person alice = (Person) restored.get(1L);
        Person bob = (Person) restored.get(2L);

        assertEquals("Alice", alice.name);
        assertEquals("Bob", bob.name);
        assertSame(bob, alice.friend);
        assertSame(alice, bob.friend);
    }

    @Test
    void resolveObjectRef() {
        var heap = List.of(
                new ObjectSnapshot(1L, "java.lang.String", ObjectKind.STRING,
                        Map.of("value", new PrimitiveRef("test")), null)
        );

        HeapRestorer restorer = new HeapRestorer();
        restorer.restoreAll(heap);

        assertNull(restorer.resolve(new NullRef()));
        assertEquals(42, restorer.resolve(new PrimitiveRef(42)));
        assertEquals("test", restorer.resolve(new HeapRef(1L)));
    }
}
