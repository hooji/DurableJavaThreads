# Durable Java Threads: Code Audit

## Summary

This audit reviews the codebase for reliability, simplicity, comprehensibility, potential bugs, and race conditions. Findings are organized by severity (Critical, High, Medium, Low) and by the area of the codebase they affect.

---

## Critical Issues

### C1. `ReplayState` Uses Static Fields for Per-Restore-Cycle State

**Files:** `ReplayState.java:30-44`

The `resumeLatch`, `localsLatch`, and `restoreError` fields are `static volatile` — shared across all threads in the JVM. While `FREEZE_LOCK` serializes freeze/restore operations, the latches are read/written by both the replay thread and JDI worker *outside* the `FREEZE_LOCK` scope (the replay thread runs concurrently with the JDI worker after being configured).

**Problem:** If two restore operations somehow overlap (e.g., `FREEZE_LOCK` is only held during `performFreeze`, not during the entire restore lifecycle), the second restore's `activateWithLatch()` overwrites the first restore's latches, causing the first replay thread to block forever or wake on the wrong latch.

**Current mitigation:** `FREEZE_LOCK` serialization. But the lock is in `ThreadFreezer`, and `ThreadRestorer` doesn't acquire it. Restores are not serialized — multiple `Durable.restore()` calls can run concurrently.

**Recommendation:** Either:
- (a) Move latch state into `ReplayData` (the `ThreadLocal` value) so each restore has its own latches, or
- (b) Add `FREEZE_LOCK` serialization to `ThreadRestorer.configureJdiRestore()`, or
- (c) Use a per-restore coordination object passed to both the replay thread and JDI worker.

Option (a) is the cleanest — it eliminates the shared mutable state entirely.

### C2. `FreezeFlag.isFrozen()` Consumes the Flag on Read

**File:** `ThreadFreezer.java:612-614`

```java
static boolean isFrozen(Thread t) {
    return frozenThreads.remove(t.getId());
}
```

`isFrozen()` uses `remove()`, so the first call returns `true` and all subsequent calls return `false`. This is called in three places in `ThreadFreezer.freeze()` (lines 83, 96, 114). If the first check at line 83 runs but doesn't throw (because `InterruptedException` wasn't the cause), the flag is consumed and the safety checks at lines 96 and 114 will miss it.

**Scenario:** Thread is frozen, `lock.notifyAll()` fires before `interrupt()`. Thread wakes without `InterruptedException`, falls through to line 96. `isFrozen()` at line 96 returns `true` and throws `ThreadFrozenError`. This works. But if line 83's `isFrozen()` ran first (impossible in current flow since line 83 is inside the catch block), it would consume the flag.

**Current risk:** Low in practice because the control flow prevents double-checking on the happy path. But refactoring could easily introduce a bug.

**Recommendation:** Change to `contains()` for checking, and only `remove()` when about to throw:
```java
static boolean isFrozen(Thread t) {
    return frozenThreads.contains(t.getId());
}
static void clearFrozen(Thread t) {
    frozenThreads.remove(t.getId());
}
```

### C3. Thread ID Reuse in `FreezeFlag`

**File:** `ThreadFreezer.java:606`

`frozenThreads` stores `Thread.getId()` values, but thread IDs can be reused after a thread terminates (especially with virtual threads in Java 21+). If a frozen thread's ID is never cleaned up (e.g., if `isFrozen()` isn't called), a new thread with the same ID could be falsely detected as frozen.

**Current mitigation:** `isFrozen()` uses `remove()`, so the ID is cleaned up on check. But if the frozen thread dies without anyone checking the flag (e.g., if the worker thread fails), the stale ID persists.

**Recommendation:** Use `WeakReference<Thread>` or an `IdentityHashMap<Thread, Boolean>` instead of thread IDs.

---

## High Issues

### H1. Duplicated ConcurrentHashMap Walking Logic

**Files:** `ThreadFreezer.java:470-505`, `ThreadRestorer.java:974-1028`

Both `registerNamedObjectsViaJdi()` and `getObjectFromBridgeArray()` manually walk ConcurrentHashMap internals via JDI (finding `table` field, iterating nodes, reading `key`/`val`/`next`). This logic is duplicated and fragile — it depends on ConcurrentHashMap's internal field names (`table`, `key`, `val`, `next`), which are implementation details that could change across JDK versions.

**Recommendation:** Extract into a shared utility method in `JdiHelper`, e.g., `JdiHelper.walkConcurrentHashMap(vm, mapRef, keyMatcher)`.

### H2. `localsReady()` Latch Recreation Race

**File:** `ReplayState.java:197-201`

