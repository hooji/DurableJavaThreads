# Durable Java Threads — Code Audit Report

**Date:** 2026-03-28
**Scope:** Full codebase review for reliability, correctness, race conditions, and code quality.
**Codebase Version:** 1.3.1

---

## Executive Summary

The codebase implements a sophisticated thread freeze/restore mechanism using JDI self-attach and ASM bytecode instrumentation. The core design is sound, and significant effort has gone into handling edge cases (lambda bridges, double-suspend patterns, operand stack simulation, etc.).

However, the audit identified **several categories of issues** ranging from potential race conditions to dead code, architectural concerns, and areas where error handling could silently mask problems. The most critical findings relate to shared mutable state in `ReplayState`, the complexity of `PrologueInjector`, and inconsistencies between the two heap walker implementations.

---

## Critical Issues

### C1. ReplayState Uses Static Fields for Per-Restore-Operation State

**Files:** `ReplayState.java:56-70`, `ReplayState.java:84`
**Severity:** Critical (race condition in concurrent restore scenarios)

The `resumeLatch`, `localsLatch`, `goLatch`, and `restoreError` fields are `static volatile` — shared across ALL threads. While `Durable.freeze()` and `Durable.restore()` are serialized via `synchronized(Durable.class)`, the restore operation releases the `Durable.class` monitor **before** the restored thread actually finishes running. Specifically, `ThreadRestorer.restore()` returns a `RestoredThread` handle, and the caller may invoke `resume()` at an arbitrary later time.

If a second `Durable.restore()` call happens while the first restored thread is still running (blocked on the go-latch or executing user code), the static latches from the first restore are overwritten by `activateWithLatch()` for the second restore. The first restored thread could then:
- Block forever (its go-latch was replaced)
- Wake on the wrong latch
- Read a `restoreError` meant for the second restore

**Recommendation:** Make all per-restore-operation state instance-scoped. Create a `RestoreSession` object that holds the latches, error signal, and replay data. Pass it through the restore pipeline instead of using static fields.

### C2. FreezeFlag Uses Thread ID Which Can Be Reused

**File:** `ThreadFreezer.java:575-600`
**Severity:** Medium-High

`FreezeFlag` stores frozen thread IDs in a `Set<Long>`. Thread IDs can be reused by the JVM after a thread terminates. If `clearFrozen()` is not called before thread termination (which could happen if `ThreadFrozenError` is caught by user code), the stale ID could match a new thread, causing it to falsely appear frozen.

While `ThreadFrozenError` extends `Error` (making it unlikely to be caught), a catch-all `catch (Throwable t)` in user code would intercept it.

**Recommendation:**
- Use `WeakReference<Thread>` or `IdentityHashMap<Thread, Boolean>` instead of thread IDs.
- Add a note to the `ThreadFrozenError` javadoc explicitly warning against catching it.

### C3. Snapshot Handler Called While Thread is Suspended (Deadlock Risk)

**File:** `ThreadFreezer.java:199`
**Severity:** Medium-High

In `performFreeze()`, the user's snapshot handler is called while the target thread is still JDI-suspended (inside the retry loop). If the handler acquires any lock that the frozen thread holds, this creates a deadlock. The handler runs on Thread B (the worker), not the frozen thread, but the frozen thread is suspended mid-execution and may hold arbitrary locks (monitors, ReentrantLocks, etc.).

**Recommendation:** Document this constraint prominently in the `Durable.freeze()` javadoc. Consider adding an option to call the handler *after* the thread is resumed but before it's terminated (though this narrows the window).

### C4. Double-Suspend/Resume Without Try-Finally Guarantee

**File:** `ThreadFreezer.java:193-219`
**Severity:** Medium

The double-suspend is inside a try block, and the double-resume is in the `finally`. However, if the first `threadRef.suspend()` succeeds but the second throws (e.g., `VMDisconnectedException`), the finally block calls `resume()` twice on a thread with suspend count 1, which would cause an `IllegalThreadStateException` or leave the thread in an inconsistent state.

Similarly in `ThreadRestorer.java:429-436` — double-suspend with double-resume, same issue.

**Recommendation:** Track the actual suspend count and only resume the exact number of times suspended. Wrap each suspend in its own try-catch.

---

## Significant Issues

### S1. HeapWalker (Reflection-Based) Is Unused

**File:** `internal/HeapWalker.java`
**Severity:** Medium (dead code, maintenance burden)

`HeapWalker` operates on live Java objects via reflection, while `JdiHeapWalker` operates via JDI mirrors. Only `JdiHeapWalker` is used in the actual freeze path. `HeapWalker` appears to be an earlier implementation that was superseded but never removed.

