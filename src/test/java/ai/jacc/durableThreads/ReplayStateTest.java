package ai.jacc.durableThreads;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayStateTest {

    @AfterEach
    void cleanup() {
        // Ensure replay mode is deactivated after each test
        try {
            ReplayState.deactivate();
        } catch (Exception ignored) {}
    }

    @Test
    void normalThreadIsNotReplayThread() {
        assertFalse(ReplayState.isReplayThread());
    }

    @Test
    void activatedThreadIsReplayThread() {
        ReplayState.activate(new int[]{0, 1, 2});
        assertTrue(ReplayState.isReplayThread());
    }

    @Test
    void deactivateRestoresNormalMode() {
        ReplayState.activate(new int[]{0});
        assertTrue(ReplayState.isReplayThread());
        ReplayState.deactivate();
        assertFalse(ReplayState.isReplayThread());
    }

    @Test
    void currentResumeIndexReturnsCorrectValue() {
        ReplayState.activate(new int[]{5, 10, 15});
        assertEquals(5, ReplayState.currentResumeIndex());
    }

    @Test
    void advanceFrameMovesToNextFrame() {
        ReplayState.activate(new int[]{5, 10, 15});
        assertEquals(5, ReplayState.currentResumeIndex());

        ReplayState.advanceFrame();
        assertEquals(10, ReplayState.currentResumeIndex());

        ReplayState.advanceFrame();
        assertEquals(15, ReplayState.currentResumeIndex());
    }

    @Test
    void isLastFrameDetectsDeepestFrame() {
        ReplayState.activate(new int[]{0, 1, 2});

        assertFalse(ReplayState.isLastFrame()); // frame 0 of 3
        ReplayState.advanceFrame();
        assertFalse(ReplayState.isLastFrame()); // frame 1 of 3
        ReplayState.advanceFrame();
        assertTrue(ReplayState.isLastFrame());  // frame 2 of 3 (last)
    }

    @Test
    void singleFrameIsImmediatelyLast() {
        ReplayState.activate(new int[]{42});
        assertTrue(ReplayState.isLastFrame());
        assertEquals(42, ReplayState.currentResumeIndex());
    }

    @Test
    void replayStateIsThreadLocal() throws InterruptedException {
        ReplayState.activate(new int[]{99});

        boolean[] otherThreadResult = new boolean[1];
        Thread other = new Thread(() -> {
            otherThreadResult[0] = ReplayState.isReplayThread();
        });
        other.start();
        other.join();

        assertTrue(ReplayState.isReplayThread()); // this thread: activated
        assertFalse(otherThreadResult[0]);          // other thread: not activated
    }

    @Test
    void dummyInstanceCreatesNonNullObject() {
        Object obj = ReplayState.dummyInstance("java.lang.Object");
        assertNotNull(obj);
        assertInstanceOf(Object.class, obj);
    }

    @Test
    void dummyInstanceCreatesCorrectType() {
        Object obj = ReplayState.dummyInstance("java.util.ArrayList");
        assertNotNull(obj);
        assertInstanceOf(java.util.ArrayList.class, obj);
    }

    @Test
    void dummyInstanceThrowsForBadClass() {
        assertThrows(RuntimeException.class, () ->
                ReplayState.dummyInstance("com.nonexistent.FakeClass"));
    }
}