After `latch.await()` returns, the replay thread recreates the latch under `LATCH_LOCK`:
```java
synchronized (LATCH_LOCK) {
    localsLatch = new CountDownLatch(1);
}
```

The JDI worker's `releaseLocalsReady()` also holds `LATCH_LOCK`. But there's a window between `await()` returning (line 193) and entering the `synchronized` block (line 199) where:
1. JDI worker could call `releaseLocalsReady()` for the *next* frame
2. But `localsLatch` still points to the *old* (already-counted-down) latch
3. The countdown is lost — the next `localsReady()` call blocks forever

**Current mitigation:** The JDI worker uses `isCorrectFrame()` polling and only releases the latch after confirming the thread is at the correct frame's `localsReady()`. This provides a timing buffer. But under extreme load or slow GC pauses, the race could still fire.

**Recommendation:** Have the JDI worker create the new latch (it knows when a frame is done), rather than having the replay thread recreate it. Or use a rendezvous pattern where both threads agree on the latch identity.

### H3. No Timeout on `resumePoint()` and `localsReady()` Awaits

**Files:** `ReplayState.java:131`, `ReplayState.java:193`

Both `resumePoint()` and `localsReady()` call `latch.await()` without a timeout. If the JDI worker fails silently (e.g., crashes without calling `signalRestoreError()`), the replay thread blocks forever.

**Current mitigation:** The JDI worker has a `catch` block that releases both latches on failure. But certain failures (e.g., `OutOfMemoryError`, JDI worker thread killed by OS) won't reach the catch block.

**Recommendation:** Add a timeout (e.g., 60 seconds) and throw a descriptive error on timeout.

### H4. `Durable.java` Massive API Surface with Duplicated Logic

**File:** `Durable.java` (464 lines, 16 public methods)

The restore methods with `startThread`/`waitForThreadToFinish` parameters duplicate the error-handling and thread-join logic in two places (lines 179-203 and 363-388). The file deserialization logic is also duplicated across `restore(Path)`, `restore(Path, boolean, boolean)`, `restore(Path, Map)`, and `restore(Path, Map, boolean, boolean)`.

**Recommendation:**
- Extract file deserialization into a private `loadSnapshot(Path)` method
- Extract the start-and-wait logic into a private `startAndWait(Thread, boolean, boolean)` method
- Consider a builder pattern or options object to replace the boolean parameter explosion

### H5. `HeapObjectBridge.clear()` Called at Wrong Time

**File:** `ThreadRestorer.java:93` and `ThreadRestorer.java:388`

`HeapObjectBridge.clear()` is called at line 93 before populating the bridge, and at line 388 after restore completes. But the bridge is populated at line 94-96, then the JDI worker reads from it during Phase 2 (which runs on a separate thread). If `clear()` at line 388 races with an ongoing JDI read, the JDI worker could fail to resolve a heap reference.

Line 388 is inside the JDI worker, so it runs after all frames are set. This is probably safe. But `clear()` at line 93 could affect a *previous* restore cycle's JDI worker if two restores overlap (see C1).

**Recommendation:** Scope the bridge contents per-restore-cycle rather than using a global static map.

---

## Medium Issues

### M1. Reflection-Based Slot Access in `captureLocals()`

**File:** `ThreadFreezer.java:588-592`

```java
java.lang.reflect.Method slotMethod = jdiLocal.getClass().getMethod("slot");
slot = (int) slotMethod.invoke(jdiLocal);
```

The slot index is obtained via reflection on JDI implementation classes. If this fails (which it silently catches), `slot` defaults to 0, meaning all locals would appear to be at slot 0. This corrupts the snapshot — the restore would set the wrong variables.

**Recommendation:** At minimum, log a warning when reflection fails. Better: use `jdiLocal.isArgument()` and parameter ordering to compute slots without reflection, or use a JDI version check to determine if `slot()` is available.

### M2. `drainPendingEvents()` Swallows All Exceptions

**File:** `ThreadFreezer.java:529-546`

The catch-all `catch (Exception e)` silently swallows errors during event draining. If `VMDisconnectedException` is thrown, the freeze will proceed with a dead JDI connection and fail later with a confusing error.

**Recommendation:** Re-throw `VMDisconnectedException` as a `RuntimeException` so the freeze fails fast with a clear message.

### M3. `blockForever()` Has a Busy-Spin Fallback

**File:** `ThreadFreezer.java:141-145`

```java
while (true) {
    Thread.yield();
}
```

If the sleep loop somehow exits (100 iterations of `Long.MAX_VALUE` sleeps), the thread enters an infinite busy spin. This would burn a CPU core indefinitely.

