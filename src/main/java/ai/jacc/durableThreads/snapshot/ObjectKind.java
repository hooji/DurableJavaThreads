package ai.jacc.durableThreads.snapshot;

public enum ObjectKind {
    REGULAR,
    ARRAY,
    STRING,
    /**
     * A JDK collection (ArrayList, HashMap, HashSet, etc.) captured by
     * walking its internal element storage. Elements are stored in
     * {@link ObjectSnapshot#arrayElements()} — for maps, elements alternate
     * key/value (key0, val0, key1, val1, ...).
     */
    COLLECTION
}
