# Durable Java Threads -- Code Audit

> Audit performed against v1.3.5 source code. Focus: enterprise hardening,
> bug detection, simplification opportunities, and robustness improvements.

---

## Executive Summary

The library is well-architected with clear separation of concerns and thorough
error handling. The bytecode instrumentation pipeline, JDI integration, and
snapshot format are all solid. Below are findings organized by severity.

---

## CRITICAL -- Potential Bugs

### C1. `ThreadFreezer.freeze()` typo: `AssertionError` instead of `AssertionError`

**File:** `ThreadFreezer.java:138`

```java
throw new AssertionError(
```

This is spelled `AssertionError` -- which does not exist in the JDK. This should
be `AssertionError`. However, looking more carefully, Java's class is actually
`java.lang.AssertionError` -- wait, no. The correct JDK class is
**`java.lang.AssertionError`**. Let me re-check: the JDK class is
`AssertionError`. This compiles, so it's fine. (False alarm -- `AssertionError`
is not a typo; I was second-guessing myself.)

**ACTUAL ISSUE**: The real concern here is the `[EXPERIMENT]` comment. This is a
safety-net throw at the end of `freeze()` that should theoretically never be
reached. For enterprise use, this should either:
- Be converted to a proper named exception, or
- Have structured logging instead of embedding debug state in the message string

**Severity:** Low (code path is defensive, but the EXPERIMENT tag suggests
incomplete confidence in the termination mechanism)

---

### C2. `JdiValueConverter.convertToJdiValue()` ignores `restoredHeap` and `heapRestorer` parameters

**File:** `JdiValueConverter.java:22-33`

```java
static Value convertToJdiValue(VirtualMachine vm, ObjectRef ref,
                               java.util.Map<Long, Object> restoredHeap,
                               HeapRestorer heapRestorer) {
    if (ref instanceof NullRef) {
        return null;
    } else if (ref instanceof PrimitiveRef) {
        return convertPrimitiveToJdiValue(vm, ((PrimitiveRef) ref).value());
    } else if (ref instanceof HeapRef) {
        return resolveHeapRefViaJdi(vm, ((HeapRef) ref).id());
    }
    return null;
}
```

The `restoredHeap` and `heapRestorer` parameters are accepted but **never used**.
The method resolves heap refs exclusively via `HeapObjectBridge` + JDI. These
parameters are vestigial from an earlier design and should be removed to avoid
confusion.

**Recommendation:** Remove unused parameters from `convertToJdiValue()` and
update all call sites (`JdiLocalSetter.setFrameLocals()`).

---

### C3. `HeapObjectBridge.clear()` race in `ThreadRestorer`

**File:** `ThreadRestorer.java:68-71` and `ThreadRestorer.java:315`

The bridge is populated at line 68-71:
```java
HeapObjectBridge.clear();
for (Map.Entry<Long, Object> entry : restoredHeap.entrySet()) {
    HeapObjectBridge.put(entry.getKey(), entry.getValue());
}
```

And cleared again at line 315 (inside `runJdiRestore`):
```java
HeapObjectBridge.clear();
```

The first `clear()` + populate happens in the `restore()` method under
`synchronized(Durable.class)`. The second `clear()` at line 315 happens in the
JDI worker thread, which runs **outside** the Durable.class monitor (the monitor
is released before `jdiWorker.join()`). 

Wait -- re-reading: `ThreadRestorer.restore()` is called inside
`synchronized(Durable.class)` in `Durable.restore()`, and `jdiWorker.join()`
happens inside `ThreadRestorer.restore()`, so the monitor IS held during the
entire JDI worker lifecycle. This is safe.

**However**, if the JDI worker fails and the error path at line 323 releases
the go-latch, the replay thread could wake up and proceed while
`HeapObjectBridge` still contains stale data. The `clear()` in the success path
(line 315) would not execute in the error case.

**Recommendation:** Move `HeapObjectBridge.clear()` into a `finally` block in
`runJdiRestore()` to ensure cleanup on both success and error paths.

---

### C4. `FreezeFlag` uses `IdentityHashMap`-backed Set but Thread objects are long-lived

**File:** `ThreadFreezer.java:476-478`

```java
private static final Set<Thread> frozenThreads =
    Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
```

After `clearFrozen()` is called, the entry is removed. But if the
`ThreadFrozenError` is caught by user code (despite extending `Error`), or if
the clear+throw sequence is interrupted, the Thread reference stays in the set
permanently, preventing GC of the Thread object.

**Recommendation:** This is a minor leak risk. The current code is careful about
clearing, but for enterprise hardening, consider using a `WeakHashMap` variant
or periodically purging entries for dead threads.

---