**Recommendation:** This code should truly never be reached. Replace the busy spin with `Thread.sleep(Long.MAX_VALUE)` in a tighter loop, or throw an error. The defensive intent is good, but a CPU-burning fallback is worse than a clear crash.

### M4. `waitForThreadAtMethod()` Polls at 10ms Intervals

**File:** `ThreadRestorer.java:436-437`

The JDI worker polls every 10ms for the replay thread to reach a specific method. This is a busy-wait pattern that:
- Wastes CPU during the poll loop
- Has inherent latency (up to 10ms per phase transition)
- Could miss transient states if the thread transitions quickly

**Recommendation:** Consider using JDI breakpoints or event requests instead of polling. Set a breakpoint at `resumePoint()` and `localsReady()`, wait for the event, then proceed. This would be both more efficient and more reliable.

### M5. `isAtMethod()` Does Suspension Inside a Polling Loop

**File:** `ThreadRestorer.java:481-509`

`isAtMethod()` suspends/resumes the thread for every poll iteration just to read its stack frames. Combined with the 10ms poll interval, this causes rapid suspend/resume cycles that could interfere with the thread's normal execution and JDI event processing.

**Recommendation:** If switching to breakpoint events (M4) isn't feasible, at least batch the status check — only suspend/read-frames when the thread status indicates WAIT/SLEEPING.

### M6. `setValueBypassTypeCheck()` Uses Deep JDI Internals

**File:** `ThreadRestorer.java:790-843`

This method accesses `com.sun.tools.jdi.JDWP$StackFrame$SetValues$SlotInfo` and `StackFrameImpl` internals via reflection. This is:
- Fragile across JDK versions
- Likely to break with module access restrictions (Java 16+)
- A maintenance burden

**Recommendation:** Document which JDK versions this works on. Add a test that exercises this path. Consider using `--add-opens` for the specific module if needed.

### M7. `DurableTransformer` Throws RuntimeException on Instrumentation Failure

**File:** `DurableTransformer.java:98-101`

A `ClassFileTransformer.transform()` that throws an exception prevents the class from loading. Any user class that triggers an ASM error (e.g., unusual bytecode patterns) would cause a `NoClassDefFoundError` at runtime.

**Recommendation:** Log the error and return `null` (skip instrumentation for that class) rather than throwing. The class won't be freezable, but it also won't prevent the application from running.

### M8. Snapshot Deserialization Uses Unchecked Java Serialization

**File:** `Durable.java:266-277`

```java
ThreadSnapshot snapshot = (ThreadSnapshot) ois.readObject();
```

Java's built-in serialization is a known security risk when deserializing untrusted data. If snapshots could come from untrusted sources, this is a deserialization vulnerability.

**Recommendation:** Document that snapshots should only be loaded from trusted sources. Consider adding a serialization filter (`ObjectInputFilter`) or migrating to a custom binary format.

---

## Low Issues

### L1. `emitIntConst()` Is Never Called

**File:** `PrologueInjector.java:1068-1078`

The `emitIntConst()` helper is defined but never used anywhere in the class. Dead code.

**Recommendation:** Remove it.

### L2. `emitLocalDefaults()` No-Arg Overload Is Never Called

**File:** `PrologueInjector.java:833-835`

The no-argument `emitLocalDefaults()` (fallback version) is defined but only the `emitLocalDefaults(int invokeIndex)` overload is called. Dead code.

**Recommendation:** Remove it.

### L3. `HeapWalker.java` vs `JdiHeapWalker.java` Unclear Relationship

**Files:** `internal/HeapWalker.java`, `internal/JdiHeapWalker.java`

`HeapWalker` does basic object graph capture from live Java objects. `JdiHeapWalker` does JDI-based capture. They don't share an interface or inherit from each other, but serve similar purposes. The naming doesn't clearly convey which is used when.

**Recommendation:** Consider renaming `HeapWalker` to something like `ReflectiveHeapWalker` or merging its functionality into `HeapRestorer` if it's only used during restore.

### L4. `LocalEntry` Record-Like Class with Manual equals/hashCode

**File:** `ThreadRestorer.java:20-55`

`LocalEntry` is a simple data carrier with manually written `equals()`, `hashCode()`, and `toString()`. Since the project targets Java 8, records aren't available, but this is boilerplate that could be reduced.

**Recommendation:** If/when upgrading to Java 16+, convert to a record.

### L5. Mixed Import Styles

Multiple files use both wildcard imports (`import com.sun.jdi.*;`) and specific imports. Some files use fully-qualified class names inline (e.g., `java.util.Map<String, Integer>` inside method bodies) rather than imports.

