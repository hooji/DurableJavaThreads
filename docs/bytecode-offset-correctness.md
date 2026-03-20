# Bytecode Offset Computation: Correctness Analysis

This document provides a deep technical analysis of how DurableThreads computes
bytecode offsets during freeze and restore, and why the computation is correct in
all cases. It is intended for engineers evaluating the library's reliability, and
for contributors who need to understand the core mechanism.

## Overview

DurableThreads freezes a running thread by capturing its call stack, including
the exact execution position within each method. To restore the thread later
(potentially in a different JVM), the library must:

1. **Freeze**: Map the JVM's raw bytecode position (BCP) to a logical
   *invoke index* that identifies which method call the thread was executing.
2. **Restore**: Replay the call stack using instrumented bytecode that dispatches
   to the correct invoke index via a `tableswitch`.

The correctness of the entire system depends on the BCP-to-invoke-index mapping
being identical at freeze time and restore time. This document proves that it is.

## Architecture: The Four-Phase Pipeline

### Phase 1: Class Load (Instrumentation)

When a class is loaded, `DurableTransformer` instruments it:

1. **`PrologueInjector`** rewrites each method, prepending a replay prologue
   (resume stubs + skip-check code) before the original method body. It tracks
   the number of *original-code invoke instructions* in each method.

2. **`RawBytecodeScanner`** scans the *instrumented* bytecode to find the exact
   byte offset (BCP) of every user-invoke instruction.

3. **`DurableTransformer.buildInvokeOffsetMaps()`** combines these: it takes the
   scanner's full list of invoke BCPs and extracts the *last N* entries, where N
   is the number of original-code invokes reported by `PrologueInjector`. These
   are registered in `InvokeRegistry` as the BCP-to-index mapping.

### Phase 2: Freeze (BCP to Index)

When `Durable.freeze()` is called, `ThreadFreezer` uses JDI to inspect the
frozen thread's stack. For each frame, it reads the BCP from JDI and calls
`InvokeRegistry.getInvokeIndex(key, bcp)` to convert it to a logical invoke
index. This index is stored in the `ThreadSnapshot`.

### Phase 3: Snapshot Serialization

The snapshot (including invoke indices for each frame) is serialized and can be
transferred to a different JVM.

### Phase 4: Restore (Index to Execution)

`ThreadRestorer` loads the snapshot, activates replay mode with the stored invoke
indices, and starts a new thread. The instrumented prologue in each method reads
the invoke index and dispatches via `tableswitch` to the corresponding resume
stub, which rebuilds the call stack frame by frame.

## Correctness Argument

The system is correct if and only if:

> For every method M, the invoke index assigned to invoke instruction I at freeze
> time equals the invoke index that the restore prologue uses to dispatch to I's
> resume stub.

We prove this by establishing four invariants.

### Invariant 1: PrologueInjector and RawBytecodeScanner Use Identical Filtering

Both components must agree on what constitutes a "user invoke" (an invoke that
should be counted). They filter identically:

- **Included**: `INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKEINTERFACE`,
  `INVOKEDYNAMIC` — any invoke instruction in the original method body.
- **Excluded (by both)**:
  - Calls to `<init>` (constructors) — these are `INVOKESPECIAL`, which neither
    component counts.
  - Calls to `ReplayState.*` — the scanner explicitly filters these, and they
    only exist in injected prologue code (not in original code), so
    `PrologueInjector`'s count naturally excludes them.

This filtering is enforced by `RawBytecodeScanner.isUserInvoke()`, which checks:
```java
if (opcode == Opcodes.INVOKESPECIAL) return false;  // <init>, super calls
if (owner.equals("com/u1/durableThreads/ReplayState")) return false;
```

`PrologueInjector` counts invokes in the original code stream (its
`bufferedOps`), which by construction contains only the original method's invoke
instructions — never `INVOKESPECIAL` or `ReplayState` calls.

**Defensive check**: `DurableTransformer.buildInvokeOffsetMaps()` throws a
`RuntimeException` if the scanner finds fewer invokes than `PrologueInjector`
reports. This catches any filtering inconsistency at class load time rather than
at freeze/restore time.

### Invariant 2: The "Last N" Extraction Is Correct

