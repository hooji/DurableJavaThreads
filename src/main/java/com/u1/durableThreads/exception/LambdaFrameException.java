package com.u1.durableThreads.exception;

/**
 * Thrown when a lambda frame is detected in the call stack of a thread
 * being frozen.
 *
 * <p>Lambda-generated classes ({@code $$Lambda}) have JVM-specific names
 * that are not portable across JVM instances. A snapshot containing lambda
 * frames cannot be correctly restored because the replay mechanism cannot
 * re-enter the lambda's call site.</p>
 *
 * <p>To fix: refactor the lambda into a named method or inner class, or
 * move the {@code Durable.freeze()} call outside the lambda.</p>
 */
public class LambdaFrameException extends RuntimeException {

    private final String lambdaClassName;
    private final String enclosingMethod;

    public LambdaFrameException(String lambdaClassName, String enclosingMethod) {
        super("Cannot freeze thread: lambda frame detected in call stack. "
                + "Lambda class '" + lambdaClassName
                + "' (in " + enclosingMethod + ") cannot be replayed during restore. "
                + "Move Durable.freeze() outside the lambda, or refactor the lambda "
                + "into a named method.");
        this.lambdaClassName = lambdaClassName;
        this.enclosingMethod = enclosingMethod;
    }

    /** The JVM-generated lambda class name (e.g., {@code com/example/Foo$$Lambda/0x123}). */
    public String lambdaClassName() {
        return lambdaClassName;
    }

    /** The enclosing method where the lambda was defined. */
    public String enclosingMethod() {
        return enclosingMethod;
    }
}
