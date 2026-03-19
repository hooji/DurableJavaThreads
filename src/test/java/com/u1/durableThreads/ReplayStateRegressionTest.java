package com.u1.durableThreads;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link ReplayState}, focusing on the
 * {@code signalRestoreError} / {@code resumePoint()} interaction.
 *
 * <p>A past regression introduced a race condition: cleanup exceptions
 * (from {@code tr.resume()} or {@code vm.dispose()}) in the JDI worker's
 * finally blocks could trigger {@code signalRestoreError} after a successful
 * restore, poisoning the replay thread even though locals were already set.
 * These tests verify the signaling contract.</p>
 */
class ReplayStateRegressionTest {

    @AfterEach
    void cleanup() {
        try { ReplayState.deactivate(); } catch (Exception ignored) {}
        // Clear any stale error
        try { ReplayState.resumePoint(); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------
    // signalRestoreError → resumePoint contract
    // ---------------------------------------------------------------

    /**
     * When signalRestoreError is called BEFORE releaseResumePoint, the
     * replay thread must throw RuntimeException after waking.
     */
    @Test
    void resumePointThrowsWhenErrorSignalledBeforeRelease() throws InterruptedException {
        ReplayState.activateWithLatch(new int[]{0});

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch threadDone = new CountDownLatch(1);

        Thread replay = new Thread(() -> {
            try {
                ReplayState.resumePoint();
            } catch (RuntimeException e) {
                thrown.set(e);
            } finally {
                threadDone.countDown();
            }
        });
        replay.start();

        // Give the thread time to block on the latch
        Thread.sleep(50);

        // Simulate JDI worker failure: signal error, then release
        ReplayState.signalRestoreError("simulated JDI failure");
        ReplayState.releaseResumePoint();

        assertTrue(threadDone.await(2, TimeUnit.SECONDS), "Replay thread should complete");
        assertNotNull(thrown.get(), "resumePoint should throw when error was signalled");
        assertTrue(thrown.get().getMessage().contains("simulated JDI failure"),
                "Exception message should contain the signalled error");
    }

    /**
     * When NO error is signalled, resumePoint must return normally after
     * the latch is released.
     */
    @Test
    void resumePointReturnsNormallyWhenNoError() throws InterruptedException {
        ReplayState.activateWithLatch(new int[]{0});

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch threadDone = new CountDownLatch(1);

        Thread replay = new Thread(() -> {
            try {
                ReplayState.resumePoint();
            } catch (RuntimeException e) {
                thrown.set(e);
            } finally {
                threadDone.countDown();
            }
        });
        replay.start();

        Thread.sleep(50);

        // Simulate successful restore: no signalRestoreError, just release
        ReplayState.releaseResumePoint();

        assertTrue(threadDone.await(2, TimeUnit.SECONDS));
        assertNull(thrown.get(),
                "resumePoint must NOT throw when no error was signalled");
    }

    /**
     * activateWithLatch must clear any stale restoreError from a previous
     * failed restore. Without this, a past failure could poison a subsequent
     * restore attempt.
     */
    @Test
    void activateWithLatchClearsStaleError() throws InterruptedException {
        // First "restore" — signal an error
        ReplayState.activateWithLatch(new int[]{0});
        ReplayState.signalRestoreError("old error");
        ReplayState.releaseResumePoint();

        // Consume the error
        try { ReplayState.resumePoint(); } catch (RuntimeException ignored) {}

        // Second "restore" — no error
        ReplayState.activateWithLatch(new int[]{0});

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch threadDone = new CountDownLatch(1);

        Thread replay = new Thread(() -> {
            try {
                ReplayState.resumePoint();
            } catch (RuntimeException e) {
                thrown.set(e);
            } finally {
                threadDone.countDown();
            }
        });
        replay.start();

        Thread.sleep(50);
        ReplayState.releaseResumePoint();

        assertTrue(threadDone.await(2, TimeUnit.SECONDS));
        assertNull(thrown.get(),
                "Stale error from a previous restore must not leak into a new restore");
    }

    /**
     * If signalRestoreError is called but the latch is never released,
     * the replay thread must stay blocked (not wake up due to the error signal alone).
     * The error is only checked AFTER the latch is released.
     */
    @Test
    void signalErrorAloneDoesNotUnblockReplayThread() throws InterruptedException {
        ReplayState.activateWithLatch(new int[]{0});

        boolean[] completed = new boolean[1];
        Thread replay = new Thread(() -> {
            try {
                ReplayState.resumePoint();
            } catch (RuntimeException ignored) {}
            completed[0] = true;
        });
        replay.start();

        Thread.sleep(50);

        // Signal error but do NOT release the latch
        ReplayState.signalRestoreError("error without release");
        Thread.sleep(100);

        assertFalse(completed[0],
                "signalRestoreError alone must NOT unblock the replay thread — "
                + "the latch must be explicitly released");

        // Clean up: release so thread can finish
        ReplayState.releaseResumePoint();
        replay.join(2000);
        assertTrue(completed[0]);
    }

    // ---------------------------------------------------------------
    // Race condition regression: error signalled AFTER success
    // ---------------------------------------------------------------

    /**
     * Simulate the exact race condition that caused the original regression:
     * 1. JDI worker sets locals successfully
     * 2. JDI worker calls deactivate() + releaseResumePoint()
     * 3. Replay thread wakes and should see NO error
     * 4. AFTER that, a cleanup exception causes signalRestoreError to be called
     *
     * The replay thread must NOT be affected by the late error signal.
     * This tests the invariant that signalRestoreError must be called
     * BEFORE releaseResumePoint to have any effect.
     */
    @Test
    void lateErrorSignalAfterReleaseDoesNotAffectReplayThread() throws InterruptedException {
        ReplayState.activateWithLatch(new int[]{0});

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch replayDone = new CountDownLatch(1);
        CountDownLatch replayWokeUp = new CountDownLatch(1);

        Thread replay = new Thread(() -> {
            try {
                ReplayState.resumePoint();
                replayWokeUp.countDown(); // signal that we returned normally
            } catch (RuntimeException e) {
                thrown.set(e);
                replayWokeUp.countDown();
            } finally {
                replayDone.countDown();
            }
        });
        replay.start();

        Thread.sleep(50);

        // Simulate successful restore: release the latch (no error signal)
        ReplayState.releaseResumePoint();

        // Wait for the replay thread to wake up and pass the error check
        assertTrue(replayWokeUp.await(2, TimeUnit.SECONDS));
        assertNull(thrown.get(),
                "Replay thread should return normally from resumePoint");

        // NOW signal an error (simulating a late cleanup exception).
        // This must NOT retroactively affect the already-completed resumePoint.
        ReplayState.signalRestoreError("late cleanup error");

        assertTrue(replayDone.await(2, TimeUnit.SECONDS));
        assertNull(thrown.get(),
                "Late error signal after release must not affect the replay thread");
    }

    /**
     * Multiple signalRestoreError calls should use the last message.
     * This verifies the volatile field semantics.
     */
    @Test
    void multipleErrorSignalsUsesLast() throws InterruptedException {
        ReplayState.activateWithLatch(new int[]{0});

        AtomicReference<String> errorMsg = new AtomicReference<>();
        CountDownLatch threadDone = new CountDownLatch(1);

        Thread replay = new Thread(() -> {
            try {
                ReplayState.resumePoint();
            } catch (RuntimeException e) {
                errorMsg.set(e.getMessage());
            } finally {
                threadDone.countDown();
            }
        });
        replay.start();

        Thread.sleep(50);

        ReplayState.signalRestoreError("first error");
        ReplayState.signalRestoreError("second error");
        ReplayState.signalRestoreError("final error");
        ReplayState.releaseResumePoint();

        assertTrue(threadDone.await(2, TimeUnit.SECONDS));
        assertNotNull(errorMsg.get());
        assertTrue(errorMsg.get().contains("final error"),
                "Should use the last signalled error, got: " + errorMsg.get());
    }

    // ---------------------------------------------------------------
    // dummyInstance regression
    // ---------------------------------------------------------------

    /**
     * dummyInstance must throw RuntimeException (not return null) for
     * nonexistent classes. A past version returned null, which caused
     * the prologue to call instance methods on null → NPE.
     */
    @Test
    void dummyInstanceThrowsForNonexistentClass() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> ReplayState.dummyInstance("com.nonexistent.FakeClass123"));

        assertTrue(ex.getMessage().contains("FakeClass123"),
                "Exception should mention the class name");
        assertNotNull(ex.getCause(),
                "Should wrap the original ClassNotFoundException");
    }

