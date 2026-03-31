# Dead Code and Legacy Code Candidates

**Date:** 2026-03-28 (updated 2026-03-31)
**Purpose:** Inventory of code that appeared to be dead or left over from previous architectures, with recommendations for each item.

> **Status as of v1.4.0:** Items 1–6, 8–9 have been **removed**. Item 7 (HeapWalker)
> has been **moved to test sources**. Items 10–12 are resolved. See individual
> status annotations below.

---

## Legend

- **Confidence 99%+** — Can be removed without an experiment. The code is provably unreachable or unused.
- **Experiment needed** — The code might be reachable in edge cases. An experiment (removing it and running full CI + stress) is recommended before permanent removal.

---

## 1. ReplayState: Multi-Phase Latch Infrastructure — ✅ REMOVED

**Files:** `ReplayState.java:18-24, 52-63, 195-275, 284-285`
**Confidence:** 99%+ remove
**Status:** **Removed in v1.4.0.** All multi-phase latch infrastructure has been deleted.

The following are remnants of the old two-phase restore protocol, which was replaced by the single-pass direct-jump architecture:

| Item | Lines | Notes |
|------|-------|-------|
| `LATCH_LOCK` object | 24 | Was needed to coordinate localsReady latch recreation |
| `resumeLatch` field | 56 | Deepest frame used to block here; now blocks on go-latch via `freeze()` |
| `localsLatch` field | 63 | Per-frame Phase 2 latch; Phase 2 no longer exists |
| `resumePoint()` method | 167-188 | **Partially live** — still referenced by `emitNoInvokePrologue()` (see item 2) |
| `releaseResumePoint()` method | 195-200 | Never called from any code |
| `localsAwaitArmed` ThreadLocal | 208 | Phase 2 arm/disarm gate; never armed |
| `armLocalsAwait()` method | 214-216 | Never called from any code |
| `localsReady()` method | 227-261 | Never called from any code (was injected into bytecode by old PrologueInjector; no longer injected) |
| `releaseLocalsReady()` method | 268-275 | Never called from any code |
| `resumeLatch = null` in `activate()` | 284 | Only needed if resumeLatch existed |
| `localsLatch = null` in `activate()` | 285 | Only needed if localsLatch existed |
| `resumeLatch`/`localsLatch` creation in `activateWithLatch()` | 312-313 | Only needed if resumePoint/localsReady were used |

**Note on `resumePoint()`:** It is still emitted by `emitNoInvokePrologue()` for methods with zero invoke instructions. In the single-pass architecture, this is only reachable if a method with no invokes is the deepest frame (e.g., `freeze()` is called from a leaf method). However, `freeze()` itself IS an invoke, so any method that calls `freeze()` has at least one invoke and would go through `emitFullPrologue()`, not `emitNoInvokePrologue()`. The `resumePoint()` call in `emitNoInvokePrologue()` appears unreachable during restore — it only fires during replay of a method with zero invokes, which means that method can never be the deepest frame (since it never calls anything). This should be verified with an experiment.

**Experiment for `resumePoint()`:** Add a `throw new AssertionError("resumePoint reached in no-invoke prologue")` at the top of `resumePoint()` and run full CI. If nothing fires, `resumePoint()` is dead.

### Associated test code

| Item | File |
|------|------|
| `ReplayStateTest.resumePointBlocksUntilReleased()` | `ReplayStateTest.java:89-107` |
| Entire `ReplayStateRegressionTest` class (10 tests) | `ReplayStateRegressionTest.java` |

These tests exercise `resumePoint()`, `releaseResumePoint()`, and `signalRestoreError()` in the context of the old latch protocol. If the latch infrastructure is removed, these tests should be removed too.

---

## 2. ReplayState: Boxing/Unboxing Helpers — ✅ REMOVED

**File:** `ReplayState.java:395-411`
**Confidence:** 99%+ remove
**Status:** **Removed in v1.4.0.**