The instrumented bytecode has this structure:

```
[Replay Prologue]
  - isReplayThread() check
  - tableswitch dispatch to resume stubs
  - Resume Stub 0: advanceFrame() / resumePoint(), re-invoke, set __skip, goto originalCode
  - Resume Stub 1: ...
  - ...
[Original Code] (label: originalCode)
  - Original method body with skip-checks inserted before each invoke
```

Resume stubs contain user-invoke instructions (the re-invocations), but these
always appear *before* the original code section in the bytecode. Therefore:

- The scanner finds all user invokes across the entire method: stub invokes
  first, then original-code invokes.
- The last N entries in the scanner's list correspond exactly to the original-code
  invokes, in their original order.
- `DurableTransformer` extracts `allOffsets.subList(allOffsets.size() - N, allOffsets.size())`.

This is correct because:

1. Resume stubs are emitted before the `originalCode` label (verified in
   `PrologueInjector.visitEnd()`).
2. Each resume stub contributes exactly one user invoke to the scanner's list
   (the re-invocation), *except* for `INVOKEDYNAMIC` stubs which call
   `resumePoint()` instead (a `ReplayState` call, filtered out). So
   `INVOKEDYNAMIC` stubs contribute zero scanner entries.
3. The original code section's invokes appear after all stub invokes in bytecode
   order, so they occupy the last N positions in the scanner's list.

### Invariant 3: BCP-to-Index Lookup Is Unambiguous

`InvokeRegistry.getInvokeIndex(key, bcp)` performs a reverse linear search
through the registered offsets for the method. It returns the index of the
*last* offset that is <= the given BCP.

This is unambiguous because consecutive user invokes in the original code are
always separated by at least 15 bytes of skip-check bytecode (the
`emitInvokeWithSkipCheck` code: ILOAD, LDC/BIPUSH, IF_ICMPLT, pop args,
ILOAD, LDC/BIPUSH, IF_ICMPNE, ICONST_M1, ISTORE, INVOKESTATIC localsReady,
unbox, GOTO). No two user invoke BCPs can be adjacent.

At freeze time, JDI reports the BCP of the `Durable.freeze()` call itself, which
is the invoke instruction in the original code section. This BCP exactly matches
one of the registered offsets, so the lookup returns the correct index.

### Invariant 4: RawBytecodeScanner Correctly Parses All JVM Opcodes

The scanner must correctly compute the byte offset of each instruction to find
invoke BCPs. This requires correct handling of:

- **Fixed-size opcodes** (1-5 bytes): All 200+ JVM opcodes have well-defined
  sizes. The scanner's `opcodeSize()` method handles every standard opcode.
- **`tableswitch`** (variable size): Requires 0-3 bytes of alignment padding
  after the opcode, followed by default offset (4 bytes), low (4 bytes),
  high (4 bytes), and `(high - low + 1) * 4` bytes of jump offsets. The padding
  is `(4 - ((bcp + 1) % 4)) % 4` bytes, matching the JVM specification.
- **`lookupswitch`** (variable size): Requires 0-3 bytes of alignment padding,
  followed by default offset (4 bytes), npairs (4 bytes), and `npairs * 8` bytes
  of match-offset pairs. Same padding formula.
- **`wide` prefix**: Doubles the operand size of load/store instructions (3 or 5
  bytes total). `iinc` with `wide` is 6 bytes.
- **`INVOKEINTERFACE`**: 5 bytes (opcode + 2-byte index + count + 0), unlike
  other invoke instructions which are 3 bytes.
- **`INVOKEDYNAMIC`**: 5 bytes (opcode + 2-byte index + 0 + 0).

The scanner has been verified to handle all of these correctly, including the
alignment-dependent padding for switch instructions. An unknown opcode causes a
`RuntimeException` rather than silent failure.

## Edge Cases and How They Are Handled

### Switch Instructions with Alignment Padding

Switch instructions (`tableswitch`, `lookupswitch`) use alignment-dependent
padding that varies based on the instruction's position in the bytecode. The
scanner computes padding as `(4 - ((bcp + 1) % 4)) % 4`, which is the standard
JVM formula. This has been tested with methods containing both dense (tableswitch)
and sparse (lookupswitch) switch statements interleaved with method calls.

