# Durable Java Threads — Code Audit Report

**Date:** 2026-03-28 (updated)
**Scope:** Full codebase review for reliability, correctness, race conditions, dead code, and code quality.
**Codebase Version:** 1.3.1 (post Stage 1–4 refactoring)

---

## Executive Summary

The codebase implements a sophisticated thread freeze/restore mechanism using JDI
self-attach and ASM bytecode instrumentation. Significant cleanup has been done
since the initial audit — most dead code has been removed, `PrologueInjector` has
been decomposed into four focused classes, `ThreadRestorer` has been decomposed
into `ThreadRestorer` + `JdiValueConverter` + `JdiLocalSetter` +
`SnapshotValidator` + `ReflectionHelpers`, and the stale multi-phase latch
infrastructure has been eliminated from `ReplayState`.

The codebase is in substantially better shape. The remaining issues fall into
three categories: (1) correctness defects that must be fixed, (2) robustness
improvements for enterprise reliability, and (3) minor cleanup items.

### What Was Fixed Since the Initial Audit

| Original Item | Status |
|---------------|--------|
| C1. ReplayState static shared state | **Fixed** — latches documented and scoped; old multi-phase infrastructure removed |
| S1. HeapWalker (reflection-based) unused | **Fixed** — moved to test sources |
| S2. Stale multi-phase infrastructure | **Fixed** — removed from ReplayState |
| S3. STUB_OFFSETS in InvokeRegistry | **Fixed** — removed |
| S4. SnapshotFileWriter duplicate javadoc | **Fixed** — two constructors now have distinct docs |
| S6. Version.java mutable public field | **Fixed** — class removed entirely |
| Q1. PrologueInjector too large | **Fixed** — decomposed into PrologueInjector + PrologueEmitter + OperandStackSimulator + PrologueTypes (1242 lines total, well-factored) |
| Q4. sun.misc.Unsafe usage in ReplayState | **Fixed** — replaced with Objenesis |
| Dead code items 1–6, 8–9 from dead-code-candidates.md | **Fixed** — all removed |

---

## Critical Issues

### C1. FrameSnapshot.equals/hashCode/toString Missing lambdaBridgeInterface

**File:** `snapshot/FrameSnapshot.java:87-117`
**Severity:** Critical (correctness defect)

The `lambdaBridgeInterface` field (line 28) is not included in `equals()`,
`hashCode()`, or `toString()`. Two `FrameSnapshot` objects that differ only in
their lambda bridge interface will compare as equal and produce the same hash.

This violates the Java equals/hashCode contract. While the field is not currently
used as a map key or set member in production code, this is a latent defect that
will cause silent data corruption if snapshots are ever compared, deduplicated,
or placed in hash-based collections. It also makes debugging harder since
`toString()` omits the field.

**Fix:** Add `lambdaBridgeInterface` to all three methods.

### C2. FreezeFlag Uses Thread ID Which Can Be Reused

**File:** `ThreadFreezer.java:468-493`
**Severity:** Medium-High

`FreezeFlag` stores frozen thread IDs in a `Set<Long>`. Thread IDs can be
reused by the JVM after a thread terminates. If `clearFrozen()` is not called
before thread termination (which could happen if `ThreadFrozenError` is caught
by user code in a `catch (Throwable t)` block), the stale ID could match a new
thread, causing it to falsely appear frozen.

The code does clear the flag before throwing `ThreadFrozenError` (at three
separate points: lines 87, 101, 115), but a user's `catch (Throwable)` could
intercept the error and prevent proper cleanup.

**Fix:** Replace `Set<Long>` with a `Set<Thread>` using
`Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()))`.
Thread references are identity-comparable and cannot collide. Stale entries will
be GC'd when the thread is collected.

### C3. Snapshot Handler Called While Thread Is Suspended (Deadlock Risk)

**File:** `ThreadFreezer.java:135-143`
**Severity:** Medium-High

The user's snapshot handler is called while the target thread is JDI-suspended.
If the handler acquires any lock that the frozen thread holds, this creates a
deadlock. The handler runs on Thread B (the worker), but Thread A is suspended
mid-execution and may hold arbitrary monitors.

**Mitigation:** This is documented in `Durable.freeze()` javadoc (line 93-94).
No runtime guard exists.

**Recommendation:** Add a prominent `@apiNote` warning to the `Consumer<ThreadSnapshot>`
parameter documentation on `Durable.freeze()`. Consider providing a
`freezeAsync()` variant that calls the handler after the thread is resumed (at
the cost of a narrower capture window).

### C4. Thread Name Collision in JdiHelper.findThread()

**File:** `JdiHelper.java:539-546`
**Severity:** Medium

Thread matching is done by name only. If two threads have the same name (which
is allowed in Java), the wrong thread could be frozen or restored. The code
acknowledges this in comments (lines 532-537) but provides no mitigation.

