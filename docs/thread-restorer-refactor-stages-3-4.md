# ThreadRestorer Decomposition — Stages 3 and 4

**Date:** 2026-03-28
**Branch:** `claude/document-java-threads-XGpM7`
**Current state:** Stages 1, 2a-2e complete. All 258 tests pass (215 unit + 43 E2E).

## What's Been Done

ThreadRestorer has been partially decomposed:

- **Stage 1:** `SnapshotValidator` — validates bytecode/structure hashes, loads classes
- **Stage 2:** `ReflectionHelpers` — method lookup, descriptor matching, type conversion,
  dummy args, receiver creation, bottom frame invocation, cause chain search

## What Remains

ThreadRestorer is now ~850 lines. Two more extractions are planned:

### Stage 3: Extract `JdiValueConverter`

**What to extract:** Stateless JDI value conversion methods.

| Method | Lines | Purpose |
|--------|-------|---------|
| `convertToJdiValue()` | ~10 | Dispatches NullRef/PrimitiveRef/HeapRef |
| `convertPrimitiveToJdiValue()` | ~12 | Boxed primitive → JDI VirtualMachine.mirrorOf() |
| `resolveHeapRefViaJdi()` | ~40 | Reads HeapObjectBridge via JDI field access to get ObjectReference |

**Dependencies:** Takes a `VirtualMachine` reference (from `JdiHelper.getConnection()`).
Uses `JdiHelper.getConcurrentHashMapValue()` for bridge access.

**Call sites in ThreadRestorer:**
- `convertToJdiValue()` is called from `setFrameLocals()` (line ~640 in current code)

**Risk:** Low. Pure functions, no state, straightforward extraction.

**Exclusion lists:** `JdiValueConverter` must be added to:
1. `DurableTransformer.EXCLUDED_CLASSES` — already handled if it's in `ai/jacc/durableThreads/`
   package (but verify — the exclusion is per-class, not per-package for this package)
2. `FrameFilter.EXCLUDED_FRAME_CLASSES` — probably NOT needed since this class won't
   appear on a captured thread's stack (it's only called from the JDI worker thread,
   not the replay thread). But check to be sure.

### Stage 4: Extract `JdiLocalSetter`

**What to extract:** The complex JDI local variable manipulation logic.

| Method | Lines | Purpose |
|--------|-------|---------|
| `setLocalsViaJdi()` | ~40 | Matches JDI frames to snapshot frames, iterates |
| `setFrameLocals()` | ~130 | The big one: local lookup, value resolution, GC pinning, setValue with exception handling |
| `setValueBypassTypeCheck()` | ~50 | Java 8 JDI reflection hack to bypass ClassNotLoadedException |
| `preloadSnapshotClasses()` | ~30 | Ensures JDI can find classes before setValue |
| `forceLoadClass()` | ~20 | JDI invokeMethod to call Class.forName in target VM |
| `LocalEntry` inner class | ~35 | Pre-resolved local variable entry for batch setValue |

**Dependencies:**
- `VirtualMachine` (from `JdiHelper.getConnection()`)
- `ThreadReference` (the suspended replay thread)
- `ThreadSnapshot` + `HeapRestorer` + restored heap map (for value resolution)
- `JdiValueConverter` (for converting snapshot ObjectRefs to JDI Values)
- `FrameFilter.isInfrastructureFrame()` (for frame matching)

**Call sites in ThreadRestorer:**
- `setLocalsViaJdi()` called from `runJdiRestore()` (one call site)
- `preloadSnapshotClasses()` called from `runJdiRestore()` (one call site)

**Risk:** Medium-high. This is the most complex code in the codebase — lots of
JDI exception handling, GC pinning, frame matching. Move the methods exactly
as-is; do NOT refactor the logic during extraction.

**Exclusion lists:**
1. `DurableTransformer.EXCLUDED_CLASSES` — YES, must add
2. `FrameFilter.EXCLUDED_FRAME_CLASSES` — probably NOT needed (JDI worker thread
   only, not replay thread). But verify.

## Critical Lessons Learned

### 1. Two exclusion lists must stay in sync

Any new class in `ai.jacc.durableThreads` must be added to
`DurableTransformer.EXCLUDED_CLASSES`. Without this, the agent injects replay
prologues into the class, causing `IllegalAccessError` at runtime.

If the class can appear on a **replay thread's call stack** (i.e., it's called
during the replay/restore sequence on the thread that will be frozen), it must
ALSO be added to `FrameFilter.EXCLUDED_FRAME_CLASSES`. Without this, re-freeze
in multi-cycle scenarios captures the class as a user frame, corrupting the
snapshot.

### 2. Always rebuild the jar before E2E tests

E2E tests spawn child JVMs that load the agent jar from `target/`. If the jar is
stale, child JVMs run old code. Always run `mvn package -DskipTests` before
`mvn failsafe:integration-test`.

### 3. Run ALL tests after each extraction

Not just HelloWorldIT — run the full suite including multi-cycle tests. The
multi-cycle tests (`MultiCycleFreezeRestoreIT`, `MultiCycleNamedObjectIT`) are
the most sensitive to frame filtering issues.

### 4. Recommended test command sequence

```bash
mvn compile -q && mvn test           # unit tests (215)
mvn package -DskipTests -q           # rebuild jar
mvn failsafe:integration-test failsafe:verify  # E2E tests (43)
```

## What Stays in ThreadRestorer After Stages 3-4

After all extractions, ThreadRestorer should contain only:

- `restore()` — the main orchestration method (~60 lines)
- `runJdiRestore()` — JDI worker thread body, delegates to JdiLocalSetter (~30 lines)
- `computeResumeIndices()` — reads invoke indices from snapshot (~10 lines)
- `computeFrameReceivers()` — pre-resolves "this" for each frame (~25 lines)
- `createLambdaBridgeProxy()` — creates dynamic proxy for lambda frames (~50 lines)
- `waitForThreadAtMethod()` / `isAtMethod()` — JDI thread polling (~40 lines)

Estimated: ~215 lines, down from ~1150 at the start of refactoring.