    /**
     * dummyInstance must return a non-null, correctly-typed instance.
     * The old version could return null if Unsafe.allocateInstance failed,
     * which would cause NPE when the prologue tried to call methods on it.
     */
    @Test
    void dummyInstanceNeverReturnsNull() {
        // Test with various types that could trip up Unsafe.allocateInstance
        String[] classNames = {
                "java.lang.Object",
                "java.util.ArrayList",
                "java.util.HashMap",
                "java.lang.StringBuilder",
                "java.util.concurrent.ConcurrentHashMap"
        };

        for (String className : classNames) {
            Object instance = ReplayState.dummyInstance(className);
            assertNotNull(instance,
                    "dummyInstance must never return null for " + className);
        }
    }

    /**
     * dummyInstance must create an instance of the exact type requested,
     * not a supertype or proxy. The replay prologue does CHECKCAST after
     * calling dummyInstance, so the type must match exactly.
     */
    @Test
    void dummyInstanceCreatesExactType() {
        Object obj = ReplayState.dummyInstance("java.util.ArrayList");
        assertEquals("java.util.ArrayList", obj.getClass().getName(),
                "dummyInstance must create the exact requested type");
    }

    /**
     * dummyInstance must work for inner classes (which have $ in the name).
     * Anonymous inner classes in test programs are a common case.
     */
    @Test
    void dummyInstanceWorksForInnerClasses() {
        // java.util.AbstractMap$SimpleEntry is a well-known inner class
        Object obj = ReplayState.dummyInstance("java.util.AbstractMap$SimpleEntry");
        assertNotNull(obj);
        assertInstanceOf(java.util.AbstractMap.SimpleEntry.class, obj);
    }
}
