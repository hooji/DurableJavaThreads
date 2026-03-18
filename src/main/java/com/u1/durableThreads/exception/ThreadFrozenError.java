package com.u1.durableThreads.exception;

/**
 * Thrown internally to terminate the frozen thread.
 * An uncaught exception handler ignores this error.
 * Application code should never catch this.
 */
public class ThreadFrozenError extends Error {

    public ThreadFrozenError() {
        super("Thread has been frozen and must terminate");
    }
}
