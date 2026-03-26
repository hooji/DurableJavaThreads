# Architectural Change: Direct-Jump Resume Stubs for Single-Pass Local Restoration

## Overview

This document describes a fundamental change to how the replay prologue rebuilds the call stack during thread restoration. The change eliminates the two-phase local variable restoration process, replacing it with a single-pass approach where all locals in all frames can be set simultaneously.

## Problem Statement

The current architecture requires a two-phase process to restore local variables:

- **Phase 1:** Rebuild the call stack via resume stubs, set only parameters (always in scope)
- **Phase 2:** Process each frame one at a time, deepest first, waiting for each frame to reach `localsReady()` in its original code section before non-parameter locals can be set via JDI

Phase 2 fails when a restored thread re-freezes before returning through all frames. After the deepest frame's locals are set and it continues executing, if it calls `freeze()` again, shallower frames never reach their `localsReady()` and the JDI worker times out.

The root cause: in the current architecture, each frame's resume stub makes its OWN call to the deeper method. The frame remains in stub code (not original code) while the deeper method executes. Non-parameter locals are out of scope in stub code, so JDI cannot set them until the frame transitions to its original code section.

## Current Architecture

### How Resume Stubs Work Today

When `PrologueInjector` instruments a method, it injects a replay prologue at the beginning of every method. The prologue structure for a method with N invoke instructions:

```
METHOD ENTRY:
  __retVal = null
  if (!ReplayState.isReplayThread()) goto ORIGINAL_CODE
  switch (ReplayState.currentResumeIndex()):
    case 0: goto RESUME_0
    case 1: goto RESUME_1
    ...

RESUME_N (for the deepest frame):
  if (isLastFrame) {
    resumePoint()           // blocks for JDI (Phase 1)
    deactivate()
    armLocalsAwait()
    push sub-stack defaults
    push dummy return value
    goto POST_INVOKE_N      // jump to original code section
  } else {
    advanceFrame()
    push receiver + dummy args
    INVOKE deeper_method    // STUB MAKES ITS OWN CALL
    box return → __retVal
    armLocalsAwait()
    push sub-stack defaults
    unbox __retVal
    goto POST_INVOKE_N
  }

ORIGINAL_CODE:
  ... original bytecode ...
  invoke_N                  // original call site
  POST_INVOKE_N:
  localsReady()             // blocks for JDI (Phase 2)
  ... more original code ...
```

### The Stack During Restore (Current)

For a snapshot with frames `[originalUserMethod, fact(3), fact(2), fact(1)]` (bottom to top):

After Phase 1 (thread blocked at `resumePoint()`):
```
resumePoint()                     ← blocked on resumeLatch
fact(1) RESUME STUB               ← stub code, non-param locals OUT OF SCOPE
fact(2) RESUME STUB               ← stub code, non-param locals OUT OF SCOPE
fact(3) RESUME STUB               ← stub code, non-param locals OUT OF SCOPE
originalUserMethod() RESUME STUB  ← stub code, non-param locals OUT OF SCOPE
────────────────────────────────────
invokeBottomFrame() [reflection]
Thread.run()
```

The problem: JDI can only set parameters in all frames (parameters are always in scope). Non-parameter locals require each frame to be in its original code section, necessitating the per-frame Phase 2 dance.

### Phase 2 Flow (Current)

1. Release `resumePoint()` → deepest frame's stub jumps to `POST_INVOKE` in original code
2. `localsReady()` blocks → JDI sets ALL locals for deepest frame → releases
3. Deepest frame continues executing user code, eventually returns to next frame
4. Next frame's stub receives return value, jumps to its `POST_INVOKE`
5. `localsReady()` blocks → JDI sets locals → releases
6. Repeat until shallowest frame is processed

This sequential unwinding is what breaks when the deepest frame re-freezes instead of returning.

## New Architecture: Direct-Jump Stubs

### Core Idea

