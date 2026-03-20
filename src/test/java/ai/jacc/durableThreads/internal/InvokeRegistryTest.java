package ai.jacc.durableThreads.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InvokeRegistryTest {

    @Test
    void keyFormat() {
        String key = InvokeRegistry.key("com/example/Foo", "doWork", "(I)V");
        assertEquals("com/example/Foo#doWork+(I)V", key);
    }

    @Test
    void registerAndRetrieveOffsets() {
        String key = InvokeRegistry.key("com/test/A", "method1", "()V");
        List<Integer> offsets = List.of(10, 25, 42);
        InvokeRegistry.register(key, offsets);

        List<Integer> retrieved = InvokeRegistry.getInvokeOffsets(key);
        assertNotNull(retrieved);
        assertEquals(3, retrieved.size());
        assertEquals(List.of(10, 25, 42), retrieved);
    }

    @Test
    void getInvokeIndexFindsExactMatch() {
        String key = InvokeRegistry.key("com/test/B", "method2", "()V");
        InvokeRegistry.register(key, List.of(10, 25, 42));

        assertEquals(0, InvokeRegistry.getInvokeIndex(key, 10));
        assertEquals(1, InvokeRegistry.getInvokeIndex(key, 25));
        assertEquals(2, InvokeRegistry.getInvokeIndex(key, 42));
    }

    @Test
    void getInvokeIndexFindsNearestBefore() {
        String key = InvokeRegistry.key("com/test/C", "method3", "()V");
        InvokeRegistry.register(key, List.of(10, 25, 42));

        // BCP 30 is between invokes at 25 and 42 → should return index 1
        assertEquals(1, InvokeRegistry.getInvokeIndex(key, 30));
        // BCP 50 is after all invokes → should return index 2
        assertEquals(2, InvokeRegistry.getInvokeIndex(key, 50));
    }

    @Test
    void getInvokeIndexReturnsNegativeForUnknownKey() {
        assertEquals(-1, InvokeRegistry.getInvokeIndex("nonexistent#m+()V", 10));
    }

    @Test
    void getInvokeIndexReturnsNegativeForBcpBeforeFirstInvoke() {
        String key = InvokeRegistry.key("com/test/D", "method4", "()V");
        InvokeRegistry.register(key, List.of(10, 25));

        assertEquals(-1, InvokeRegistry.getInvokeIndex(key, 5));
    }

    @Test
    void storeAndRetrieveInstrumentedBytecode() {
        byte[] bytecode = {1, 2, 3, 4, 5};
        InvokeRegistry.storeInstrumentedBytecode("com/test/E", bytecode);

        assertTrue(InvokeRegistry.isInstrumented("com/test/E"));
        assertArrayEquals(bytecode, InvokeRegistry.getInstrumentedBytecode("com/test/E"));
    }

    @Test
    void uninstrumentedClassReturnsNull() {
        assertFalse(InvokeRegistry.isInstrumented("com/test/Nonexistent"));
        assertNull(InvokeRegistry.getInstrumentedBytecode("com/test/Nonexistent"));
    }
}