### C5. `ThreadRestorer.waitForThreadAtMethod()` polling loop suspends/resumes repeatedly

**File:** `ThreadRestorer.java:340-365`

The polling loop calls `isAtMethod()` which does `tr.suspend()` / `tr.frames()`
/ `tr.resume()` on every iteration (every 10ms). This is expensive JDI work and
could interfere with the replay thread's progress toward `awaitGoLatch()`.

Each suspend/resume cycle:
1. Sends a JDWP SuspendThread command
2. Reads up to 10 frames
3. Sends a JDWP ResumeThread command

**Recommendation:** Only call `isAtMethod()` when the thread status indicates
WAIT/SLEEPING. The status check is already there, but move the `isAtMethod()`
inside a rate-limited path (e.g., check status first, only inspect frames every
50-100ms instead of 10ms).

---

## HIGH -- Robustness Improvements

### H1. `ReplayState` static fields (`goLatch`, `restoreError`) lack cleanup guarantees

**File:** `ReplayState.java:68-82`

If a restore fails after `activateWithLatch()` but before the go-latch is
released, these static fields retain stale values. The next restore will
overwrite them (line 183-185), but there's a window where:
- `restoreError` from a failed restore could bleed into a subsequent restore
- `goLatch` from a failed restore could be read by `getGoLatch()`

The serialization via `synchronized(Durable.class)` makes this unlikely but
not impossible if an exception escapes the synchronized block.

**Recommendation:** Add a `ReplayState.reset()` method that clears all static
state, called in a `finally` block in `ThreadRestorer.restore()`.

---

### H2. `JdiLocalSetter.preloadSnapshotClasses()` calls `forceLoadClass()` which invokes methods on suspended thread

**File:** `JdiLocalSetter.java:296-299`

```java
tr.suspend();
try {
    JdiLocalSetter.preloadSnapshotClasses(vm, tr, snapshot);
} finally {
    tr.resume();
}
```

But `preloadSnapshotClasses()` calls `forceLoadClass()` which calls
`classType.invokeMethod(threadRef, ...)`. Invoking methods via JDI requires the
thread to be **running** (or at least suspended at a safe point). Calling
`invokeMethod()` on a suspended thread from outside should work (JDI handles
resume-invoke-suspend internally), but the thread's `StackFrame` references
become invalid after `invokeMethod()`.

This is called BEFORE `setLocalsViaJdi()`, so frame invalidation here is fine.
But if `invokeMethod()` fails (the comment mentions Java 8 WAITING thread
issues), the `Class.forName()` fallback at line 291 loads the class in the
current JVM -- which IS the same JVM, so this works. Good.

**No action needed** -- but the code comment could be clearer about why this
ordering is safe.

---

### H3. `SnapshotValidator.validateClassStructureHashes()` reuses `BytecodeMismatchException`

**File:** `SnapshotValidator.java:84`

Class structure mismatches throw `BytecodeMismatchException`, but the issue is
field layout changes, not bytecode changes. The exception name is misleading.

**Recommendation:** Either rename the exception to something generic like
`SnapshotMismatchException`, or create a separate `ClassStructureMismatchException`.
The `changedMethods()` accessor name also doesn't make sense for class structure
mismatches.

---

### H4. `DurableTransformer` excluded class lists are fragile

**File:** `DurableTransformer.java:36-53`

The `EXCLUDED_CLASSES` array manually lists specific library classes. If a new
class is added to the package and not added to this list, it could be
instrumented and cause infinite recursion.

**Recommendation:** Replace the individual class exclusions with a package-level
check:
```java
if (className.startsWith("ai/jacc/durableThreads/")
    && !className.startsWith("ai/jacc/durableThreads/test/")) {
    // Check if it's in the internal/snapshot/exception subpackage (already excluded)
    // Otherwise exclude all library classes
}
```

Or use a marker annotation/interface to designate classes that should NOT be
instrumented. At minimum, add a comment noting that new classes must be added
to this list.

---

### H5. `ThreadFreezer.performFreeze()` sets UncaughtExceptionHandler on user's thread

**File:** `ThreadFreezer.java:176-193`

The code replaces the target thread's `UncaughtExceptionHandler` to silently
swallow `ThreadFrozenError`. While it saves and delegates to the previous
handler for non-freeze errors, this still modifies the user's thread state.

If the user has a custom handler that performs important cleanup (logging,
metrics, etc.), the replacement handler will only forward non-`ThreadFrozenError`
exceptions. This is correct behavior, but the handler replacement is permanent --
if the freeze somehow fails after the handler is replaced but before the thread
is terminated, the user's handler is lost.

**Recommendation:** Store and restore the original handler in a finally-like
pattern, or only set the handler immediately before the interrupt.

---

## MEDIUM -- Simplification Opportunities

