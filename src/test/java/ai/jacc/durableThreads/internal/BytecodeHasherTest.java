package ai.jacc.durableThreads.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeHasherTest {

    @Test
    void hashIsConsistentForSameMethod() throws IOException {
        byte[] classBytes = loadClassBytes(BytecodeHasherTest.class);

        byte[] hash1 = BytecodeHasher.hash(classBytes,
                "hashIsConsistentForSameMethod", "()V");
        byte[] hash2 = BytecodeHasher.hash(classBytes,
                "hashIsConsistentForSameMethod", "()V");

        assertNotNull(hash1);
        assertArrayEquals(hash1, hash2);
    }

    @Test
    void differentMethodsHaveDifferentHashes() throws IOException {
        byte[] classBytes = loadClassBytes(BytecodeHasherTest.class);

        byte[] hash1 = BytecodeHasher.hash(classBytes,
                "hashIsConsistentForSameMethod", "()V");
        byte[] hash2 = BytecodeHasher.hash(classBytes,
                "differentMethodsHaveDifferentHashes", "()V");

        assertNotNull(hash1);
        assertNotNull(hash2);
        assertFalse(java.util.Arrays.equals(hash1, hash2));
    }

    @Test
    void returnsNullForNonexistentMethod() throws IOException {
        byte[] classBytes = loadClassBytes(BytecodeHasherTest.class);

        byte[] hash = BytecodeHasher.hash(classBytes, "doesNotExist", "()V");
        assertNull(hash);
    }

    @Test
    void hashIsSha256Length() throws IOException {
        byte[] classBytes = loadClassBytes(BytecodeHasherTest.class);

        byte[] hash = BytecodeHasher.hash(classBytes,
                "hashIsSha256Length", "()V");
        assertNotNull(hash);
        assertEquals(32, hash.length); // SHA-256 = 256 bits = 32 bytes
    }

    private static byte[] loadClassBytes(Class<?> clazz) throws IOException {
        String resourcePath = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Could not load class bytes for " + clazz.getName());
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            return buffer.toByteArray();
        }
    }
}
