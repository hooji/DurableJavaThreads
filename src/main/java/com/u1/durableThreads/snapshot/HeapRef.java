package com.u1.durableThreads.snapshot;

/**
 * Reference to an object in the snapshot's heap.
 */
public record HeapRef(long id) implements ObjectRef {
}