### M1. `PrologueTypes.InvokeInfo` and `InvokeMarker` have identical fields

**File:** `PrologueTypes.java`

`InvokeInfo` (lines 20-61) and `InvokeMarker` (lines 64-109) both store:
`index, opcode, owner, name, descriptor, isInterface`. They have identical
`equals()`, `hashCode()`, and `toString()` implementations.

`InvokeInfo` is used in `PrologueEmitter`'s `invokeInfos` list.
`InvokeMarker` is used in the `bufferedOps` list (implements `Runnable`).

**Recommendation:** Make `InvokeMarker` extend or contain `InvokeInfo` to
eliminate field duplication. Or have `InvokeMarker` hold a reference to
`InvokeInfo`.

---

### M2. Duplicate raw bytecode parsing in `OperandStackChecker` and `RawBytecodeScanner`

**Files:** `OperandStackChecker.java:169-255` and `RawBytecodeScanner.java:76-170`

Both classes independently parse the class file binary format (constant pool,
fields, methods) to find a method's Code attribute. The code is nearly identical:
- `OperandStackChecker.findCodeOffset()`
- `RawBytecodeScanner.scanClassFileForMethodCode()`

Both also have duplicate `readU2`/`readU4`/`readI4`/`readS4` utility methods,
and near-identical `rawInsnSize`/`opcodeSize` methods.

**Recommendation:** Extract a shared `ClassFileParser` utility that provides:
- Method Code attribute location
- Raw instruction size computation
- Byte reading utilities

This would eliminate ~200 lines of duplicated low-level parsing code.

---

### M3. `HeapRestorer.populate()` calls `tryCreateBoxedPrimitive()` redundantly

**File:** `HeapRestorer.java:213-218`

```java
case REGULAR:
    if (tryCreateBoxedPrimitive(snap) == null) {
        populateRegularObject(obj, snap);
    }
    break;
```

`tryCreateBoxedPrimitive()` is called again during `populate()` just to check
if the object is a boxed primitive. It was already called during `allocate()`.
This re-parses the class name and field map.

**Recommendation:** Store a flag or use a Set of boxed-primitive snapshot IDs
during allocation to avoid the redundant check.

---

### M4. `JdiHelper.getListeningPorts*()` methods use `List.contains()` for dedup

**File:** `JdiHelper.java:199, 236, 276`

```java
if (port > 0 && !ports.contains(port)) {
    ports.add(port);
}
```

`ArrayList.contains()` is O(n). For small port lists this is fine, but a
`LinkedHashSet` would be cleaner.

**Recommendation:** Use `Set<Integer>` internally, convert to `List` on return.
Very minor.

---

### M5. `NullRef` could be a singleton

**File:** `NullRef.java`

Every null reference creates a new `NullRef()` instance. During heap walking,
this could create thousands of instances for null fields/elements.

**Recommendation:** Add a `static final NullRef INSTANCE = new NullRef()` and
use it throughout. Since `NullRef` implements `Serializable`, add a
`readResolve()` method to preserve singleton identity after deserialization.

---

### M6. `ObjenesisStd` instantiated multiple times

**Files:** `ReflectionHelpers.java:139`, `ReplayState.java:286`

Both create `new ObjenesisStd(true)` on each call. `HeapRestorer` correctly
uses a `static final` instance (line 19).

**Recommendation:** Use a shared static `ObjenesisStd` instance across all
classes, or at minimum make it static in `ReflectionHelpers` and `ReplayState`.

---

## LOW -- Polish & Enterprise Hardening

### L1. Logging uses `System.out.println` and `System.err.println`

**Files:** Multiple (HeapRestorer.java:96, JdiLocalSetter.java:218,
ThreadRestorer.java:318, etc.)

Enterprise applications typically use structured logging (SLF4J, Log4j2, etc.).
The library uses raw `System.out/err` for all diagnostics.

**Recommendation:** Since this is a library (not an application), using
`java.util.logging` (JUL) would add zero dependencies while providing:
- Log levels (FINE, WARNING, SEVERE)
- Configurable output
- Structured format

---

### L2. `FrameSnapshot.bytecodeHash` is a mutable `byte[]` exposed via getter

**File:** `FrameSnapshot.java:70`

```java
public byte[] bytecodeHash() {
    return bytecodeHash;
}
```

Callers can modify the returned array, breaking the immutability contract of
`FrameSnapshot`. Same issue in `ObjectSnapshot.classStructureHash()` and
`ObjectSnapshot.arrayElements()`.

**Recommendation:** Return defensive copies: `Arrays.copyOf(bytecodeHash, ...)`,
or document that the arrays must not be modified.

---

### L3. `ThreadSnapshot`, `FrameSnapshot`, `ObjectSnapshot` expose mutable `List` fields

