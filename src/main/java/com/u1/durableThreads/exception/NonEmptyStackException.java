package com.u1.durableThreads.exception;

/**
 * Thrown at freeze time when a frame in the call stack has values on the
 * operand stack that cannot be captured or restored.
 *
 * <p>The JDI debug interface does not expose operand stack values, so threads
 * can only be frozen when all frames have a clean (empty) operand stack at
 * their call sites. This means {@code Durable.freeze()} must not be called
 * from within an expression that leaves intermediate values on the stack.</p>
 *
 * <p><b>Example of code that would trigger this:</b></p>
 * <pre>{@code
 * // BAD: freeze() is called while compute()'s return value is on the stack
 * int result = compute() + helperThatFreezes();
 * }</pre>
 *
 * <p><b>Fix:</b></p>
 * <pre>{@code
 * // GOOD: each call is on its own statement
 * int a = compute();
 * int b = helperThatFreezes();  // freeze happens inside here
 * int result = a + b;
 * }</pre>
 */
public class NonEmptyStackException extends RuntimeException {

    private final String frameSummary;

    public NonEmptyStackException(String frameSummary) {
        super("Cannot freeze: non-empty operand stack detected. " + frameSummary);
        this.frameSummary = frameSummary;
    }

    /** Details about which frame has the non-empty stack. */
    public String frameSummary() {
        return frameSummary;
    }
}
