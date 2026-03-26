# Design: Lambda Frame Support

**Status:** Future work
**Prerequisite:** v1.2.0 single-pass restore architecture

## Problem

When `Durable.freeze()` is called from within a lambda, or any frame on the frozen thread's call stack is a lambda-generated class (`$$Lambda`), the library throws `LambdaFrameException`. This is the most significant usability limitation — lambdas have been a core Java feature since Java 8 (2014), and every JDK version we support includes them.

The restriction exists because lambda class names are JVM-generated and non-deterministic (e.g., `Foo$$Lambda$27/0x00000001234`). At restore time, a class with that exact name doesn't exist, so the replay prologue can't re-enter the method.

**Goal:** Support lambdas in normal user code — callbacks, event handlers, `Runnable`/`Callable` implementations, method references. We are NOT targeting stream pipelines (`stream().filter().map().collect()`) or other deeply-chained functional APIs.

## Background: How Lambdas Work in the JVM

1. The Java compiler generates a **synthetic method** on the enclosing class for the lambda body:
   ```
   // Source: list.forEach(item -> process(item));
   // Compiled to: Foo.lambda$doWork$0(Item item) { process(item); }
   ```

2. At the call site, the compiler emits an `invokedynamic` instruction that calls `LambdaMetafactory.metafactory()` as its bootstrap method.

3. At runtime, the bootstrap method generates a lightweight class that:
   - Implements the target functional interface (e.g., `Consumer<Item>`)
   - Holds captured variables as fields
   - Delegates the interface method to the synthetic method on the enclosing class

4. The generated class name is non-deterministic: `Foo$$Lambda$N/0xHASH` where N is a counter and the hash varies across JVM runs.

**Key insight:** The lambda body's bytecode lives in the synthetic method on the enclosing class (`Foo.lambda$doWork$0`), which IS instrumented, IS deterministic, and CAN be replayed. The `$$Lambda` class is just a thin dispatch wrapper.

## What a Lambda Call Stack Looks Like

When `freeze()` is called from inside a lambda:

```
Thread stack (top to bottom):
  Durable.freeze()                          ← excluded (infrastructure)
  Foo.lambda$doWork$0(Item item)            ← synthetic method on REAL class (instrumented!)
  Foo$$Lambda$27/0x001.accept(Object)       ← lambda dispatch class (NOT instrumented)
  java.util.ArrayList.forEach(Consumer)     ← JDK infrastructure (filtered)
  Foo.doWork()                              ← user code (instrumented)
```

