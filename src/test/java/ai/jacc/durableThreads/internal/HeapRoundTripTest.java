package ai.jacc.durableThreads.internal;

import ai.jacc.durableThreads.snapshot.*;
import org.junit.jupiter.api.Test;

import java.util.*;

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
                .findFirst().orElseThrow(() -> new NoSuchElementException());

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
                .findFirst().orElseThrow(() -> new NoSuchElementException());
        assertEquals(ObjectKind.ARRAY, arraySnap.kind());
        assertEquals(2, arraySnap.arrayElements().length);
    }

    @Test
    void restoreString() {
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef("hello"));
        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, "java.lang.String", ObjectKind.STRING,
                        fields, null)
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
        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, "[I", ObjectKind.ARRAY, Collections.<String, ObjectRef>emptyMap(), elements)
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        Object arr = restored.get(1L);
        assertInstanceOf(int[].class, arr);
        assertArrayEquals(new int[]{10, 20, 30}, (int[]) arr);
    }

    @Test
    void restoreSimpleObject() {
        Map<String, ObjectRef> personFields = new LinkedHashMap<>();
        personFields.put(Person.class.getName() + ".name", new HeapRef(2L));
        personFields.put(Person.class.getName() + ".age", new PrimitiveRef(30));

        Map<String, ObjectRef> stringFields = new LinkedHashMap<>();
        stringFields.put("value", new PrimitiveRef("Alice"));

        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, Person.class.getName(), ObjectKind.REGULAR,
                        personFields, null),
                new ObjectSnapshot(2L, "java.lang.String", ObjectKind.STRING,
                        stringFields, null)
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
        Map<String, ObjectRef> aliceFields = new LinkedHashMap<>();
        aliceFields.put(Person.class.getName() + ".name", new HeapRef(3L));
        aliceFields.put(Person.class.getName() + ".age", new PrimitiveRef(30));
        aliceFields.put(Person.class.getName() + ".friend", new HeapRef(2L));

        Map<String, ObjectRef> bobFields = new LinkedHashMap<>();
        bobFields.put(Person.class.getName() + ".name", new HeapRef(4L));
        bobFields.put(Person.class.getName() + ".age", new PrimitiveRef(25));
        bobFields.put(Person.class.getName() + ".friend", new HeapRef(1L));

        Map<String, ObjectRef> aliceNameFields = new LinkedHashMap<>();
        aliceNameFields.put("value", new PrimitiveRef("Alice"));

        Map<String, ObjectRef> bobNameFields = new LinkedHashMap<>();
        bobNameFields.put("value", new PrimitiveRef("Bob"));

        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, Person.class.getName(), ObjectKind.REGULAR,
                        aliceFields, null),
                new ObjectSnapshot(2L, Person.class.getName(), ObjectKind.REGULAR,
                        bobFields, null),
                new ObjectSnapshot(3L, "java.lang.String", ObjectKind.STRING,
                        aliceNameFields, null),
                new ObjectSnapshot(4L, "java.lang.String", ObjectKind.STRING,
                        bobNameFields, null)
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
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef("test"));
        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, "java.lang.String", ObjectKind.STRING,
                        fields, null)
        );

        HeapRestorer restorer = new HeapRestorer();
        restorer.restoreAll(heap);

        assertNull(restorer.resolve(new NullRef()));
        assertEquals(42, restorer.resolve(new PrimitiveRef(42)));
        assertEquals("test", restorer.resolve(new HeapRef(1L)));
    }

    // ===================================================================
    // Collection restore tests
    // ===================================================================

    @Test
    void restoreArrayList() {
        Map<String, ObjectRef> alphaFields = new LinkedHashMap<>();
        alphaFields.put("value", new PrimitiveRef("alpha"));
        Map<String, ObjectRef> betaFields = new LinkedHashMap<>();
        betaFields.put("value", new PrimitiveRef("beta"));
        Map<String, ObjectRef> gammaFields = new LinkedHashMap<>();
        gammaFields.put("value", new PrimitiveRef("gamma"));

        // ArrayList with three string elements
        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, "java.util.ArrayList", ObjectKind.COLLECTION,
                        Collections.<String, ObjectRef>emptyMap(),
                        new ObjectRef[]{
                                new HeapRef(2L), new HeapRef(3L), new HeapRef(4L)
                        }),
                new ObjectSnapshot(2L, "java.lang.String", ObjectKind.STRING,
                        alphaFields, null),
                new ObjectSnapshot(3L, "java.lang.String", ObjectKind.STRING,
                        betaFields, null),
                new ObjectSnapshot(4L, "java.lang.String", ObjectKind.STRING,
                        gammaFields, null)
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        Object obj = restored.get(1L);
        assertInstanceOf(java.util.ArrayList.class, obj);
        @SuppressWarnings("unchecked")
        java.util.ArrayList<String> list = (java.util.ArrayList<String>) obj;
        assertEquals(3, list.size());
        assertEquals("alpha", list.get(0));
        assertEquals("beta", list.get(1));
        assertEquals("gamma", list.get(2));
    }

    @Test
    void restoreHashMap() {
        Map<String, ObjectRef> key1Fields = new LinkedHashMap<>();
        key1Fields.put("value", new PrimitiveRef("key1"));
        Map<String, ObjectRef> key2Fields = new LinkedHashMap<>();
        key2Fields.put("value", new PrimitiveRef("key2"));

        // HashMap with two key-value pairs (interleaved: k1, v1, k2, v2)
        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, "java.util.HashMap", ObjectKind.COLLECTION,
                        Collections.<String, ObjectRef>emptyMap(),
                        new ObjectRef[]{
                                new HeapRef(2L), new PrimitiveRef(100),
                                new HeapRef(3L), new PrimitiveRef(200)
                        }),
                new ObjectSnapshot(2L, "java.lang.String", ObjectKind.STRING,
                        key1Fields, null),
                new ObjectSnapshot(3L, "java.lang.String", ObjectKind.STRING,
                        key2Fields, null)
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        Object obj = restored.get(1L);
        assertInstanceOf(java.util.HashMap.class, obj);
        @SuppressWarnings("unchecked")
        java.util.HashMap<String, Object> map = (java.util.HashMap<String, Object>) obj;
        assertEquals(2, map.size());
        assertEquals(100, map.get("key1"));
        assertEquals(200, map.get("key2"));
    }

    @Test
    void restoreHashSet() {
        // HashSet with elements (just values, no pairs)
        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, "java.util.HashSet", ObjectKind.COLLECTION,
                        Collections.<String, ObjectRef>emptyMap(),
                        new ObjectRef[]{
                                new PrimitiveRef(10), new PrimitiveRef(20), new PrimitiveRef(30)
                        })
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        Object obj = restored.get(1L);
        assertInstanceOf(java.util.HashSet.class, obj);
        @SuppressWarnings("unchecked")
        java.util.HashSet<Object> set = (java.util.HashSet<Object>) obj;
        assertEquals(3, set.size());
        assertTrue(set.contains(10));
        assertTrue(set.contains(20));
        assertTrue(set.contains(30));
    }

    @Test
    void restoreCollectionWithNonSerializableElements() {
        // ArrayList containing Person objects (non-Serializable)
        Map<String, ObjectRef> personFields = new LinkedHashMap<>();
        personFields.put(Person.class.getName() + ".name", new HeapRef(3L));
        personFields.put(Person.class.getName() + ".age", new PrimitiveRef(42));

        Map<String, ObjectRef> nameFields = new LinkedHashMap<>();
        nameFields.put("value", new PrimitiveRef("Charlie"));

        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, "java.util.ArrayList", ObjectKind.COLLECTION,
                        Collections.<String, ObjectRef>emptyMap(),
                        new ObjectRef[]{new HeapRef(2L)}),
                new ObjectSnapshot(2L, Person.class.getName(), ObjectKind.REGULAR,
                        personFields, null),
                new ObjectSnapshot(3L, "java.lang.String", ObjectKind.STRING,
                        nameFields, null)
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        @SuppressWarnings("unchecked")
        java.util.ArrayList<Person> list = (java.util.ArrayList<Person>) restored.get(1L);
        assertEquals(1, list.size());
        assertEquals("Charlie", list.get(0).name);
        assertEquals(42, list.get(0).age);
    }

    // ===================================================================
    // Identity and cycle tests
    // ===================================================================

    @Test
    void multipleReferencesToSameObjectPreserveIdentity() {
        // Two Person objects both reference the same friend object
        Map<String, ObjectRef> sharedFriendFields = new LinkedHashMap<>();
        sharedFriendFields.put(Person.class.getName() + ".name", new HeapRef(4L));
        sharedFriendFields.put(Person.class.getName() + ".age", new PrimitiveRef(20));

        Map<String, ObjectRef> aliceFields = new LinkedHashMap<>();
        aliceFields.put(Person.class.getName() + ".name", new HeapRef(5L));
        aliceFields.put(Person.class.getName() + ".age", new PrimitiveRef(30));
        aliceFields.put(Person.class.getName() + ".friend", new HeapRef(3L));

        Map<String, ObjectRef> bobFields = new LinkedHashMap<>();
        bobFields.put(Person.class.getName() + ".name", new HeapRef(6L));
        bobFields.put(Person.class.getName() + ".age", new PrimitiveRef(25));
        bobFields.put(Person.class.getName() + ".friend", new HeapRef(3L));

        Map<String, ObjectRef> charlieNameFields = new LinkedHashMap<>();
        charlieNameFields.put("value", new PrimitiveRef("Charlie"));
        Map<String, ObjectRef> aliceNameFields = new LinkedHashMap<>();
        aliceNameFields.put("value", new PrimitiveRef("Alice"));
        Map<String, ObjectRef> bobNameFields = new LinkedHashMap<>();
        bobNameFields.put("value", new PrimitiveRef("Bob"));

        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, Person.class.getName(), ObjectKind.REGULAR,
                        aliceFields, null),
                new ObjectSnapshot(2L, Person.class.getName(), ObjectKind.REGULAR,
                        bobFields, null),
                new ObjectSnapshot(3L, Person.class.getName(), ObjectKind.REGULAR,
                        sharedFriendFields, null),
                new ObjectSnapshot(4L, "java.lang.String", ObjectKind.STRING,
                        charlieNameFields, null),
                new ObjectSnapshot(5L, "java.lang.String", ObjectKind.STRING,
                        aliceNameFields, null),
                new ObjectSnapshot(6L, "java.lang.String", ObjectKind.STRING,
                        bobNameFields, null)
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        Person alice = (Person) restored.get(1L);
        Person bob = (Person) restored.get(2L);

        // Both should reference the SAME friend object (identity, not just equality)
        assertNotNull(alice.friend);
        assertNotNull(bob.friend);
        assertSame(alice.friend, bob.friend, "Multiple refs to same object must preserve identity");
        assertEquals("Charlie", alice.friend.name);
    }

    @Test
    void selfReferentialObjectPreservesIdentity() {
        // A Person whose friend is itself
        Map<String, ObjectRef> selfFields = new LinkedHashMap<>();
        selfFields.put(Person.class.getName() + ".name", new HeapRef(2L));
        selfFields.put(Person.class.getName() + ".age", new PrimitiveRef(99));
        selfFields.put(Person.class.getName() + ".friend", new HeapRef(1L));

        Map<String, ObjectRef> nameFields = new LinkedHashMap<>();
        nameFields.put("value", new PrimitiveRef("Narcissus"));

        List<ObjectSnapshot> heap = Arrays.asList(
                new ObjectSnapshot(1L, Person.class.getName(), ObjectKind.REGULAR,
                        selfFields, null),
                new ObjectSnapshot(2L, "java.lang.String", ObjectKind.STRING,
                        nameFields, null)
        );

        HeapRestorer restorer = new HeapRestorer();
        Map<Long, Object> restored = restorer.restoreAll(heap);

        Person person = (Person) restored.get(1L);
        assertEquals("Narcissus", person.name);
        assertSame(person, person.friend, "Self-referencing object should point to itself");
    }
}
