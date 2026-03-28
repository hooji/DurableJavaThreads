# Dead Code and Legacy Code Candidates

**Date:** 2026-03-28
**Purpose:** Inventory of code that appears to be dead or left over from previous architectures, with recommendations for each item.

---

## Legend

- **Confidence 99%+** — Can be removed without an experiment. The code is provably unreachable or unused.
- **Experiment needed** — The code might be reachable in edge cases. An experiment (removing it and running full CI + stress) is recommended before permanent removal.

---

## 1. ReplayState: Multi-Phase Latch Infrastructure

**Files:** `ReplayState.java:18-24, 52-63, 195-275, 284-285`
**Confidence:** 99%+ remove

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

## 2. ReplayState: Boxing/Unboxing Helpers

**File:** `ReplayState.java:395-411`
**Confidence:** 99%+ remove

16 static methods (`boxBoolean`, `boxByte`, ..., `unboxBoolean`, `unboxByte`, ...) that were used by the old resume stubs to box return values into `__retVal` and unbox them after the post-invoke jump. The direct-jump architecture doesn't box/unbox return values — stubs jump to BEFORE_INVOKE, not POST_INVOKE.

These methods are only referenced from `boxReturnValue()` and `unboxReturnValue()` in PrologueInjector, which are themselves dead (see item 3).

The comment says "These MUST live in ReplayState so that RawBytecodeScanner filters them out." This filtering concern is moot since the methods are never emitted into instrumented bytecode.

---

## 3. PrologueInjector: Dead Methods

**File:** `PrologueInjector.java`
**Confidence:** 99%+ remove

| Method | Lines | Reason |
|--------|-------|--------|
| `boxReturnValue(Type)` | 961-1002 | Never called — was used by old stubs for return value boxing |
| `unboxReturnValue(Type, int)` | 1011-1062 | Never called — was used by old stubs for return value unboxing |
| `pushDummyReturnValue(String)` | 944-949 | Never called — was used by old stubs to push dummy return |
| `getInvokeDynamicIndices()` | 75-78 | Never called externally; the `indyIndicesByMethod` map is never populated |
| `indyIndicesByMethod` map | 53 | Never written to (no `indyIndicesOut.put()` calls exist) |
| `indyIndicesOut` field in PrologueMethodVisitor | 109 | Passed through but never written to |

---

## 4. ThreadRestorer: Dead Methods

**File:** `ThreadRestorer.java`
**Confidence:** 99%+ remove

| Method | Lines | Reason |
|--------|-------|--------|
| `isCorrectFrame()` | 499-518 | Was used to poll whether replay thread reached the correct frame's `localsReady()`. No callers. |
| `setLocalsForSingleFrame()` | 630-675 | Was used for Phase 2 per-frame local setting. No callers. |

---

## 5. InvokeRegistry: STUB_OFFSETS Infrastructure

**File:** `InvokeRegistry.java:27, 52-54, 72`
**Confidence:** 99%+ remove

| Item | Lines | Reason |
|------|-------|--------|
| `STUB_OFFSETS` map | 27 | Never populated — `registerStubOffsets()` is never called |
| `registerStubOffsets()` method | 52-54 | No callers anywhere in codebase |
| Fallback lookup in `getInvokeIndex()` | 72 | Always returns -1 since STUB_OFFSETS is always empty |

This was infrastructure for re-freezing a restored thread (where BCP might point to a stub invoke). With the direct-jump architecture, stubs don't contain user invokes, so the BCP always points to original code.

---

## 6. InvokeRegistry: Unused Public Methods

**File:** `InvokeRegistry.java`
**Confidence:** 99%+ remove

| Method | Lines | Reason |
|--------|-------|--------|
| `getInvokeOffsets()` | 95-97 | Only called from `InvokeRegistryTest` — no production callers |
| `isInstrumented()` | 116-118 | Only called from `InvokeRegistryTest` — no production callers |

These are public API methods with no production callers. They exist only for test convenience. Could be kept if considered useful public API, or made package-private and tested indirectly.

---

## 7. HeapWalker (Reflection-Based)

**File:** `internal/HeapWalker.java` (entire class, 124 lines)
**Confidence:** Experiment needed

`HeapWalker` operates on live Java objects via reflection. It is NOT used in the production freeze/restore path — `JdiHeapWalker` is used instead. The only usage is in `HeapRoundTripTest` (18 tests).

`HeapWalker` is a simpler, in-process heap walker that doesn't handle collections, enums, immutables, or opaque types. It serves as a test utility for verifying that `HeapRestorer` can round-trip objects that `HeapWalker` captures.