Instead of each resume stub making its own call to the deeper method, the stub jumps to the point in the original code section **just before** the original invoke instruction. The original bytecode makes the call. This means every frame on the stack is positioned in its original code section, with all local variables in scope.

### New Resume Stub Structure

```
RESUME_N (for non-deepest frames):
  advanceFrame()
  initialize non-param locals to defaults  // for verifier compatibility
  goto BEFORE_INVOKE_N                     // jump INTO original code

RESUME_N (for the deepest frame):
  resumePoint()             // blocks for JDI — all locals set here
  initialize non-param locals to defaults
  goto POST_INVOKE_N        // jump past the freeze() call

ORIGINAL_CODE:
  ... original bytecode ...
  BEFORE_INVOKE_N:          // NEW label, right before the invoke
  invoke_N                  // original call — the REAL bytecode makes the call
  POST_INVOKE_N:
  ... more original code ...
```

### The Stack During Restore (New)

After Phase 1 (thread blocked at `resumePoint()`):
```
resumePoint()                     ← blocked on resumeLatch
fact(1) ORIGINAL CODE SECTION     ← at POST_INVOKE, locals IN SCOPE
fact(2) ORIGINAL CODE SECTION     ← at invoke of fact(1), locals IN SCOPE
fact(3) ORIGINAL CODE SECTION     ← at invoke of fact(2), locals IN SCOPE
originalUserMethod() ORIG CODE    ← at invoke of fact(3), locals IN SCOPE
────────────────────────────────────
invokeBottomFrame() [reflection]
Thread.run()
```

**ALL frames are in their original code sections.** JDI can set ALL locals (parameters AND non-parameters) in ALL frames in a single pass while the thread is at `resumePoint()`. Phase 2 is eliminated entirely.

### What Happens After Locals Are Set

The JDI worker sets all locals in all frames, then releases `resumePoint()`. The deepest frame (`fact(1)`) wakes and continues from `POST_INVOKE_N` — right after where `freeze()` was called. User code runs. The deepest frame eventually returns through the call chain normally.

For the go-latch (pausing before user code runs): `resumePoint()` can be replaced with a two-stage block — first wait for JDI to finish, then wait for the go-latch. Or a separate synthetic frame at the deep end can hold the go-latch (see below).

## Implementation Details

### PrologueInjector Changes

The `PrologueInjector.PrologueMethodVisitor` class needs significant changes:

#### 1. Add BEFORE_INVOKE Labels

Currently, the injector creates `postInvokeLabels[]` (one per invoke). It needs to also create `beforeInvokeLabels[]`. These labels are placed immediately before each invoke instruction in the original code:

```java
// In emitOriginalCode():
if (op instanceof InvokeMarker) {
    InvokeMarker marker = (InvokeMarker) op;
    target.visitLabel(beforeInvokeLabels[marker.index]);  // NEW
    target.visitMethodInsn(marker.opcode, marker.owner, marker.name,
            marker.descriptor, marker.isInterface);
    target.visitLabel(postInvokeLabels[marker.index]);
    target.visitMethodInsn(Opcodes.INVOKESTATIC,
            RS, "localsReady", "()V", false);
}
```

Similarly for `InvokeDynamicMarker`.

#### 2. Change Non-Deepest Resume Stubs

The non-deepest frame stub currently:
1. Calls `advanceFrame()`
2. Pushes receiver + dummy args
3. Invokes the deeper method directly
4. Boxes return value

New behavior:
1. Calls `advanceFrame()`
2. Initializes non-parameter locals to type-appropriate defaults (for verifier)
3. Sets up the operand stack to match what the original code expects at `BEFORE_INVOKE_N`
4. Jumps to `BEFORE_INVOKE_N`

The original invoke instruction then executes, making the real call. The receiver and arguments on the operand stack will initially be defaults (null/0), but the JDI worker will set the correct values for local variables while the thread is blocked at `resumePoint()` in the deepest frame. When the deepest frame eventually returns through the chain, each frame will have correct locals.

