# Design: Lambda Frame Support

**Status:** Implemented (v1.4.0)
**Prerequisite:** Single-pass restore architecture (implemented)

## Current State

Lambda support is implemented. The library uses a **lambda bridge proxy**
approach when a `$$Lambda` frame appears in the call stack:

- **Freeze:** `$$Lambda` frames are skipped (not captured). The functional
  interface implemented by the lambda is detected and stored as
  `lambdaBridgeInterface` in the next deeper frame's `FrameSnapshot`.
- **Restore:** A `java.lang.reflect.Proxy` implementing the functional interface
  is created. The proxy delegates to the synthetic method on the enclosing class,
  whose replay prologue handles the rest.

See `ThreadFreezer.isLambdaFrame()`, `ThreadFreezer.detectLambdaInterface()`,
`ThreadRestorer.createLambdaBridgeProxy()`, and `ThreadRestorer.computeFrameReceivers()`.

### What Works
- Lambdas as `Runnable`, `Consumer`, `Function`, etc. passed to user methods
- Lambdas capturing variables from the enclosing scope
- Method references (`this::process`, `ClassName::method`)
- Both static and instance synthetic methods

### Known Limitations
- Stream pipelines and deeply-chained functional APIs remain unsupported
- The proxy receiver may not match the exact type expected by some frameworks
- Captured variable values in static lambdas are passed as leading parameters
  to the synthetic method — the proxy pads dummy args, relying on JDI to set
  the correct values during the single-pass restore

## Original Problem

When `Durable.freeze()` is called from within a lambda, the call stack contains
a `$$Lambda` frame whose class name is JVM-generated and non-deterministic.
At restore time, a class with that exact name doesn't exist, so the replay
prologue can't re-enter the method.

**Goal:** Support lambdas in normal user code — callbacks, event handlers,
`Runnable`/`Callable` implementations, method references. We are NOT targeting
stream pipelines (`stream().filter().map().collect()`) or other deeply-chained
functional APIs.

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

## Implemented Design: Lambda Bridge Proxy

The implemented approach uses **lambda elision + dynamic proxy** — a variant of
the "Hybrid Approach" originally discussed below.

### Freeze Side (ThreadFreezer)

1. When a `$$Lambda` frame is encountered, it is **skipped** (not captured).
2. The functional interface implemented by the lambda class is detected via
   `detectLambdaInterface()` — inspects `ClassType.interfaces()` to find the
   first non-marker interface.
3. The interface name is stored as `pendingLambdaBridgeInterface` and attached
   to the **next captured frame** (the synthetic method on the enclosing class)
   as `FrameSnapshot.lambdaBridgeInterface`.

### Restore Side (ThreadRestorer)

1. `computeFrameReceivers()` checks each frame for `lambdaBridgeInterface`.
2. If present, `createLambdaBridgeProxy()` creates a `java.lang.reflect.Proxy`
   that implements the functional interface and delegates to the synthetic method.
3. The proxy handles both static and instance synthetic methods:
   - **Static** (non-capturing or local-capturing lambda): invokes with padded
     args (captured vars are leading parameters, set by JDI later).
   - **Instance** (this-capturing lambda): invokes on a dummy receiver instance
     (the real `this` is set by JDI).
4. The proxy is used as the receiver for the caller frame's resume stub, which
   pushes it via `resolveReceiver()` when jumping to `BEFORE_INVOKE`.

### Why This Works

The caller frame's resume stub jumps to `BEFORE_INVOKE` for the original invoke
(e.g., `forEach(consumer)`). The proxy satisfies the interface type expected by
the invoke. When the JDK method calls the proxy's interface method, the proxy
delegates to the synthetic method, whose replay prologue takes over. The
synthetic method's locals (including captured variables) are set by JDI in the
single-pass restore.

### Original Design Alternatives Considered

Three approaches were evaluated during design:

| Approach | Complexity | Outcome |
|---|---|---|
| Named wrapper class (ASM-generated) | High | Rejected — too complex |
| Transparent lambda elision (skip + null) | Low | Rejected — NPE on JDK frame |
| **Dynamic proxy bridge (implemented)** | Medium | Chosen — reliable, no class generation |

## Scope and Limitations

### Supported (implemented):
- `Runnable` / `Callable` lambdas passed to user methods
- `Consumer` / `Function` callbacks
- Method references (`this::process`, `ClassName::method`)
- Lambdas capturing local variables from enclosing scope
- Both static and instance synthetic methods

### Not supported:
- `stream().filter().map().collect()` — deep JDK frame chains
- `CompletableFuture.thenApply().thenCompose()` — async chains
- These patterns create deeply nested JDK infrastructure frames that are
  impractical to replay

### Known edge cases and potential issues:
- **Nested lambdas** (lambda inside lambda) — should work if each level has a
  separate synthetic method, but not comprehensively tested.
- **Proxy type mismatch** — if the caller's invoke expects an exact class type
  rather than the interface, the Proxy may fail. In practice, functional
  interfaces are always invoked via the interface type.
- **Static lambda arg padding** — `createLambdaBridgeProxy()` pads args for
  static synthetic methods (captured vars are leading params). If the arg count
  calculation is wrong, the invoke fails at replay time.
- **Lambda in the bottom frame** — if the bottom-most user frame is a synthetic
  lambda method, `invokeBottomFrame()` calls it directly via reflection. The
  lambda bridge proxy is only used for caller→callee transitions.
- **Serializable lambdas** (`(Serializable & Runnable) () -> ...`) — the
  `detectLambdaInterface()` method skips `Serializable`, so it should find the
  functional interface correctly. Not explicitly tested.

## E2E Test Coverage (v1.4.0)

Lambda support is exercised by multiple E2E integration tests:

- `LambdaFreezeIT` — comprehensive lambda freeze/restore integration test
- `LambdaCallbackFreezeProgram` — lambda callbacks (Consumer, Function)
- `LambdaRunnableFreezeProgram` — Runnable lambdas
- `LambdaCapturedVarsFreezeProgram` — lambdas capturing local variables
- `MethodRefFreezeProgram` — method references (`this::method`, `ClassName::method`)

## Future Work

1. **Nested lambda testing** — nested lambdas (lambda inside lambda) should work
   but are not comprehensively tested in isolation.
2. **Stream pipeline support** — would require a fundamentally different
   approach (e.g., capturing the stream's spliterator state). Low priority.
3. **Proxy robustness** — investigate whether some JDK methods perform
   `instanceof` checks against the concrete lambda class rather than the
   functional interface, which would break the proxy approach.
