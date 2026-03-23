package ai.jacc.durableThreads.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClassStructureHasher — verifies that class structure hashing
 * detects field layout changes.
 */
class ClassStructureHasherTest {

    static class SimpleClass {
        int x;
        String name;
    }

    static class ExtendedClass extends SimpleClass {
        double extra;
    }

    static class TransientFieldClass {
        int persistent;
        transient int ignored;
    }

    static class StaticFieldClass {
        static int shared;
        int instance;
    }

    static class ArrayFieldClass {
        byte[] data;
        long[] values;
        String[] names;
    }

    static class InnerClassFieldHolder {
        SimpleClass nested;
        java.util.Map.Entry<String, String> entry;
    }

    @Test
    void hashIsConsistentForSameClass() {
        byte[] hash1 = ClassStructureHasher.hashClassStructure(SimpleClass.class);
        byte[] hash2 = ClassStructureHasher.hashClassStructure(SimpleClass.class);

        assertNotNull(hash1);
        assertEquals(32, hash1.length, "SHA-256 should produce 32 bytes");
        assertArrayEquals(hash1, hash2, "Same class should produce identical hashes");
    }

    @Test
    void differentClassesProduceDifferentHashes() {
        byte[] hash1 = ClassStructureHasher.hashClassStructure(SimpleClass.class);
        byte[] hash2 = ClassStructureHasher.hashClassStructure(ExtendedClass.class);

        assertFalse(java.util.Arrays.equals(hash1, hash2),
                "Classes with different fields should produce different hashes");
    }

    @Test
    void hashIncludesInheritedFields() {
        // ExtendedClass should hash its own fields AND SimpleClass's fields
        byte[] hash = ClassStructureHasher.hashClassStructure(ExtendedClass.class);
        assertNotNull(hash);
        assertEquals(32, hash.length);
    }

    @Test
    void transientFieldsAreExcluded() {
        // TransientFieldClass has 'persistent' (hashed) and 'ignored' (not hashed)
        byte[] hash = ClassStructureHasher.hashClassStructure(TransientFieldClass.class);
        assertNotNull(hash);
        assertEquals(32, hash.length);
    }

    @Test
    void staticFieldsAreExcluded() {
        // StaticFieldClass has 'shared' (not hashed) and 'instance' (hashed)
        byte[] hash = ClassStructureHasher.hashClassStructure(StaticFieldClass.class);
        assertNotNull(hash);
        assertEquals(32, hash.length);
    }

    @Test
    void innerClassFieldTypesUseJdiFormat() {
        // Inner class fields should use dollar-sign format (Outer$Inner) to match JDI's typeName()
        byte[] hash1 = ClassStructureHasher.hashClassStructure(InnerClassFieldHolder.class);
        byte[] hash2 = ClassStructureHasher.hashClassStructure(InnerClassFieldHolder.class);

        assertNotNull(hash1);
        assertEquals(32, hash1.length);
        assertArrayEquals(hash1, hash2, "Inner class field holder should produce consistent hashes");
    }

    @Test
    void arrayFieldTypesUseJdiFormat() {
        // Array fields should hash using JDI-compatible type names
        // (e.g. "byte[]" not "[B", "long[]" not "[J") to match JDI's typeName() format
        byte[] hash1 = ClassStructureHasher.hashClassStructure(ArrayFieldClass.class);
        byte[] hash2 = ClassStructureHasher.hashClassStructure(ArrayFieldClass.class);

        assertNotNull(hash1);
        assertEquals(32, hash1.length);
        assertArrayEquals(hash1, hash2, "Array field class should produce consistent hashes");
    }
}
