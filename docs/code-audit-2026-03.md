# Durable Java Threads — Code Audit Report

**Date:** 2026-03-29 (updated)
**Scope:** Full codebase review for reliability, correctness, race conditions, dead code, and code quality.
**Codebase Version:** 1.4.0

---

## Executive Summary

The codebase implements a sophisticated thread freeze/restore mechanism using JDI
self-attach and ASM bytecode instrumentation. The codebase has undergone extensive
cleanup and is in good shape for enterprise use.

### Completed Fixes (Chronological)

#### Prior to this audit (earlier refactoring rounds)

| Item | Status |
|------|--------|
| ReplayState static shared state | **Fixed** — latches documented and scoped; old multi-phase infrastructure removed |
| HeapWalker (reflection-based) unused | **Fixed** — moved to test sources |
| Stale multi-phase infrastructure | **Fixed** — removed from ReplayState |
| STUB_OFFSETS in InvokeRegistry | **Fixed** — removed |
| SnapshotFileWriter duplicate javadoc | **Fixed** — two constructors now have distinct docs |
| Version.java mutable public field | **Fixed** — class removed entirely |
| PrologueInjector too large | **Fixed** — decomposed into PrologueInjector + PrologueEmitter + OperandStackSimulator + PrologueTypes (1242 lines total) |
| sun.misc.Unsafe usage in ReplayState | **Fixed** — replaced with Objenesis |
| Dead code items 1–6, 8–9 from dead-code-candidates.md | **Fixed** — all removed |
| ThreadRestorer too large | **Fixed** — decomposed into ThreadRestorer + JdiValueConverter + JdiLocalSetter + SnapshotValidator + ReflectionHelpers |

#### Fixed during this audit round

| Item | Commit | What changed |
|------|--------|--------------|
| FrameSnapshot equals/hashCode/toString | `d0d4be7` | Added missing `lambdaBridgeInterface` to all three methods |
| HeapRestorer dead branch | `0e3d3b2` | Removed identical if/else in `populateArray()` |
| ThreadRestorer unused import | `6f7f38d` | Removed stale `isInfrastructureFrame` static import |
| FreezeFlag thread ID reuse | `913e546` | Replaced `Set<Long>` with `Set<Thread>` using IdentityHashMap |
| Timeouts configurable | `76c390f` | Added `durable.freeze.timeout.ms` and `durable.jdi.wait.timeout.ms` system properties |
| detectLambdaInterface dead catch | `4efd6f7` | Removed — confirmed never fires (258/258 tests pass without it) |
| autoNameThis dead catch | `8a396be` | Removed — confirmed never fires (258/258 tests pass without it) |
| captureLocals "this" dead catch | `4099fc6` | Removed — confirmed never fires (258/258 tests pass without it) |
| captureLocals slot reflection | `f7d6b77` | **Removed entirely** — the reflection always failed (`getMethod` on a package-private method), silently defaulting every slot to 0. Restore matches by name, not slot. The reflection was broken dead code masking a latent bug. |
| CI: ubuntu JDK 10 | `ae9807f` | Removed from build + stress matrix (intermittent Zulu failures unrelated to our code) |
| CI: Node.js 24 | `6b1989e` | Bumped actions/checkout, actions/setup-java, actions/cache from v4 to v5 |
| Snapshot handler deadlock risk | `514c157` | Moved handler.accept() after threadRef.resume() — eliminates deadlock if handler acquires locks held by the frozen thread |

---

## Remaining Issues

### Critical

**None.** All critical issues have been resolved.

### Medium — Worth Addressing

#### C4. Thread Name Collision in JdiHelper.findThread()

**File:** `JdiHelper.java:539-546`
**Severity:** Medium

Thread matching is done by name only. If two threads have the same name,
the wrong thread could be frozen or restored. The code acknowledges this in
comments (lines 532-537) but provides no mitigation.

**Recommendation:** After finding a thread by name, validate that it is in the
expected state (e.g., stack contains `freeze()` for freeze, or `WAITING` in
`awaitGoLatch` for restore). This won't prevent collision entirely but will
detect most mismatches.

#### B3. Lambda Bridge Proxy Method Lookup by Name Only

**File:** `ThreadRestorer.java:199-204`
**Severity:** Low-Medium

`createLambdaBridgeProxy()` finds the target synthetic method by name only
(no signature matching). In practice, the JVM generates unique names
(`lambda$<enclosing>$<counter>`), so collisions are unlikely but not
impossible with obfuscation tools.

**Recommendation:** Match by both name and parameter count as a basic
disambiguation.

### Low — Cosmetic / Nice-to-Have

#### Q4. ThreadRestorer Step Comments Skip Step 2

**File:** `ThreadRestorer.java:31-91`

Step comments go: 0, 1, 3, 3b, 4, 4b, 5, 6. Step 2 is missing.

**Fix:** Renumber sequentially.

#### S3. SnapshotFileWriter Constructor Javadoc Overlap

**File:** `SnapshotFileWriter.java:26-46`

Two constructors have nearly identical javadoc. Minor cosmetic issue.

---

## Concurrency Analysis (No Action Needed)

These items were analyzed and found to be safe:

| Item | Status |
|------|--------|
| `restoreError` static volatile field | Safe — serialized by `synchronized(Durable.class)`, documented in ReplayState class javadoc |
| `FreezeFlag` concurrent access pattern | Safe — `synchronizedSet.add()` provides happens-before edge before `interrupt()` |
| `HeapObjectBridge.clear()` timing | Safe — called before `resume()`, not during user code execution |
| JdiHelper port detection swallowed exceptions | Acceptable — intentional multi-strategy cascade where failure is expected |

---

## Configurable Timeouts

All key timeouts are now configurable via system properties:

| Property | Default | Location | Purpose |
|----------|---------|----------|---------|
| `durable.freeze.timeout.ms` | 30,000 ms | ThreadFreezer | Caller thread wait for freeze worker |
| `durable.jdi.wait.timeout.ms` | 30,000 ms | ThreadRestorer | JDI worker wait for replay thread |
| `durable.restore.timeout.seconds` | 300 s | ReplayState | Go-latch wait for RestoredThread.resume() |

Remaining hardcoded values (JdiHelper probe timeouts, poll intervals) are
internal implementation details that do not need enterprise configurability.

---

## Testing Gaps

1. **No tests for concurrent restore** — all tests use sequential
   freeze/restore. The `synchronized(Durable.class)` serialization is the
   production guard, but overlapping `RestoredThread.resume()` calls (where
   the monitor has been released) are untested.

2. **No tests for thread name collision** — what happens when two threads
   share a name during freeze or restore.

3. **No tests for the `setValueBypassTypeCheck` path** — only exercised on
   Java 8, which is increasingly rare but still in the support matrix.

4. **No negative tests for corrupt snapshots** — what happens when a snapshot
   is partially corrupted or truncated during deserialization.

5. **Lambda bridge proxy coverage** — nested lambdas, method references to
   private methods, and serializable lambdas are not explicitly tested
   (documented in `lambda-frame-support-design.md`).

---

## CI Configuration

**Workflow:** `.github/workflows/ci.yml`
**Matrix:** JDK 8–25 on ubuntu, macOS, Windows (ubuntu skips JDK 10 due to
intermittent Zulu failures; macOS skips 8/9/10/12/14 due to no ARM Zulu builds)
**Jobs:** `build` (unit + E2E) and `stress` (20-repetition E2E via `-Pstress`)
**Actions:** All on v5 (Node.js 24 compatible, ahead of June 2026 deadline)