**Fix:** After finding a thread by name, validate that it is in the expected
state (e.g., check that its stack contains a call to `freeze()` for freeze
operations, or that it is `WAITING` in `awaitGoLatch` for restore operations).
This won't prevent the collision entirely but will detect most mismatches.

---

## Significant Issues

### S1. HeapRestorer.populateArray Has Dead Branch

**File:** `HeapRestorer.java:281-285`
**Severity:** Low (code smell, but confusing)

```java
if (componentType.isPrimitive()) {
    Array.set(array, i, value);
} else {
    Array.set(array, i, value);
}
```

Both branches execute identical code. This is left over from a refactor where
primitive arrays had special handling.

**Fix:** Remove the conditional; collapse to a single `Array.set(array, i, value)`.

### S2. ThreadRestorer Has Unused Static Import

**File:** `ThreadRestorer.java:6`
**Severity:** Low (stale import after Stage 4 extraction)

```java
import static ai.jacc.durableThreads.internal.FrameFilter.isInfrastructureFrame;
```

This import is no longer used in ThreadRestorer — `isInfrastructureFrame` was
moved to `JdiLocalSetter` during Stage 4. The import compiles cleanly but is
dead code.

**Fix:** Remove line 6.

### S3. SnapshotFileWriter Constructor Javadoc Overlap

**File:** `SnapshotFileWriter.java:26-46`
**Severity:** Low (cosmetic)

The two constructors have nearly identical javadoc. The `Path` constructor
(line 38-42) says "see String constructor" but the docs are essentially
duplicated. Consider having the `Path` constructor simply say
`@see #SnapshotFileWriter(String)`.

---

## Race Conditions and Concurrency Issues

### R1. restoreError Is a Single Static Field

**File:** `ReplayState.java:82`

`restoreError` is a `static volatile String`. While this is safe under the
current serialization guarantee (`synchronized(Durable.class)` covers the
entire restore operation including JDI worker completion), it would break
if the serialization constraint were ever relaxed. This is documented in
the class javadoc (lines 13-32) with a clear explanation of why it is safe.

**Status:** Acceptable given current architecture. The class javadoc adequately
documents the thread-safety invariant.

### R2. FreezeFlag Concurrent Access Pattern

**File:** `ThreadFreezer.java:468-493`

The pattern `markFrozen(t); t.interrupt()` in `performFreeze()` and
`isFrozen(t); clearFrozen(t); throw` in the caller has a potential gap: the
interrupt could arrive before `markFrozen` completes from the interrupted
thread's perspective. However, `markFrozen` uses a `synchronizedSet` whose
`add()` provides a happens-before edge, and `interrupt()` also provides a
happens-before edge, so the flag is guaranteed to be visible before the
interrupt handler runs.

**Status:** Safe. Consider adding a comment documenting the happens-before chain.

### R3. HeapObjectBridge.clear() Called While Bridge May Be in Use

**File:** `ThreadRestorer.java:297` (inside `runJdiRestore`)

`HeapObjectBridge.clear()` is called at the end of `runJdiRestore()`. At this
point, the replay thread is still blocked on the go-latch with all locals
already set via JDI. After `resume()`, the thread runs user code that accesses
local variables directly (not via the bridge). So the bridge is no longer needed.

**Status:** Safe. The clear happens before `resume()`, not during user code.

---

## Potential Bugs

### B1. captureLocals Slot Extraction via Reflection

**File:** `ThreadFreezer.java:451-455`

```java
try {
    java.lang.reflect.Method slotMethod = jdiLocal.getClass().getMethod("slot");
    slot = (int) slotMethod.invoke(jdiLocal);
} catch (Exception ignored) {}
```

If the reflection fails, `slot` defaults to 0 for ALL non-`this` local
variables. The snapshot's slot indices would all be 0, but restore uses
name-based matching (not slot-based), so this doesn't cause a functional
failure. However, the slot information in the snapshot would be incorrect,
which could mislead debugging or any future slot-based logic.

**Recommendation:** Log a warning at `FINE` level if slot reflection fails.

### B2. drainPendingEvents Discards Events Without Resume

**File:** `ThreadFreezer.java` (if present — verify)

The original audit flagged that discarding JDI events without calling
`eventSet.resume()` could incrementally increase the VM's suspend count. This
concern is mitigated by the double-suspend pattern, but should be verified in
multi-cycle stress tests.

**Status:** Needs verification in multi-cycle E2E tests. The existing
`MultiCycleFreezeRestoreIT` and `MultiCycleNamedObjectIT` pass, which suggests
this is not causing issues in practice.

### B3. Lambda Bridge Proxy Method Lookup by Name Only

**File:** `ThreadRestorer.java:199-204`