16 static methods (`boxBoolean`, `boxByte`, ..., `unboxBoolean`, `unboxByte`, ...) that were used by the old resume stubs to box return values into `__retVal` and unbox them after the post-invoke jump. The direct-jump architecture doesn't box/unbox return values — stubs jump to BEFORE_INVOKE, not POST_INVOKE.

These methods are only referenced from `boxReturnValue()` and `unboxReturnValue()` in PrologueInjector, which are themselves dead (see item 3).

The comment says "These MUST live in ReplayState so that RawBytecodeScanner filters them out." This filtering concern is moot since the methods are never emitted into instrumented bytecode.

---

## 3. PrologueInjector: Dead Methods — ✅ REMOVED

**File:** `PrologueInjector.java`
**Confidence:** 99%+ remove
**Status:** **Removed in v1.4.0.** All dead methods (`boxReturnValue`, `unboxReturnValue`, `pushDummyReturnValue`, `getInvokeDynamicIndices`, `indyIndicesByMethod`) have been deleted.

| Method | Lines | Reason |
|--------|-------|--------|
| `boxReturnValue(Type)` | 961-1002 | Never called — was used by old stubs for return value boxing |
| `unboxReturnValue(Type, int)` | 1011-1062 | Never called — was used by old stubs for return value unboxing |
| `pushDummyReturnValue(String)` | 944-949 | Never called — was used by old stubs to push dummy return |
| `getInvokeDynamicIndices()` | 75-78 | Never called externally; the `indyIndicesByMethod` map is never populated |
| `indyIndicesByMethod` map | 53 | Never written to (no `indyIndicesOut.put()` calls exist) |
| `indyIndicesOut` field in PrologueMethodVisitor | 109 | Passed through but never written to |

---

## 4. ThreadRestorer: Dead Methods — ✅ REMOVED

**File:** `ThreadRestorer.java`
**Confidence:** 99%+ remove
**Status:** **Removed in v1.4.0.** Dead methods (`isCorrectFrame`, `setLocalsForSingleFrame`) have been deleted.

| Method | Lines | Reason |
|--------|-------|--------|
| `isCorrectFrame()` | 499-518 | Was used to poll whether replay thread reached the correct frame's `localsReady()`. No callers. |
| `setLocalsForSingleFrame()` | 630-675 | Was used for Phase 2 per-frame local setting. No callers. |

---

## 5. InvokeRegistry: STUB_OFFSETS Infrastructure — ✅ REMOVED

**File:** `InvokeRegistry.java:27, 52-54, 72`
**Confidence:** 99%+ remove
**Status:** **Removed in v1.4.0.**

| Item | Lines | Reason |
|------|-------|--------|
| `STUB_OFFSETS` map | 27 | Never populated — `registerStubOffsets()` is never called |
| `registerStubOffsets()` method | 52-54 | No callers anywhere in codebase |
| Fallback lookup in `getInvokeIndex()` | 72 | Always returns -1 since STUB_OFFSETS is always empty |

This was infrastructure for re-freezing a restored thread (where BCP might point to a stub invoke). With the direct-jump architecture, stubs don't contain user invokes, so the BCP always points to original code.

---

## 6. InvokeRegistry: Unused Public Methods — ✅ REMOVED

**File:** `InvokeRegistry.java`
**Confidence:** 99%+ remove
**Status:** **Removed in v1.4.0.**

| Method | Lines | Reason |
|--------|-------|--------|
| `getInvokeOffsets()` | 95-97 | Only called from `InvokeRegistryTest` — no production callers |
| `isInstrumented()` | 116-118 | Only called from `InvokeRegistryTest` — no production callers |

These are public API methods with no production callers. They exist only for test convenience. Could be kept if considered useful public API, or made package-private and tested indirectly.

---

## 7. HeapWalker (Reflection-Based) — ✅ MOVED TO TEST SOURCES

**File:** formerly `internal/HeapWalker.java` (entire class, 124 lines)
**Confidence:** Experiment needed
**Status:** **Moved to test sources in v1.4.0.** Now lives at `src/test/java/ai/jacc/durableThreads/internal/HeapWalker.java` where it serves as a test utility for `HeapRoundTripTest`.