**Recommendation:** Standardize import style across the codebase. Use specific imports for clarity.

### L6. `InvokeInfo`, `InvokeMarker`, `InvokeDynamicMarker`, `LocalVarInfo` Are Record-Like

**File:** `PrologueInjector.java:1198-1297`

Four data classes with manual boilerplate for `equals()`, `hashCode()`, and `toString()`. Consider records (Java 16+) or Lombok.

### L7. `Version.java` Is Hardcoded

**File:** `Version.java`

```java
public static final String VERSION = "1.1.0";
```

This needs manual updating on every release. Consider generating it from `pom.xml` via Maven resource filtering.

---

## Refactoring Opportunities

### R1. Extract Shared JDI Utilities

`ThreadFreezer` and `ThreadRestorer` both contain:
- Infrastructure frame detection logic (duplicated exclusion prefix lists)
- ConcurrentHashMap walking via JDI
- `findField`/`findFieldInType` methods
- Double-suspend/resume patterns

These should live in `JdiHelper` or a new shared utility class.

### R2. Consolidate Frame Filtering Logic

`ThreadFreezer.isInfrastructureFrame()` (lines 272-280) and the inline checks in `ThreadRestorer` (lines 555-558, 607-610, 460-463) use slightly different exclusion lists:
- `ThreadFreezer` uses `EXCLUDED_FRAME_PREFIXES` + `EXCLUDED_FRAME_CLASSES`
- `ThreadRestorer` uses hardcoded `ai/jacc/durableThreads/ReplayState`, `java/`, `jdk/`, `sun/`
- Missing from `ThreadRestorer`: `javax/`, `com/sun/`

This inconsistency could cause bugs where frames are captured but not properly matched during restore.

**Recommendation:** Single `isInfrastructureFrame()` method in a shared location.

### R3. Replace ConcurrentHashMap JDI Walking with `invokeMethod`

Instead of manually walking ConcurrentHashMap's internal `table` array via JDI field access, call `map.get(key)` via `ObjectReference.invokeMethod()`. This is:
- Simpler (one JDI call instead of a loop)
- More robust (doesn't depend on HashMap internals)
- Slightly slower (invokeMethod requires thread suspension)

The current approach is presumably for performance, but the correctness risk seems higher than the performance benefit.

### R4. Make `ReplayState` Instance-Based

Currently, `ReplayState` uses a mix of `ThreadLocal` (for replay data) and static fields (for latches and errors). This makes reasoning about concurrency difficult and prevents concurrent restores.

Refactoring to an instance-based coordination object that's passed to both the replay thread and JDI worker would:
- Eliminate shared mutable state
- Enable concurrent restore operations
- Make the data flow explicit

### R5. Simplify `Durable.java` API Surface

The 16 public methods can be reduced by:
1. Private `loadSnapshot(Path)` for deserialization
2. Private `startAndWait(Thread, Throwable[], boolean, boolean)` for the start/join pattern
3. Builder or options class: `Durable.restore(snapshot).withReplacements(map).startAndJoin()`

---

## Testing Gaps

1. **No test for concurrent restores** — The static latch state in `ReplayState` would fail under concurrent restore, but there's no test exercising this.
2. **No test for freeze after restore** (re-freeze cycle) — `STUB_OFFSETS` handling suggests this is supported, but coverage is unclear.
3. **No test for `blockForever()` path** — The safety-net code after all normal termination paths.
4. **No test for `setValueBypassTypeCheck()`** — The Java 8 JDI workaround has no dedicated test.
5. **No test for JDWP port scanning fallback** — Platform-specific port detection is only tested implicitly.

---

## Summary of Recommendations by Priority

| Priority | Item | Effort |
|----------|------|--------|
| Critical | C1: Per-restore latch isolation | Medium |
| Critical | C2: Non-destructive FreezeFlag check | Low |
| Critical | C3: Thread ID reuse safety | Low |
| High | H1: Deduplicate CHM walking | Medium |
| High | H2: Latch recreation race | Medium |
| High | H3: Add await timeouts | Low |
| High | H4: Deduplicate Durable.java logic | Low |
| High | H5: Per-cycle HeapObjectBridge | Medium |
| Medium | M4: JDI breakpoints vs polling | High |
| Medium | M7: Return null on transform failure | Low |
| Medium | R2: Consolidate frame filtering | Low |
| Medium | R1: Extract shared JDI utilities | Medium |
| Low | L1-L2: Remove dead code | Trivial |
| Low | R5: Simplify API surface | Medium |