**Critical consideration: operand stack at the jump target.** The verifier requires the stack shape at `BEFORE_INVOKE_N` to match between the stub's goto and the normal code path. The stub must push the same types onto the stack. For the invoke arguments, the stub can push defaults (null/0) — the receiver and args are consumed by the invoke and the return value takes their place. But there may be items on the stack BELOW the invoke's arguments (sub-stack items). The existing `invokeSubStacks` map tracks these and the stub already pushes sub-stack defaults. This logic should carry over.

**However:** There's a subtle difference. Currently the stub pushes dummy args explicitly. With the new approach, the stub jumps to `BEFORE_INVOKE_N` where the original code's operand stack has the real args pushed by the original bytecode. But the stub is jumping from the prologue — the original arg-pushing code hasn't executed. So the stub still needs to push the right number and types of values to match the stack shape at `BEFORE_INVOKE_N`.

The stack at `BEFORE_INVOKE_N` (coming from original code) is: `[sub-stack items..., receiver, arg0, arg1, ...]`. The stub needs to replicate this: push sub-stack defaults, push null/0 receiver, push null/0 args. This is essentially what the stub does today, just jumping to a different label.

#### 3. Change Deepest Frame Resume Stub

The deepest frame stub currently:
1. Calls `resumePoint()` (blocks)
2. Initializes locals
3. Arms `localsAwait`
4. Pushes sub-stack + dummy return value
5. Jumps to `POST_INVOKE_N`

New behavior:
1. Initializes non-parameter locals to defaults (for verifier)
2. Calls `resumePoint()` (blocks — JDI sets ALL locals in ALL frames here)
3. Pushes sub-stack + dummy return value (or JDI could set these too, but defaults are fine since the return value from freeze() is void)
4. Jumps to `POST_INVOKE_N`

Note: `armLocalsAwait()` and the `localsReady()` mechanism are no longer needed.

#### 4. Remove localsReady() Injection

The `localsReady()` call after each invoke in the original code section is no longer needed. Remove it from `emitOriginalCode()`:

```java
// OLD:
target.visitMethodInsn(marker.opcode, ...);
target.visitLabel(postInvokeLabels[marker.index]);
target.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "localsReady", "()V", false);

// NEW:
target.visitLabel(beforeInvokeLabels[marker.index]);
target.visitMethodInsn(marker.opcode, ...);
target.visitLabel(postInvokeLabels[marker.index]);
// No localsReady() call
```

#### 5. Verifier Compatibility

The JVM verifier checks that at every jump target, the types of locals and stack entries are consistent across all paths that reach that target. The stub jumping to `BEFORE_INVOKE_N` must produce the same local types and stack shape as the normal code path reaching that label.

**Locals:** The stub initializes all non-parameter locals to type-appropriate defaults. The existing `emitLocalDefaults(invokeIndex)` method (which uses `perInvokeScopeMaps`) does exactly this — it knows which local types are in scope at each invoke position.

**Stack:** The stub pushes sub-stack defaults (via `pushSubStackDefaults`) plus the invoke's receiver and arguments (as defaults). This matches the stack shape at `BEFORE_INVOKE_N`.

### ThreadRestorer Changes

#### Eliminate Phase 2

The `runJdiRestore()` method currently has two phases. Phase 2 (the per-frame `localsReady()` loop) is removed entirely. Phase 1 changes to set ALL locals (not just parameters):

```java
// OLD Phase 1: set parameters only
setLocalsViaJdi(vm, tr, snapshot, restoredHeap, heapRestorer, false);

// NEW Phase 1: set ALL locals in ALL frames
setLocalsViaJdi(vm, tr, snapshot, restoredHeap, heapRestorer, true);
```

The `requireAllLocals` parameter (currently `false` for Phase 1, `true` for Phase 2) should be `true` for the single pass. If a local can't be set (out of scope), that's now a fatal error — it should never happen with the new stub architecture since all frames are in their original code sections.