---

## 8. LambdaFrameException — ✅ REMOVED

**File:** `exception/LambdaFrameException.java` (entire class)
**Confidence:** 99%+ remove
**Status:** **Removed in v1.4.0.** Lambda frames are now handled transparently via the bridge proxy approach.

---

## 9. Version.java — ✅ REMOVED

**File:** `Version.java` (entire class)
**Confidence:** 99%+ remove
**Status:** **Removed in v1.4.0.** The version is maintained in `pom.xml`.

---

## 10. ~~SnapshotFileWriter Null Path~~ — NOT DEAD CODE

**File:** `SnapshotFileWriter.java`
**Status:** KEEP — incorrectly identified as dead code.

The null-path handling IS required. During restore, the deepest frame's resume
stub jumps to BEFORE_INVOKE for the original `freeze("path")` call. At that
point, the local variable holding the path is a dummy null (JDI hasn't set it
yet). The `SnapshotFileWriter(String)` constructor receives null and must not
throw. The `accept()` no-op return prevents writing to a null path. Removing
this causes NPE → replay thread dies → JDI worker times out.

---

## 11. ThreadFreezer: blockForever() Reachability

**File:** `ThreadFreezer.java:125-155`
**Confidence:** Experiment needed

`blockForever()` is a safety net that prevents a frozen thread from ever continuing past `freeze()`. It's called if the thread somehow reaches the end of `freeze()` without being terminated by the interrupt/flag mechanism.

The method has two phases: a sleep loop (100 iterations checking FreezeFlag) and a busy spin. In the current architecture, the interrupt path should always fire before `blockForever()` is reached, but proving this definitively requires an experiment.

**Experiment:** Add `System.err.println("[EXPERIMENT] blockForever reached!")` at the top of `blockForever()` and run full CI + stress. If it never prints, consider whether the safety net is worth keeping as defensive code (it costs nothing at runtime since it's never reached) or removing for clarity.

**Recommendation:** Even if unreachable, this is defensive code with zero runtime cost. Consider keeping it but simplifying (remove the busy spin, just loop with `Thread.sleep`).

---

## 12. ThreadRestorer: Javadoc and Comments Referencing Phase 2 — ✅ CLEANED UP

**File:** `ThreadRestorer.java` (various locations)
**Confidence:** 99%+ update
**Status:** **Fixed in v1.4.0.** Stale Phase 2 comments have been removed as part of the ThreadRestorer decomposition into JdiLocalSetter, JdiValueConverter, SnapshotValidator, and ReflectionHelpers.

---

## Summary

| # | Item | Size | Confidence | Status |
|---|------|------|-----------|--------|
| 1 | ReplayState multi-phase latches | ~100 lines | 99%+ | ✅ Removed |
| 2 | ReplayState box/unbox helpers | 16 methods | 99%+ | ✅ Removed |
| 3 | PrologueInjector dead methods | ~120 lines | 99%+ | ✅ Removed |
| 4 | ThreadRestorer dead methods | ~50 lines | 99%+ | ✅ Removed |
| 5 | InvokeRegistry STUB_OFFSETS | ~10 lines | 99%+ | ✅ Removed |
| 6 | InvokeRegistry unused public methods | 2 methods | 99%+ | ✅ Removed |
| 7 | HeapWalker class | 124 lines | Experiment | ✅ Moved to test sources |
| 8 | LambdaFrameException | 1 class | 99%+ | ✅ Removed |
| 9 | Version.java | 1 class | 99%+ | ✅ Removed |
| 10 | SnapshotFileWriter null path | ~5 lines | — | ✅ Kept (NOT dead code) |
| 11 | blockForever() | ~25 lines | Experiment | ✅ Kept as defensive code |
| 12 | Stale Phase 2 comments | ~15 lines | 99%+ | ✅ Cleaned up |

**All items resolved as of v1.4.0.** ~450 lines of dead production code removed.