The frames, from the replay perspective:
- `Foo.doWork()` — user frame, has invoke for `forEach()`, can be replayed
- `ArrayList.forEach()` — JDK frame, filtered out (infrastructure)
- `Foo$$Lambda$27.accept()` — lambda frame, currently rejected
- `Foo.lambda$doWork$0()` — synthetic method, CAN be replayed (it's on `Foo.class`)

## Proposed Design

### Core Idea: Replace Lambda Frames with Synthetic Method Frames

At freeze time, when we encounter a `$$Lambda` frame, instead of rejecting it:

1. **Identify the delegate:** The lambda class's interface method (e.g., `accept`) simply calls the synthetic method on the enclosing class. We can determine this by:
   - Inspecting the lambda frame's callee (the next deeper frame) — it should be `Foo.lambda$doWork$0`
   - Or: reading the lambda class's bytecode via JDI to find the delegation target

2. **Capture the synthetic method's frame** instead of the lambda's frame. The synthetic method is on a real, instrumented class with a deterministic name. Its locals are the lambda parameters plus captured variables.

3. **Capture the captured variables** from the lambda instance's fields. These are the values that were closed over when the lambda was created.

4. **Store enough metadata** in the snapshot to reconstruct the call at restore time:
   - The enclosing class name (`Foo`)
   - The synthetic method name (`lambda$doWork$0`)
   - The synthetic method signature
   - The captured variable values (from the lambda instance's fields)
   - The functional interface (`Consumer`)

### Restore Strategy

At restore time, when we encounter a frame that was originally a lambda:

1. **Create a named wrapper class** via ASM that:
   - Implements the same functional interface (e.g., `Consumer<Item>`)
   - Has fields for all captured variables
   - Delegates to the same synthetic method on the enclosing class
   - Has a deterministic name: e.g., `Foo$$DurableLambda$doWork$0$<uuid>`

2. **Define the class** using `Unsafe.defineClass()` or a custom ClassLoader.

3. **Instantiate it** with the restored captured variables via Objenesis + field setting.

4. **Use it as the receiver** when replaying the frame above the lambda (e.g., `ArrayList.forEach` passes our wrapper to its iteration, which calls our wrapper's `accept`, which calls `Foo.lambda$doWork$0`).

Actually, this may be overcomplicating it. Since we filter JDK infrastructure frames, and the synthetic method IS on the enclosing class, we might be able to skip the lambda frame entirely:

### Simpler Alternative: Transparent Lambda Elision

The call stack has:
```
Foo.doWork()                     ← invoke: forEach(lambda)
  [JDK frames: forEach, etc.]   ← filtered
  [$$Lambda.accept()]            ← the lambda dispatch
  Foo.lambda$doWork$0()          ← the actual code
```

If we treat `$$Lambda.accept()` the same as JDK infrastructure (filter it out), the snapshot would contain:
```
Frame 0 (bottom): Foo.doWork()         — invoke index points to forEach() call
Frame 1 (top):    Foo.lambda$doWork$0  — invoke index points to freeze() call
```

At restore time:
- Replay enters `Foo.doWork()` via its prologue
- The resume stub for `doWork` jumps to `BEFORE_INVOKE` for the `forEach()` call
- `forEach()` needs a `Consumer` argument — the stub pushes a dummy/null
- `forEach(null)` would NPE

This is the problem: the JDK frame (`forEach`) calls the lambda, and we need a live lambda instance for it to work. We can't just skip it.

### Hybrid Approach: Synthetic Method Direct Entry

Instead of replaying through the JDK frame (which requires a live lambda), we can enter the synthetic method directly:

1. At restore time, the bottom-most frame that needs the lambda is `Foo.doWork()` at its `forEach()` invoke.

2. Instead of letting `forEach()` call the lambda, the resume stub for `Foo.doWork()` could bypass the `forEach()` entirely and directly call `Foo.lambda$doWork$0()` via reflection, similar to how `invokeBottomFrame()` works.

3. This means the resume stub doesn't jump to `BEFORE_INVOKE` for the `forEach()` call. Instead, it directly invokes the synthetic method, which has its own replay prologue that handles the deeper frames.

**Changes needed:**
- `PrologueInjector`: for invoke indices where the callee is a lambda frame, the resume stub would use `INVOKESTATIC` to call the synthetic method directly instead of jumping to `BEFORE_INVOKE`
- `ThreadRestorer`: would need to know which invoke indices have lambda intermediaries and set up the direct-call path
- `FrameSnapshot`: would need a flag or metadata indicating "this frame was entered via lambda — use direct entry"

### What Needs Capturing from the Lambda Instance

A lambda instance has:
- **Captured variables** as instance fields (named `arg$1`, `arg$2`, etc.)
- These can include the enclosing `this`, local variables from the enclosing scope, and effectively-final values

At freeze time, we already capture these through the heap walker (the lambda instance is reachable from `doWork()`'s locals). At restore time, we need them to reconstruct the lambda — or, with the direct-entry approach, we need them as arguments to the synthetic method (captured values are passed as parameters to static synthetic methods, or available via the enclosing `this`).

## Complexity Assessment

| Approach | Complexity | Reliability |
|---|---|---|
| Named wrapper class (ASM-generated) | High — must replicate LambdaMetafactory behavior | Medium — edge cases with method handles, serializable lambdas |
| Transparent lambda elision | Low — just filter the frame | Won't work — JDK frame needs live lambda |
| Direct synthetic method entry | Medium — modify resume stubs for lambda cases | High — synthetic method is real, instrumented code |

**Recommendation:** The direct synthetic method entry approach is the best balance. It avoids generating new classes at restore time, leverages the existing prologue infrastructure (the synthetic method is already instrumented), and only requires changes to how resume stubs handle lambda-intermediary invokes.

## Scope and Limitations

### In scope (normal lambda usage):
- `Runnable` / `Callable` lambdas passed to thread constructors or executors
- `Consumer` / `Function` callbacks passed to user methods
- Method references (`this::process`, `String::valueOf`)
- Lambdas capturing local variables from enclosing scope
- Lambdas used as event handlers or callbacks

### Out of scope (stream pipelines and chained functional APIs):
- `stream().filter().map().collect()` — deep JDK frame chains
- `CompletableFuture.thenApply().thenCompose()` — async chains
- These patterns create deeply nested JDK infrastructure frames that are impractical to replay

### Edge cases to investigate:
- Lambda capturing mutable state (reference to enclosing array/object)
- Nested lambdas (lambda inside lambda)
- Lambdas in constructors or static initializers
- Method references to private methods (accessibility)
- Serializable lambdas (`(Serializable & Runnable) () -> ...`)

## Implementation Order

1. **Freeze-side changes:** Stop rejecting `$$Lambda` frames. Instead, identify the synthetic method and capture the frame pair (lambda frame → synthetic method frame) with metadata.

2. **Snapshot model changes:** Add lambda metadata to `FrameSnapshot` (synthetic method name, captured variable mapping, functional interface).

3. **Restore-side changes:** Implement direct synthetic method entry in the resume stub. The stub calls the synthetic method directly instead of going through the JDK frame + lambda dispatch.

4. **Testing:** E2E tests with lambdas as `Runnable`, `Consumer`, `Function`, captured variables, method references.

5. **Documentation:** Update the limitations section in README.
