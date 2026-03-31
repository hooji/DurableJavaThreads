package ai.jacc.durableThreads.internal;

import org.objenesis.ObjenesisStd;

/**
 * Shared {@link ObjenesisStd} instance for constructor-less object creation.
 *
 * <p>Objenesis caches instantiators per class, so sharing a single instance
 * avoids repeated cache warmup across HeapRestorer, ReflectionHelpers, and
 * ReplayState.</p>
 */
public final class ObjenesisHolder {

    private static final ObjenesisStd INSTANCE = new ObjenesisStd(true);

    private ObjenesisHolder() {}

    /** Returns the shared Objenesis instance. */
    public static ObjenesisStd get() {
        return INSTANCE;
    }
}