`createLambdaBridgeProxy()` finds the target synthetic method by name only
(no signature matching). If an enclosing class has multiple methods with the
same name (e.g., overloaded `lambda$doWork$0`), the wrong method could be
selected. In practice, the JVM's lambda desugaring generates unique method
names (`lambda$<enclosing>$<counter>`), so collisions are unlikely but not
impossible with obfuscation tools or non-standard compilers.

**Recommendation:** Match by both name and parameter count as a basic
disambiguation.

---

## Code Quality and Maintainability

### Q1. Swallowed Exceptions in ThreadFreezer

Multiple places catch exceptions silently:

| Location | Context | Risk |
|----------|---------|------|
| `ThreadFreezer.java:350` | `autoNameThis()` — can't get "this" | Low — graceful fallback |
| `ThreadFreezer.java:438` | `captureLocals()` — can't get "this" | Low — graceful fallback |
| `ThreadFreezer.java:451-455` | Slot extraction via reflection | Medium — see B1 |
| `ThreadFreezer.java:205-207` | `detectLambdaInterface()` — can't detect interface | Medium — silent failure to detect lambda |

These are mostly appropriate for resilient capture (better to capture what we can
than to fail entirely), but the complete silence makes debugging difficult in
production.

**Recommendation:** Add `System.getLogger("ai.jacc.durableThreads").log(Level.DEBUG, ...)`
or at minimum `System.err.println` at debug level for each swallowed exception.

### Q2. Swallowed Exceptions in JdiHelper Port Detection

**File:** `JdiHelper.java` (multiple locations)

JdiHelper uses a multi-strategy cascade for port detection (cached port →
system property → command-line args → `/proc/net/tcp` → `lsof` → `netstat` →
ephemeral scan → default port). Each strategy silently catches failures and
falls through to the next. This is intentional and correct design — the
strategies are platform-specific fallbacks where failure is expected.

**Status:** Acceptable. The cascade design is well-suited to the cross-platform
requirement.

### Q3. Magic Numbers and Timeouts

Several hardcoded timeouts remain:

| Location | Value | Purpose |
|----------|-------|---------|
| `ThreadFreezer.freeze()` | 30,000 ms | Wait for worker thread |
| `ReplayState.LATCH_TIMEOUT_SECONDS` | 300 s (configurable via system property) | Go-latch timeout |
| `JdiHelper.PROBE_TIMEOUT_MS` | 200 ms | Port probe timeout |
| `JdiHelper.SCAN_RANGE` | 200 ports | Ephemeral port scan range |
| `ThreadRestorer.waitForThreadAtMethod()` | 30,000 ms | Wait for replay thread |
| `ThreadRestorer.isAtMethod()` | 10 frames | Max frames to inspect |
| Various | 10 ms, 1 ms | Poll intervals |

The go-latch timeout is now configurable via
`durable.restore.timeout.seconds` system property (good). Other timeouts are
not configurable.

**Recommendation:** For enterprise use, consider making the freeze timeout and
JDI wait timeout configurable via system properties, similar to the go-latch
timeout.

### Q4. ThreadRestorer Step Comments Skip Step 2

**File:** `ThreadRestorer.java:31-91`

The step comments go: Step 0, Step 1, Step 3, Step 3b, Step 4, Step 4b, Step 5,
Step 6. Step 2 is missing (likely removed during an earlier refactoring).

**Fix:** Renumber the steps sequentially.

---

## Testing Gaps

1. **No tests for concurrent restore** — all tests use sequential
   freeze/restore. The `synchronized(Durable.class)` serialization is the
   production guard, but overlapping `RestoredThread.resume()` calls (where
   the monitor has been released) are untested.

2. **No tests for thread name collision** — what happens when two threads share
   a name during freeze or restore.

3. **No tests for the `setValueBypassTypeCheck` path** — only exercised on
   Java 8, which is increasingly rare but still in the support matrix.

4. **No negative tests for corrupt snapshots** — what happens when a snapshot
   is partially corrupted or truncated during deserialization.

5. **Lambda bridge proxy coverage** — nested lambdas, method references to
   private methods, and serializable lambdas are not explicitly tested
   (documented in `lambda-frame-support-design.md`).

---

## Recommended Priority Order for Fixes

1. **C1** — FrameSnapshot missing lambdaBridgeInterface in equals/hashCode/toString (correctness)
2. **S1** — HeapRestorer dead branch (trivial fix, eliminates confusion)
3. **S2** — ThreadRestorer unused import (trivial cleanup)
4. **C2** — FreezeFlag thread ID reuse (robustness for enterprise)
5. **C4** — Thread name collision mitigation (robustness)
6. **B3** — Lambda proxy method disambiguation (defensive)
7. **Q1** — Add debug logging for swallowed exceptions (observability)
8. **Q3** — Make key timeouts configurable (enterprise configurability)
9. **Q4** — Renumber ThreadRestorer step comments (cosmetic)
10. **S3** — SnapshotFileWriter javadoc cleanup (cosmetic)
11. **C3** — Document handler deadlock risk more prominently (documentation)