#### Remove localsReady Coordination

Remove all code related to:
- `ReplayState.releaseLocalsReady()`
- `ReplayState.localsReady()` waiting
- The per-frame Phase 2 loop
- `waitForThreadAtMethod(vm, threadName, "localsReady", ...)`
- `isCorrectFrame()` polling
- `setLocalsForSingleFrame()`

#### Go-Latch Integration

With Phase 2 gone, the go-latch can be integrated into `resumePoint()`. After the JDI worker sets all locals and releases `resumePoint()`, the thread can immediately block on a second latch (the go-latch) before continuing into user code.

Options:
1. **Two-stage `resumePoint()`:** After the resume latch is released, check and block on a go-latch before returning
2. **Synthetic deep-end frame:** A method that calls `resumePoint()`, then blocks on the go-latch, then returns to the deepest user frame

Option 2 is cleaner — it keeps `resumePoint()` simple and puts the go-latch in a separate, filterable frame.

### ReplayState Changes

#### Remove localsReady Machinery

Remove or deprecate:
- `localsLatch` field
- `localsAwaitArmed` ThreadLocal
- `armLocalsAwait()` method
- `localsReady()` method
- `releaseLocalsReady()` method
- `LATCH_LOCK` (was primarily for localsReady latch coordination)
- `getLatchLock()` and `getLocalsLatch()` accessors

#### Simplify to Single Latch

Only `resumeLatch` (and optionally a go-latch) are needed:
- `resumeLatch`: JDI worker releases after setting all locals
- Go-latch: `RestoredThread.resume()` releases to let user code run

### RawBytecodeScanner / InvokeRegistry Impact

The `localsReady()` calls were injected into the original code section. `RawBytecodeScanner` filters out calls to `ReplayState` methods, so removing `localsReady()` calls shouldn't affect invoke index counting. However, verify that removing these calls doesn't change the bytecode offsets of original invoke instructions in a way that breaks the BCP → invoke index mapping.

Since `localsReady()` calls were inserted AFTER each invoke (at the post-invoke label), and the scanner filters them out anyway, removing them should not affect the mapping.

### DurableTransformer Impact

The `buildInvokeOffsetMaps()` method post-processes instrumented bytecode to find invoke offsets. Since the new architecture changes the structure of resume stubs (no more stub re-invokes), the "last N" rule for separating stub invokes from original invokes may need adjustment.

Currently: stubs contain their own invoke instructions (the re-invoke of the deeper method). The scanner sees these plus the original invokes. The "last N" entries are the original ones.

New architecture: stubs DON'T contain invoke instructions (they jump to the original invoke in the code section). So the scanner should see FEWER invokes in stubs. The `getOriginalInvokeCount()` from `PrologueInjector` still provides the authoritative count.

**This needs careful verification.** The stub may still contain calls to `ReplayState.advanceFrame()`, `ReplayState.resumePoint()`, `ReplayState.resolveReceiver()`, etc. These are filtered by the scanner (it excludes ReplayState calls). The stub's re-invoke of the user method is what gets removed. Since that was the only non-filtered invoke in the stub, the stub should now contribute zero invokes to the scanner's count. The "last N" rule still works — all N entries are from the original code section.

### Operand Stack Checker Impact

`OperandStackChecker` validates that the operand stack is empty at each invoke site (except the frozen frame). With the new architecture, the thread is at `BEFORE_INVOKE_N` labels during restore. The stack is NOT empty there — it has the invoke's arguments. But the checker runs at freeze time, not restore time, so this shouldn't be affected. Verify that the checker's analysis of the instrumented bytecode still works correctly without the `localsReady()` calls.

### BytecodeHasher Impact

`BytecodeHasher` hashes method bytecode for change detection between freeze and restore. The hash includes all instructions. Removing `localsReady()` calls and adding `BEFORE_INVOKE` labels changes the bytecode. **Snapshots frozen with the old architecture will not restore with the new architecture** (hash mismatch). This is expected — it's a breaking change in the instrumentation format. Document this in release notes.

