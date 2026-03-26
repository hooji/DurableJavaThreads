# Implementation Guide: Single-Pass Restore via Direct-Jump Resume Stubs

## Starting Point

Branch: `claude/document-java-threads-Ahyc3`
Commit: `2e01402` (tagged locally as `new-arch-jumping-off-point`)

The codebase has the bones of the new single-pass architecture but needs one critical fix: the deepest frame still calls `resumePoint()` from its resume stub instead of jumping into original code. This means the deepest frame's non-parameter locals are out of scope when JDI tries to set them.

## Current Test Status

- 127 unit tests: 126 pass, 1 updated (`RawBytecodeScannerTest` expects 2 invokes instead of 4)
- E2E tests: most fail due to the deepest-frame scope issue described below

## Background

See `docs/direct-jump-resume-stubs.md` and `docs/slot-reuse-research-request.md` for detailed architectural context.

### The Key Insight

When restoring a thread, we need to set ALL local variables in ALL frames via JDI. JDI can only set variables that are "in scope" at the frame's current bytecode index (BCI). A variable is in scope when the BCI falls within the variable's range in the `LocalVariableTable`.

The **new architecture** solves this by having each frame's resume stub jump into the **original code section** (where the original invoke instruction is), rather than making its own call from the stub. This puts every frame at a BCI where its local variables are naturally in scope.

## What's Already Done

### PrologueInjector (`src/main/java/ai/jacc/durableThreads/PrologueInjector.java`)

**`emitFullPrologue()`** — Creates both `beforeInvokeLabels[]` and `postInvokeLabels[]`. Removed the `retValSlot` (no longer needed for return value boxing).

**`emitOriginalCode()`** — Emits `BEFORE_INVOKE` labels before each invoke and `POST_INVOKE` labels after. `localsReady()` calls have been removed (no longer needed — single-pass replaces Phase 2).

**`emitResumeStub()` for non-deepest frames** — Working correctly:
```
advanceFrame()
emitLocalDefaults()
pushSubStackDefaults()
pushDummyArguments()     // receiver + args for the invoke
GOTO beforeInvokeLabel   // jump to original code
```
The original invoke instruction fires, calling the deeper method. The frame is in its original code section with all locals in scope.

**`emitResumeStub()` for deepest frame** — THIS NEEDS FIXING. Currently:
```
emitLocalDefaults()
resumePoint()            // ← PROBLEM: called from stub, not original code
deactivate()
pushSubStackDefaults()
pushDummyReturnValue()
GOTO postInvokeLabel
```
The frame is at `resumePoint()` in the stub code. Non-parameter locals are NOT in scope.

**`emitLocalVariables()`** — Extended ALL locals to method-wide scope (to handle the scope issue). This causes slot-reuse conflicts in some methods. This will need to be reverted to params-only-wide once the deepest frame issue is fixed.

### ThreadRestorer (`src/main/java/ai/jacc/durableThreads/ThreadRestorer.java`)

**`restore()`** — Returns `RestoredThread` (not `Thread`). Starts the replay thread, runs JDI worker synchronously via `runJdiRestore()`, waits for it to finish, captures `resumeLatch` as the go-latch.