### Mixed Invoke Types

Methods may contain `INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKEINTERFACE`, and
`INVOKEDYNAMIC` in any combination. Since `INVOKEINTERFACE` and `INVOKEDYNAMIC`
are 5 bytes while `INVOKEVIRTUAL` and `INVOKESTATIC` are 3 bytes, the scanner
must correctly handle mixed instruction sizes. This is validated by E2E tests
that exercise all four invoke types in a single method.

### Complex Control Flow

Branches, loops, try-catch-finally, and switch statements create complex control
flow graphs but do not affect the bytecode offset computation. The scanner
operates on linear bytecode order (not control flow), so the invoke index mapping
is independent of which path is taken at runtime.

### INVOKEDYNAMIC Resume Stubs

`INVOKEDYNAMIC` instructions (used by the JVM for string concatenation,
lambdas, etc.) cannot be re-invoked in resume stubs because they require
bootstrap method resolution. Instead, the resume stub calls `resumePoint()`
directly. Since `resumePoint()` is a `ReplayState` method (filtered by the
scanner), `INVOKEDYNAMIC` stubs contribute zero entries to the scanner's list.
This is correctly accounted for by the "last N" extraction.

### Nested and Recursive Calls

The invoke index is per-method, not per-call-stack. Each frame in the snapshot
independently stores its invoke index. Recursive calls create multiple frames
with the same method but potentially different invoke indices, which is handled
correctly because each frame's index is computed independently from its BCP.

## Per-Frame Local Variable Restoration

A related correctness concern is the restoration of local variables in
intermediate (non-top) stack frames. The system uses a two-phase JDI restore:

**Phase 1 (Resume Point)**: The replay thread rebuilds the call stack by
executing instrumented prologues that dispatch to resume stubs. When the deepest
frame reaches `resumePoint()`, the JDI worker deactivates replay mode and sets
method parameters in all frames. The thread then wakes, and each frame's skip-check
code re-executes the original code section, skipping all invoke instructions up
to the target invoke.

**Phase 2 (Locals Ready)**: At each frame's target invoke, `localsReady()` is
called, which blocks until the JDI worker sets ALL local variables (parameters
and non-parameters) for that frame. The latch is recreated after each wake,
so each successive frame blocks independently. Frames are processed in order
from deepest (top of stack) to shallowest (bottom of stack).

This ensures that every frame's local variables are fully restored before
execution continues past the freeze point, preserving the exact semantics of the
original thread.

## Defensive Measures

The system includes several defensive checks that detect inconsistencies early:

1. **Scanner-vs-injector count mismatch**: `DurableTransformer` throws at class
   load time if the scanner finds fewer invokes than `PrologueInjector` reports.

2. **Negative invoke index at freeze**: `ThreadFreezer` throws if
   `getInvokeIndex()` returns -1 for any user frame, indicating the BCP didn't
   match any registered offset.

3. **Unknown opcodes**: `RawBytecodeScanner` throws on encountering an unknown
   opcode rather than silently producing incorrect offsets.

4. **Bytecode hash validation**: At restore time, `ThreadRestorer` computes a
   hash of each method's bytecode and compares it to the hash stored in the
   snapshot. If the bytecode has changed (e.g., due to a code update), restoration
   fails with a clear error rather than silently producing incorrect behavior.

5. **Frame mismatch detection**: During per-frame local restoration, the JDI
   worker verifies that each frame matches the expected snapshot frame by class
   name and method name. A mismatch throws immediately.

## Conclusion

The bytecode offset computation is correct because:

1. The filtering criteria for "user invokes" are identical in `PrologueInjector`
   and `RawBytecodeScanner`, enforced by code structure and validated by a
   runtime count check.
2. The "last N" extraction correctly isolates original-code invokes from
   resume-stub invokes due to the guaranteed ordering in the instrumented bytecode.
3. The BCP-to-index lookup is unambiguous because skip-check bytecode always
   separates consecutive user invokes.
4. The raw bytecode scanner correctly handles all JVM opcodes, including
   variable-size instructions with alignment-dependent padding.

These properties hold for all valid Java bytecode, with no approximations or
heuristics. The system will either compute the correct offset or fail loudly
with a descriptive error message.