The two implementations have **divergent behavior**:
- `HeapWalker` doesn't handle collections, enums, immutables, or opaque types.
- `HeapWalker` doesn't compute class structure hashes.
- `HeapWalker` doesn't support named objects.

**Recommendation:** Remove `HeapWalker` if it's truly unused (check test code). If it serves as a testing utility, clearly mark it as such and bring it into parity with `JdiHeapWalker`, or replace test usages.

### S2. Stale Multi-Phase Infrastructure in ReplayState

**File:** `ReplayState.java`
**Severity:** Medium (complexity, confusion)

The current architecture uses a single-pass restore (direct-jump to BEFORE_INVOKE), but `ReplayState` still contains infrastructure for the older multi-phase protocol:
- `resumeLatch` / `releaseResumePoint()` / `resumePoint()`
- `localsLatch` / `localsReady()` / `releaseLocalsReady()` / `armLocalsAwait()`
- The `localsReady()` method with its arm/disarm gate pattern

Similarly, `ThreadRestorer` has `setLocalsForSingleFrame()` (line 630) which appears to be from the per-frame phase-2 approach and is now dead code.

This dead infrastructure makes the code significantly harder to understand and increases the risk of bugs if someone modifies it thinking it's active.

**Recommendation:** Remove all multi-phase latch infrastructure that is no longer used. The single-pass architecture only needs the go-latch.

### S3. STUB_OFFSETS in InvokeRegistry Are Never Populated

**File:** `InvokeRegistry.java:27`, `InvokeRegistry.java:52-54`
**Severity:** Medium

`STUB_OFFSETS` is declared and has `registerStubOffsets()` / `getInvokeIndex()` fallback logic, but `registerStubOffsets()` is never called anywhere in the codebase. The `getInvokeIndex()` method checks `STUB_OFFSETS` as a fallback but will always get `null`.

This appears to be infrastructure for re-freezing a restored thread (where the BCP might point into a resume stub), but it was never completed.

**Recommendation:** Either implement the stub offset registration or remove the dead code. If re-freeze scenarios need this, add tests for the path.

### S4. SnapshotFileWriter Has Duplicate Javadoc Block

**File:** `SnapshotFileWriter.java:27-37`
**Severity:** Low (cosmetic)

The `SnapshotFileWriter(String)` constructor has two consecutive javadoc blocks. The first one (lines 27-29) is the original, and the second (lines 31-37) is the replacement that adds the null-handling note. The first block should be removed.

### S5. populateArray Has Dead Branch

**File:** `HeapRestorer.java:274-287`
**Severity:** Low (code smell)

```java
if (componentType.isPrimitive()) {
    Array.set(array, i, value);
} else {
    Array.set(array, i, value);
}
```

Both branches do the same thing. This is likely left over from a refactor where primitive arrays had special handling.

**Recommendation:** Remove the dead branch; collapse to a single `Array.set(array, i, value)`.

### S6. Version Class Uses Mutable Public Static Field

**File:** `Version.java`
**Severity:** Low

`public static String version = "v1.3.1"` — this is a mutable public field. It should be `public static final String VERSION = "v1.3.1"` for correctness and convention.

---

## Race Conditions and Concurrency Issues

### R1. ReplayState.resumePoint() Reads Latch Without Lock

**File:** `ReplayState.java:168-169`

```java
public static void resumePoint() {
    CountDownLatch latch = resumeLatch;
```

This reads `resumeLatch` without holding `LATCH_LOCK`, while `activateWithLatch()` (line 311) writes it under `LATCH_LOCK`. The field is `volatile` so the read is atomic, but there's a TOCTOU window: `activateWithLatch()` could overwrite the latch between the read and the `await()` call.

In practice this is mitigated by the `synchronized(Durable.class)` in `Durable.restore()`, but it's fragile and depends on external synchronization.

### R2. restoreError Is a Single Static Field

**File:** `ReplayState.java:84`

`restoreError` is shared across all restore operations. If two restores overlap (possible since the monitor is released before resume), one restore's error could be read by the other's replay thread.

### R3. FreezeFlag.frozenThreads Concurrent Access Pattern

**File:** `ThreadFreezer.java:576`

`frozenThreads` is a `Collections.synchronizedSet(new HashSet<>())`. While individual operations are atomic, the pattern `markFrozen(t); t.interrupt()` in `performFreeze()` and `isFrozen(t); clearFrozen(t); throw` in the caller has a potential gap: the interrupt could arrive before `markFrozen` completes from the interrupted thread's perspective (since `markFrozen` is on the worker thread and `interrupt` is the happens-before trigger).

However, `volatile` semantics of the synchronized set combined with `interrupt()` being a happens-before edge should make this safe. Worth documenting the relies-on relationship.