**`runJdiRestore()`** — Single-pass: waits for thread at `resumePoint()`, pre-loads classes, sets ALL locals in ALL frames in one pass, deactivates replay. Does NOT release `resumePoint()` latch (that's the go-latch for `RestoredThread.resume()`).

**`setLocalsViaJdi()`** — Called with `requireAllLocals=true`. Has old comment about "only require all locals for the top frame" at line 515 which needs updating — with the new architecture, all frames should have all locals in scope.

### Durable (`src/main/java/ai/jacc/durableThreads/Durable.java`)

New API fully in place:
- Simple void overloads: `restore(snapshot)`, `restore(path)`, etc. — restore, resume, join automatically
- Advanced overload: `restore(snapshot, map, boolean resume, boolean awaitCompletion)` — returns `RestoredThread`
- `synchronized(Durable.class)` on both freeze and restore

### ReplayState (`src/main/java/ai/jacc/durableThreads/ReplayState.java`)

Has `getResumeLatch()` accessor for the go-latch. The `localsReady()` method and its machinery (`localsLatch`, `LATCH_LOCK`, `armLocalsAwait`, `releaseLocalsReady`) still exist but are no longer called from the injected bytecode.

### RestoredThread (`src/main/java/ai/jacc/durableThreads/RestoredThread.java`)

Takes a `Thread` and `CountDownLatch` (go-latch). `resume()` counts down the latch. `thread()` returns the underlying thread.

## What Needs To Be Done

### 1. Fix the Deepest Frame (Critical)

The deepest frame's resume stub should NOT call `resumePoint()` from the stub. Instead, it should do the same thing as non-deepest frames: jump to `BEFORE_INVOKE` in the original code. The original invoke at that position is the call to `freeze()`.

**The key idea:** `freeze()` itself should detect that it's being called during a restore and act as the blocking point, instead of actually freezing. When all locals have been set and the go-latch is released, `freeze()` returns normally — which from the user code's perspective is exactly what happens after a successful restore.

**Changes needed in `PrologueInjector.emitResumeStub()`:**

The deepest frame case should become identical to the non-deepest case:
```
emitLocalDefaults()
deactivate()              // so freeze() doesn't see replay mode
pushSubStackDefaults()
pushDummyArguments()      // args for freeze() call
GOTO beforeInvokeLabel    // jump to original code — calls freeze()
```

Note: `deactivate()` must be called before the jump so that `freeze()` doesn't re-enter the replay prologue. But `freeze()` needs some other way to detect "I'm being called during a restore, not a real freeze."

### 2. Modify `freeze()` to Detect Restore Context

When `freeze()` is called during restore, it should block on the go-latch instead of actually freezing. Detection options:

- A thread-local flag set before the jump (e.g., `ReplayState.setRestoreMode(true)`)
- Check if the current thread's name matches a restore pattern
- A static flag set by `ThreadRestorer` before starting the replay

The simplest: add a `ThreadLocal<Boolean>` flag (e.g., `ReplayState.isRestoreInProgress()`) that's set to `true` before the replay thread starts. `freeze()` checks this flag. If true: block on the go-latch, then return normally. If false: proceed with actual freeze.

**Changes needed in `Durable.freeze()` or `ThreadFreezer.freeze()`:**
```java
if (ReplayState.isRestoreInProgress()) {
    // We're being called from a restored thread during replay.
    // Block on the go-latch until RestoredThread.resume() is called.
    ReplayState.awaitGoLatch();
    return; // return normally — user code continues after freeze()
}
// ... normal freeze logic ...
```

### 3. Implement the Go-Latch in ReplayState

The go-latch should be created during `activateWithLatch()` and accessible to both `freeze()` (for blocking) and `ThreadRestorer` (for capturing into `RestoredThread`).

Add to `ReplayState`:
- A `CountDownLatch goLatch` field (static volatile, like `resumeLatch`)
- `awaitGoLatch()` — blocks on the go-latch (called by `freeze()` during restore)
- `getGoLatch()` — returns the latch (called by `ThreadRestorer` to create `RestoredThread`)
- A `ThreadLocal<Boolean> restoreInProgress` flag

### 4. Update ThreadRestorer.runJdiRestore()

The JDI worker should now wait for the thread to be blocked inside `freeze()` (specifically at the `awaitGoLatch()` call), not at `resumePoint()`. At that point:

- All frames are in their original code sections
- The deepest frame is at the `freeze()` invoke (in original code)
- All local variables in all frames are in scope
- JDI sets ALL locals in ALL frames in a single pass

After setting locals, the JDI worker does NOT release the go-latch — that's for `RestoredThread.resume()`.

The `waitForThreadAtMethod` call should look for the thread blocked in `awaitGoLatch` or equivalent (inside `ReplayState`).

### 5. Revert `emitLocalVariables()` to Parameters-Only-Wide

Once the deepest frame is in its original code section, non-parameter locals will naturally be in scope at both `BEFORE_INVOKE` and `POST_INVOKE` BCIs (these are in the original code where the compiler's LocalVariableTable ranges apply). Only parameters need method-wide scope (to cover the prologue region where the `isReplayThread()` check happens).

Revert to the version that extends only parameter scopes to method-wide and keeps non-parameter scopes original.

### 6. Clean Up Unused Code

After the above changes, the following can be removed or simplified:

- `ReplayState.localsReady()`, `armLocalsAwait()`, `releaseLocalsReady()`, `localsLatch`, `LATCH_LOCK` — no longer used
- `ReplayState.resumePoint()`, `releaseResumePoint()`, `resumeLatch` — replaced by the go-latch in freeze()
- `PrologueInjector.boxReturnValue()`, `unboxReturnValue()` — dead code (stubs no longer box/unbox returns)
- `ThreadRestorer.setLocalsForSingleFrame()`, `isCorrectFrame()` — Phase 2 artifacts
- The `requireAllLocals` parameter distinction in `setLocalsViaJdi()` / `setFrameLocals()` — all frames should always have all locals set

### 7. Handle the `localsReady()` in emitOriginalCode

Currently `localsReady()` has been removed from `emitOriginalCode()`. This is correct for the new architecture. During normal (non-restore) execution, `localsReady()` was always a no-op anyway (gate not armed). Verify that removing it doesn't affect any test behavior.

## Key Invariants

When `Durable.restore(snapshot, map, false, false)` returns the `RestoredThread`:

1. ALL local variables in ALL user frames have been restored to correct values
2. The thread is blocked inside `freeze()` (at the `awaitGoLatch()` call)
3. ALL JDI/debugger operations are complete — no background threads, no pending JDI work
4. The thread is effectively a normal Java thread parked on a latch
5. When `resume()` is called, `freeze()` returns normally and user code continues
6. The thread can freely call `freeze()` again later (re-freeze) — it will be a real freeze

## Important Notes

- `freeze()` is excluded from instrumentation (it's in `EXCLUDED_CLASSES`), so it doesn't have a replay prologue. The restore-detection logic goes directly in `Durable.freeze()` or `ThreadFreezer.freeze()`.
- The `synchronized(Durable.class)` on freeze means the go-latch must be released OUTSIDE the synchronized block (which it is — `RestoredThread.resume()` is called by the user after `restore()` returns).
- The `emitLocalVariables()` method-wide scope extension for ALL locals causes slot-reuse conflicts. This MUST be reverted to params-only once the deepest frame fix is in place. The slot-reuse research doc (`docs/slot-reuse-research-request.md`) has details on the problem and potential fallback solutions if any scope issues remain.
- Thread names now use UUIDs to prevent accumulation across freeze/restore cycles.
- The `DurableTransformer.buildInvokeOffsetMaps()` "last N" rule still works — with no stub re-invokes, all scanner-found invokes are original-code invokes.