## Testing Considerations

### Existing Tests

- **Unit tests** (ReplayStateTest, PrologueInjectorTest, etc.): Tests that use `ReplayState.activate()` (without JDI) won't be affected by the Phase 2 removal. Tests that use `activateWithLatch()` and `localsReady()` directly will need updating.
- **E2E tests**: All E2E tests should pass with the new architecture. The MultiCycleFreezeRestoreIT test (currently failing due to the go-latch issue) should work because Phase 2 is eliminated.
- **Snapshot compatibility**: Old snapshots will NOT be restorable with new code (bytecode hash mismatch). This is acceptable.

### New Tests to Add

- Test that all locals (including non-parameters) are correctly restored in a multi-frame stack
- Test re-freeze after restore (the multi-cycle scenario)
- Test the go-latch: restore with `resume=false`, verify thread is paused, call `resume()`, verify execution continues
- Test deep call chains (10+ frames) to ensure the new stubs work at depth

## Migration Notes

- This is a **breaking change** for serialized snapshots. Snapshots created with the old architecture cannot be restored with the new code.
- The `localsReady()` method in `ReplayState` can be kept as a no-op for backward compatibility with already-instrumented classes in the same JVM session, or removed entirely if a clean break is acceptable.
- The `armLocalsAwait()` method should similarly be kept as a no-op or removed.

## Summary of Files to Modify

| File | Change |
|------|--------|
| `PrologueInjector.java` | Major: new stub structure, BEFORE_INVOKE labels, remove localsReady injection |
| `ReplayState.java` | Remove localsReady/armLocalsAwait/localsLatch machinery |
| `ThreadRestorer.java` | Remove Phase 2 loop, set all locals in single pass |
| `DurableTransformer.java` | Verify invoke offset map building still works |
| `RawBytecodeScanner.java` | Verify invoke counting still works (likely no changes) |
| `OperandStackChecker.java` | Verify analysis still works (likely no changes) |
| `Durable.java` | No changes needed (API layer unchanged) |
| `RestoredThread.java` | No changes needed |
| Unit tests | Update tests that directly test localsReady/Phase 2 |
| E2E tests | Should pass without changes |

## Current State of the Codebase

The codebase is on branch `claude/document-java-threads-Ahyc3`. Recent refactoring commits include:

1. Shared `FrameFilter` for consistent infrastructure frame detection
2. Non-destructive `FreezeFlag` with `clearFrozen()`
3. Deduplicated `Durable.java` restore helpers
4. Latch timeouts in `ReplayState` (5 min default, configurable)
5. Dead code removal from `PrologueInjector`
6. Consolidated ConcurrentHashMap JDI walking into `JdiHelper`
7. `synchronized(Durable.class)` on freeze (replacing old `FREEZE_LOCK`)
8. New `RestoredThread` wrapper class and restore API rearchitecture (in progress)
9. Thread name uses UUID to prevent accumulation across freeze/restore cycles

The restore API has been rearchitected:
- Simple overloads (`restore(snapshot)`, `restore(path)`, etc.) return `void` — they restore, resume, and join automatically
- Advanced overload (`restore(snapshot, map, resume, awaitCompletion)`) returns `RestoredThread`
- `ThreadRestorer.restore()` starts the replay thread and runs JDI work synchronously (joins the JDI worker)
- `synchronized(Durable.class)` serializes all freeze/restore operations
- The go-latch in `RestoredThread` exists but the shallowest-frame issue prevents it from working in all cases — this architectural change fixes that

All 127 unit tests and 34/35 E2E tests pass. The one failing test (`MultiCycleFreezeRestoreIT`) fails because the current Phase 2 architecture times out waiting for the shallowest frame's `localsReady()` when the restored thread re-freezes. This architectural change eliminates that failure by removing Phase 2 entirely.