### R4. HeapObjectBridge.clear() Called During Restore While Bridge May Be In Use

**File:** `ThreadRestorer.java:439`

`HeapObjectBridge.clear()` is called at the end of `runJdiRestore()`, but the replay thread (which may resolve objects via the bridge during user code execution after `resume()`) could still be running.

---

## Potential Bugs

### B1. Thread Name Collision in JdiHelper.findThread()

**File:** `JdiHelper.java:548-556`

Thread matching is done by name. If two threads have the same name (which is allowed in Java), the wrong thread could be frozen/restored. This could cause:
- Freezing a thread that didn't call `freeze()`, producing a garbage snapshot.
- Setting locals in the wrong thread during restore.

**Recommendation:** Add the thread ID to the matching criteria, or use a more robust identification strategy. At minimum, validate that the found thread's stack actually contains a call to `freeze()`.

### B2. captureLocals Uses Reflection to Get Slot Index

**File:** `ThreadFreezer.java:558-563`

```java
try {
    java.lang.reflect.Method slotMethod = jdiLocal.getClass().getMethod("slot");
    slot = (int) slotMethod.invoke(jdiLocal);
} catch (Exception ignored) {}
```

If the reflection fails (e.g., on a JDI implementation without a public `slot()` method), `slot` defaults to 0 for ALL non-`this` local variables. This would cause all locals to appear to be at slot 0 during restore, and the name-based matching in `setFrameLocals()` would mask the issue, but the slot information in the snapshot would be incorrect.

**Recommendation:** Log a warning if slot reflection fails. Consider using the `LocalVariable.slot()` method that was added in a later JDI version, or match locals by name only and remove the slot field.

### B3. drainPendingEvents Discards Events Without Resume

**File:** `ThreadFreezer.java:499-516`

The comment says "Discard without calling eventSet.resume() — we don't want to trigger any resumes." However, JDI event sets that aren't resumed contribute to the suspend count. Draining events without resuming them can incrementally increase the VM's suspend count, potentially causing all threads to hang in multi-cycle scenarios.

**Recommendation:** Track whether events are actually being discarded and what their type is. Consider whether `eventSet.resume()` should be called for non-thread-specific events.

### B4. LinkedList Capture Missing in JdiHeapWalker

**File:** `JdiHeapWalker.java:843-875`

`captureListOrSet` looks for `size` and `elementData` fields (ArrayList structure). If those aren't found, it falls back to looking for a `map` field (HashSet structure). For `LinkedList`, neither pattern matches — it has `size`, `first`, and `last` fields, but no `elementData`. The `size` check succeeds, but `elementDataField` is null, so it falls through to the `map` fallback, which also fails, resulting in an empty collection capture.

Similarly, `TreeSet` wraps a `NavigableMap` field named `m`, not `map`, so it would also produce empty results.

`ArrayDeque` has `elements` (not `elementData`) and `head`/`tail` fields.

**Recommendation:** Add specific capture logic for LinkedList (walk first/next chain), TreeSet (read `m` field), and ArrayDeque (read circular buffer). Or, reconsider the collection capture approach — it's fragile and depends on JDK internal field names which can change between versions.

### B5. Lambda Bridge Proxy Ignores Return Value Type Mismatch

**File:** `ThreadRestorer.java:318-346`

The dynamic proxy invocation handler invokes the target method and returns its result. During replay, the args are dummy values and the method's prologue immediately takes over, so the return value is irrelevant. However, if the proxy method's return type doesn't match the target method's return type, `Proxy.newProxyInstance` could throw at proxy creation time.

---

## Code Quality and Maintainability

### Q1. PrologueInjector Is Too Large (~1300 lines)

**File:** `PrologueInjector.java`

This class handles:
- Bytecode buffering
- Operand stack simulation
- Per-invoke scope map building
- Prologue emission
- Resume stub emission
- Local variable scope management
- Original code emission with labels
- Boxing/unboxing code generation
- 6 inner classes (marker types)

**Recommendation:** Extract the operand stack simulator into its own class. Extract the code emitter (prologue + stubs + original code emission) into a separate class. The buffering and simulation pass should produce a clean intermediate representation consumed by the emitter.

### Q2. Inconsistent Collection Handling Between JdiHeapWalker and HeapRestorer

The `JdiHeapWalker` captures collections by walking JDK internal data structures (bucket arrays, node chains). The `HeapRestorer` restores them by creating empty collections and calling `add()`/`put()`. This means:
- Capture depends on JDK internal field names (fragile across versions).
- Restore creates new collections with default settings (initial capacity, load factor, comparator are lost).
- `TreeMap`/`TreeSet` comparators are not preserved.
- Insertion order for `LinkedHashMap` may differ from iteration order of the original.
- `ConcurrentHashMap` concurrency level is not preserved.