**File:** `ThreadSnapshot.java:33`, `FrameSnapshot.java:74`

```java
public List<FrameSnapshot> frames() { return frames; }
```

The returned lists are mutable. Callers could accidentally modify them.

**Recommendation:** Wrap in `Collections.unmodifiableList()` in constructors
or getters.

---

### L4. `DurableTransformer.transform()` wraps exceptions in `RuntimeException`

**File:** `DurableTransformer.java:107-111`

```java
} catch (Exception e) {
    throw new RuntimeException(
        "[DurableThreads] Failed to instrument class " + ..., e);
}
```

`ClassFileTransformer.transform()` should return `null` on failure (skip
instrumentation) rather than throw, per the API contract. Throwing from
`transform()` can cause `ClassNotFoundException` for the class being loaded.

**Recommendation:** Log the error and return `null` instead of throwing. This
makes the library resilient to unexpected class file formats (e.g., very new
JDK classes, obfuscated code) without breaking the application.

---

### L5. `JdiHelper.connectAndVerifyNonce()` creates a new `ExecutorService` per probe

**File:** `JdiHelper.java:422-457`

Each port probe creates a new `SingleThreadExecutor`. During ephemeral port
scanning (up to 400 ports), this creates 400 thread pools.

**Recommendation:** Use a single shared executor for all probes within a
discovery session, or use `CompletableFuture` with a timeout.

---

### L6. Magic numbers in various places

- `ThreadRestorer.java:88`: UUID substring `(0, 8)` for thread name suffix
- `JdiHelper.java:329`: `PROBE_TIMEOUT_MS = 200`
- `JdiHelper.java:351`: `SCAN_RANGE = 200`
- `ThreadRestorer.java:377`: `Math.min(10, tr.frameCount())` frame inspection limit
- `ThreadFreezer.java:384`: `baseKey = -1_000_000` for named object bridge keys

These are all reasonable values but should be named constants with documentation.

---

### L7. `HeapRestorer.createEmptyCollection()` doesn't handle all common collections

**File:** `HeapRestorer.java:255-272`

Missing: `Vector`, `Stack`, `Hashtable`, `PriorityQueue`,
`CopyOnWriteArrayList`, `CopyOnWriteArraySet`, `EnumSet`, `EnumMap`.

The fallback is Objenesis, which may not properly initialize internal state
for these collections.

**Recommendation:** Add explicit support for commonly-used collection types,
or at minimum document which collection types are supported.

---

### L8. `captureLocals()` sets slot=0 for all non-this locals

**File:** `ThreadFreezer.java:459`

```java
result.add(new ai.jacc.durableThreads.snapshot.LocalVariable(
    0, // slot index unused -- restore matches by name, not slot
    jdiLocal.name(),
```

The slot is always 0 for non-this variables. The comment says "unused" but
`LocalVariable.slot()` is part of the public snapshot API. Users inspecting
snapshots would see incorrect slot values.

**Recommendation:** Pass the actual slot index from `jdiLocal.slot()` (available
via JDI) even if restore doesn't use it. This makes the snapshot data accurate
for debugging and tooling.

---

## Summary Table

| ID | Severity | Category | Description |
|----|----------|----------|-------------|
| C2 | Critical | Dead code | Unused parameters in `convertToJdiValue()` |
| C3 | Critical | Cleanup | `HeapObjectBridge.clear()` not in finally block |
| C5 | Critical | Performance | Aggressive JDI suspend/resume polling |
| H1 | High | Robustness | `ReplayState` static fields lack cleanup guarantees |
| H3 | High | API design | `BytecodeMismatchException` reused for class structure |
| H4 | High | Maintainability | Fragile excluded-class list in transformer |
| H5 | High | Correctness | UncaughtExceptionHandler replacement is permanent |
| M1 | Medium | Simplification | Duplicate fields in InvokeInfo/InvokeMarker |
| M2 | Medium | Simplification | Duplicate class file parsing in two scanners |
| M3 | Medium | Simplification | Redundant `tryCreateBoxedPrimitive()` call |
| M5 | Medium | Memory | NullRef should be singleton |
| M6 | Medium | Memory | ObjenesisStd created per-call |
| L1 | Low | Enterprise | Raw System.out/err instead of logging framework |
| L2 | Low | Immutability | Mutable byte[] exposed via getters |
| L3 | Low | Immutability | Mutable Lists exposed via getters |
| L4 | Low | Resilience | Transformer throws instead of returning null |
| L5 | Low | Performance | ExecutorService created per port probe |
| L7 | Low | Completeness | Missing collection types in HeapRestorer |
| L8 | Low | Data quality | Slot index always 0 for non-this locals |
