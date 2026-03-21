package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.DurableAgent;

/**
 * Simple child-JVM program that verifies the durable agent is loaded.
 * Prints "AGENT_LOADED=true" or "AGENT_LOADED=false" to stdout.
 */
public class AgentLoadProgram {
    public static void main(String[] args) {
        System.out.println("AGENT_LOADED=" + DurableAgent.isLoaded());
    }
}