### Q3. Exception Handling Swallows Context in Several Places

Multiple places catch exceptions with `catch (Exception ignored)` or `catch (Throwable ignored)`:
- `DurableAgent.eagerlyDetectJdwpPort()` — catches `Throwable ignored`
- `ThreadFreezer.autoNameThis()` — catches `Exception ignored`
- `ThreadFreezer.captureLocals()` — catches `Exception ignored` for slot reflection
- `JdiHelper.detectPortFromArguments()` — catches `Throwable t` from ManagementFactory
- Multiple places in `JdiHelper` port scanning

While some of these are intentional (non-critical failures with fallbacks), they make debugging difficult. At minimum, log at `FINE`/`DEBUG` level.

### Q4. sun.misc.Unsafe Usage for Dummy Instances

**File:** `ReplayState.java:448-456`

`dummyInstance()` uses `sun.misc.Unsafe.allocateInstance()` to create uninitialized instances. This is:
- Not portable (internal API, may be removed).
- Redundant with Objenesis (which is already a dependency).

**Recommendation:** Replace with Objenesis, which handles the same use case portably.

### Q5. Magic Numbers and Timeouts

Several hardcoded timeouts and constants:
- `ThreadFreezer.freeze()`: 30-second timeout on `lock.wait(30_000)` (line 84)
- `ReplayState.LATCH_TIMEOUT_SECONDS`: 300 seconds (5 minutes)
- `JdiHelper.PROBE_TIMEOUT_MS`: 200ms
- `JdiHelper.SCAN_RANGE`: 200 ports
- `ThreadFreezer.performFreeze()`: 10 retries, exponential backoff 50-800ms
- `ThreadRestorer.waitForThreadAtMethod()`: 30,000ms timeout
- `ThreadRestorer.isAtMethod()`: checks top 10 frames
- Various poll intervals: 10ms, 1ms

These should be gathered into a configuration class or at minimum use named constants with documentation.

### Q6. Thread Name Accumulation Potential

**File:** `ThreadRestorer.java:106-112`

Thread names are managed by stripping `-restored-*` suffixes and adding a new one. This works for single-level restore, but the regex-free `indexOf`-based stripping only handles one `-restored-` occurrence. Multiple rapid restores within the same JVM could still accumulate long names.

---

## Security Considerations

### SEC1. setValueBypassTypeCheck Uses Deep Reflection on JDI Internals

**File:** `ThreadRestorer.java:842-895`

This method bypasses JDI's type safety checks by using reflection to access internal JDWP protocol methods. While necessary for the Java 8 ClassNotLoadedException workaround, it:
- Depends on undocumented internal class structure (`com.sun.tools.jdi.JDWP$StackFrame$SetValues$SlotInfo`).
- Will break silently if JDI internals change.
- May be blocked by Java module restrictions in future JDK versions.

### SEC2. HeapObjectBridge Is a Static Public Map

**File:** `HeapObjectBridge.java`

`HeapObjectBridge.objects` is a `public static final` ConcurrentHashMap. Any code in the JVM can read or modify restored objects during the restore window. In a multi-tenant or plugin environment, this could be exploited.

---

## Testing Gaps

1. **No unit tests for JdiHeapWalker collection capture** — the correctness of LinkedList, TreeSet, ArrayDeque, and TreeMap capture paths is not verified.
2. **No tests for concurrent restore** — all tests use sequential freeze/restore.
3. **No tests for thread name collision** — what happens when two threads share a name.
4. **No tests for the `blockForever()` safety net** — hard to test by nature, but the logic should be verified.
5. **No tests for the `setValueBypassTypeCheck` path** — only exercised on Java 8.
6. **No tests for re-freezing a restored thread** — the `STUB_OFFSETS` fallback path.

---

## Recommended Priority Order for Fixes

1. **C1** — ReplayState static shared state (correctness risk)
2. **S2** — Remove stale multi-phase infrastructure (reduces complexity for all other fixes)
3. **S1** — Remove or clearly isolate unused `HeapWalker`
4. **B1** — Thread name collision risk in findThread
5. **S3** — Remove dead STUB_OFFSETS code
6. **B4** — Fix LinkedList/TreeSet/ArrayDeque collection capture
7. **C2** — FreezeFlag thread ID reuse
8. **Q1** — Decompose PrologueInjector
9. **C4** — Double-suspend/resume robustness
10. **Q4** — Replace Unsafe with Objenesis in ReplayState
11. **Q5** — Centralize timeouts and magic numbers
12. **S4-S6** — Minor cleanups (duplicate javadoc, dead branch, Version field)
