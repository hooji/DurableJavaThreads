package ai.jacc.durableThreads.exception;

/**
 * Thrown when freeze() is called in a JVM without the durable agent loaded.
 */
public class AgentNotLoadedException extends RuntimeException {

    public AgentNotLoadedException() {
        super("Durable agent is not loaded. Start the JVM with: -javaagent:durable-threads.jar");
    }
}
