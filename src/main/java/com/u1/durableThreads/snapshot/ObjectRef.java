package com.u1.durableThreads.snapshot;

import java.io.Serializable;

/**
 * A reference to an object in the snapshot.
 */
public sealed interface ObjectRef extends Serializable
        permits HeapRef, NullRef, PrimitiveRef {
}