**Recommendation:** Keep as a test utility if the round-trip tests are valuable, but consider renaming to `TestHeapWalker` or moving to test sources. Alternatively, rewrite `HeapRoundTripTest` to use `JdiHeapWalker` (which would require a JDI connection in tests — higher friction).

---

## 8. LambdaFrameException

**File:** `exception/LambdaFrameException.java` (entire class)
**Confidence:** 99%+ remove

Never thrown anywhere in the codebase. Was thrown before lambda bridge proxy support was implemented. Now lambda frames are handled transparently. Referenced only in test comments that describe the old behavior.

---

## 9. Version.java

**File:** `Version.java` (entire class)
**Confidence:** 99%+ remove

`public static String version = "v1.3.1"` — Never referenced from any production or test code. Mutable public field (not `final`). The version is already in `pom.xml`.

---

## 10. SnapshotFileWriter Null Path

**File:** `SnapshotFileWriter.java:38-39, 54`
**Confidence:** 99%+ remove (the null-path code, not the class)

The constructors accept `null` paths, and `accept()` returns early if path is null. The comment says "used during restore when the replay stub re-executes freeze() with dummy null arguments." This is from the old architecture — in the current architecture, `freeze()` during restore blocks on the go-latch and never calls the handler, so the null path is never exercised.

Both `Durable.freeze(String)` and `Durable.freeze(Path)` pass non-null paths. The `freeze(Consumer, Map)` overload takes a Consumer directly. No code path creates a `SnapshotFileWriter` with null.

---

## 11. ThreadFreezer: blockForever() Reachability

**File:** `ThreadFreezer.java:125-155`
**Confidence:** Experiment needed

`blockForever()` is a safety net that prevents a frozen thread from ever continuing past `freeze()`. It's called if the thread somehow reaches the end of `freeze()` without being terminated by the interrupt/flag mechanism.

The method has two phases: a sleep loop (100 iterations checking FreezeFlag) and a busy spin. In the current architecture, the interrupt path should always fire before `blockForever()` is reached, but proving this definitively requires an experiment.

**Experiment:** Add `System.err.println("[EXPERIMENT] blockForever reached!")` at the top of `blockForever()` and run full CI + stress. If it never prints, consider whether the safety net is worth keeping as defensive code (it costs nothing at runtime since it's never reached) or removing for clarity.

**Recommendation:** Even if unreachable, this is defensive code with zero runtime cost. Consider keeping it but simplifying (remove the busy spin, just loop with `Thread.sleep`).

---

## 12. ThreadRestorer: Javadoc and Comments Referencing Phase 2

**File:** `ThreadRestorer.java` (various locations)
**Confidence:** 99%+ update

Several comments and javadoc blocks reference the old Phase 1/Phase 2 architecture:
- Line 463: `"resumePoint", "localsReady"` in parameter docs
- Lines 495-496: "detect when the replay thread is still in a previous frame's localsReady()"
- Lines 559-567: Stack layout comment references `localsReady()` at frame 0
- Lines 573-577: `@param requireAllLocals` javadoc references Phase 1 vs Phase 2
- Lines 799: "Phase 1: non-parameter locals may not be in scope yet"

These should be updated to reflect the single-pass architecture, even if the code they describe is functionally correct.

---

## Summary

| # | Item | Size | Confidence | Action |
|---|------|------|-----------|--------|
| 1 | ReplayState multi-phase latches | ~100 lines | 99%+ | Remove (except verify resumePoint reachability) |
| 2 | ReplayState box/unbox helpers | 16 methods | 99%+ | Remove |
| 3 | PrologueInjector dead methods | ~120 lines | 99%+ | Remove |
| 4 | ThreadRestorer dead methods | ~50 lines | 99%+ | Remove |
| 5 | InvokeRegistry STUB_OFFSETS | ~10 lines | 99%+ | Remove |
| 6 | InvokeRegistry unused public methods | 2 methods | 99%+ | Remove or make package-private |
| 7 | HeapWalker class | 124 lines | Experiment | Keep as test utility or move to test sources |
| 8 | LambdaFrameException | 1 class | 99%+ | Remove |
| 9 | Version.java | 1 class | 99%+ | Remove |
| 10 | SnapshotFileWriter null path | ~5 lines | 99%+ | Remove null handling |
| 11 | blockForever() | ~25 lines | Experiment | Keep as defensive code, simplify |
| 12 | Stale Phase 2 comments | ~15 lines | 99%+ | Update comments |

**Estimated total dead code:** ~450 lines of production code + 10 unit tests + 1 exception class
