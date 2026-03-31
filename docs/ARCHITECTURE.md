# Durable Java Threads -- Architecture Document

> Generated from code analysis of v1.4.1. This document describes the library's
> internal architecture, data flow, and design rationale purely from the source
> code.

---

## 1. Purpose

Durable Java Threads provides the ability to **freeze** a running Java thread
(capturing its entire call stack, local variables, and reachable object graph),
**serialize** the snapshot to persistent storage, and later **restore** the
thread in the same or a different JVM, resuming execution from the exact point
where it was frozen.

This is achieved on **stock JVMs** (no custom bytecode format, no modified JDK)
using three standard JVM mechanisms:

1. **Java Agent / `ClassFileTransformer`** -- bytecode instrumentation at class
   load time
2. **JDI (Java Debug Interface)** -- self-attach to read/write thread state
3. **Objenesis** -- constructor-less object instantiation for heap restoration

---

## 2. High-Level Architecture

```
                          FREEZE PATH
                          ==========

   User Thread                    Freeze Worker (Thread B)
   ============                   ==========================
   Durable.freeze(handler)
     |
     +-- spawns Thread B -------> performFreeze()
     |                              |
     +-- waits on lock.wait()       +-- JDI: connect to self
                                    +-- JDI: find & suspend Thread A
                                    +-- captureSnapshot()
                                    |     +-- walk frames (bottom-to-top)
                                    |     +-- capture locals via JDI
                                    |     +-- walk heap graph (JdiHeapWalker)
                                    |     +-- compute bytecode hashes
                                    |     +-- compute invoke indices
                                    +-- handler.accept(snapshot)
                                    +-- interrupt Thread A (ThreadFrozenError)

                          RESTORE PATH
                          ============

   Caller                Replay Thread              JDI Worker
   ======                =============              ==========
   Durable.restore(snap)
     |
     +-- validate snapshot
     +-- rebuild heap (HeapRestorer)
     +-- populate HeapObjectBridge
     +-- compute resume indices
     +-- start Replay Thread ----> activateWithLatch()
     +-- start JDI Worker -------> |                    runJdiRestore()
     |                              |                      |
     |                    replay prologues run:             |
     |                    bottomFrame()                     |
     |                      -> advanceFrame()               |
     |                        -> advanceFrame()             |
     |                          -> deactivate()             |
     |                            -> freeze()               |
     |                              -> awaitGoLatch() [BLOCKED]
     |                                                      |
     |                                          waitForThreadAtMethod()
     |                                          preloadSnapshotClasses()
     |                                          setLocalsViaJdi() [single pass]
     |                                              |
     +-- jdiWorker.join()                           |
     +-- return RestoredThread -----------------> (thread parked on goLatch)
                                                    |
   caller.resume() ------> goLatch.countDown() ---> freeze() returns
                                                    user code continues
```

---

## 3. Package Structure

```
ai.jacc.durableThreads
  |-- Durable                   Public API facade
  |-- DurableAgent              Java agent premain entry point
  |-- DurableTransformer        ClassFileTransformer (bytecode injection)
  |-- PrologueInjector          ASM ClassVisitor that injects replay prologues
  |-- PrologueEmitter           Emits prologue bytecode, resume stubs, labels
  |-- PrologueTypes             Shared data types for buffering pipeline
  |-- OperandStackSimulator     Tracks stack types for sub-stack defaults
  |-- ReplayState               Thread-local state driving replay prologues
  |-- ThreadFreezer             Freeze operation (JDI capture + termination)
  |-- ThreadRestorer            Restore operation (heap rebuild + replay)
  |-- JdiLocalSetter            Sets local variables via JDI in restored frames
  |-- JdiValueConverter         Converts snapshot refs to JDI Values
  |-- ReflectionHelpers         Method lookup, dummy args, receiver creation
  |-- RestoredThread            Handle to a restored-but-paused thread
  |-- SnapshotFileWriter        Consumer<ThreadSnapshot> that writes to file
  |-- SnapshotValidator         Bytecode + class structure hash validation

ai.jacc.durableThreads.internal
  |-- InvokeRegistry            Maps (class,method) -> invoke BCP offsets
  |-- RawBytecodeScanner        Scans raw class bytes for invoke positions
  |-- OperandStackChecker       Validates empty operand stack at freeze sites
  |-- BytecodeHasher            SHA-256 of method bytecode for integrity
  |-- ClassStructureHasher      SHA-256 of class field layouts
  |-- JdiHelper                 JDI connection management + JDWP port discovery
  |-- JdiHeapWalker             Captures object graph via JDI mirrors
  |-- HeapObjectBridge          Static map for passing objects to JDI
  |-- HeapRestorer              Rebuilds object graph from snapshot
  |-- ObjenesisHolder           Shared ObjenesisStd instance
  |-- FrameFilter               Classifies frames as infrastructure vs. user

ai.jacc.durableThreads.snapshot
  |-- ThreadSnapshot            Serializable top-level snapshot
  |-- FrameSnapshot             Single stack frame's state
  |-- ObjectSnapshot            Single heap object's state
  |-- LocalVariable             Name + type + value reference
  |-- ObjectRef (interface)     Reference into the snapshot
  |-- PrimitiveRef              Boxed primitive or String
  |-- HeapRef                   Points to ObjectSnapshot by ID
  |-- NullRef                   Null reference
  |-- ObjectKind                REGULAR | ARRAY | STRING | COLLECTION

ai.jacc.durableThreads.exception
  |-- AgentNotLoadedException   Agent jar not on -javaagent
  |-- ThreadFrozenError         Error thrown to terminate frozen thread
  |-- BytecodeMismatchException Code changed between freeze/restore
  |-- NonEmptyStackException    Operand stack not empty at freeze site
  |-- UncapturableTypeException JDK type that can't be round-tripped
```

---

## 4. Bytecode Instrumentation Pipeline

### 4.1 Injection

At class load time, `DurableTransformer` (registered by `DurableAgent.premain()`)
intercepts every class except JDK internals, ASM/Objenesis (shaded), and the
library's own package (`ai/jacc/durableThreads/` and all subpackages). Specific
subpackages can be whitelisted back in for instrumentation (e.g. `e2e/` for
E2E test programs).

For each eligible method (non-abstract, non-native, non-`<init>`, non-`<clinit>`),
`PrologueInjector` performs a **single buffering pass**:

1. **Buffer** all original bytecode instructions into a `List<Runnable>`
2. **Simulate** the operand stack (`OperandStackSimulator`) to track types at
   each invoke site
3. **Assign** sequential invoke indices to every non-constructor invoke instruction
4. On `visitEnd()`, **emit** the replay prologue via `PrologueEmitter`

### 4.2 Instrumented Method Layout

```
METHOD ENTRY:
  if (!ReplayState.isReplayThread()) goto ORIGINAL_CODE     // 1 branch
  switch (ReplayState.currentResumeIndex()):
    case 0: goto RESUME_0
    case 1: goto RESUME_1
    ...

RESUME_N (deepest frame):        RESUME_N (non-deepest):
  initLocalDefaults()               advanceFrame()
  deactivate()                      initLocalDefaults()
  pushSubStackDefaults              pushSubStackDefaults
  pushDummyArgs                     pushDummyArgs
  goto BEFORE_INVOKE_N              goto BEFORE_INVOKE_N

ORIGINAL_CODE:
  ... original bytecode ...
  BEFORE_INVOKE_0:
  original invoke 0
  POST_INVOKE_0:
  ...
```

Key design choices:

- **Direct-jump architecture**: resume stubs jump to `BEFORE_INVOKE` labels in
  the original code section rather than re-invoking methods. This means the
  thread executes the actual original invoke instruction, so JDI sees the thread
  at the correct BCP.
- **`COMPUTE_FRAMES`**: ASM recomputes all stack map frames, so the injector
  uses `SKIP_FRAMES` on read and delegates frame computation to the ClassWriter.
- **Sub-stack defaults**: When invoke N has items below its arguments on the
  operand stack, the resume stub pushes type-appropriate defaults (0/null) to
  satisfy the bytecode verifier at merge points.
- **Local defaults**: Resume stubs initialize non-parameter local variables
  with type-correct defaults so they're in scope at the jump target.

### 4.3 Invoke Registry

After instrumentation, `DurableTransformer.buildInvokeOffsetMaps()` uses
`RawBytecodeScanner` to find the exact bytecode positions (BCPs) of all invoke
instructions in the instrumented bytecode. These are stored in `InvokeRegistry`
as an ordered list of BCPs per method.

At freeze time, the BCP reported by JDI is looked up in the registry to determine
the invoke index for each frame.

---

## 5. Freeze Operation

### 5.1 Thread Coordination

`Durable.freeze()` acquires `synchronized(Durable.class)` and delegates to
`ThreadFreezer.freeze()`. The caller thread (A) spawns a daemon worker thread
(B) and enters `lock.wait()`. Thread B:

1. Connects to the JVM via JDI (`JdiHelper.getConnection()`)
2. Finds Thread A by name (`JdiHelper.findThread()`)
3. Suspends Thread A via JDI
4. Captures the snapshot
5. Resumes Thread A (from JDI suspension)
6. Calls `handler.accept(snapshot)` -- **outside** JDI suspension
7. Marks Thread A as frozen (`FreezeFlag.markFrozen()`)
8. Interrupts Thread A

Thread A catches the `InterruptedException`, checks `FreezeFlag.isFrozen()`, and
throws `ThreadFrozenError` to terminate.

### 5.2 Snapshot Capture

`captureSnapshot()` walks the JDI frame stack from bottom to top, filtering out:
- Infrastructure frames (JDK, library internals via `FrameFilter`)
- Lambda frames (`$$Lambda`) -- the next frame (synthetic method) is captured instead

For each user frame:
- Records class name, method name, method signature, BCP, invoke index
- Computes bytecode hash (`BytecodeHasher`)
- Validates operand stack is empty (`OperandStackChecker`)
- Captures all visible local variables via JDI
- Captures `this` explicitly (JDI doesn't include it in `method.variables()`)

The `JdiHeapWalker` recursively captures all objects reachable from local
variables, handling:
- **Primitives**: stored as `PrimitiveRef`
- **Strings**: stored directly as `PrimitiveRef` with string value
- **Arrays**: element-by-element capture
- **Collections** (ArrayList, HashMap, EnumSet, EnumMap, etc.): internal storage walked via JDI
- **Boxed primitives**: `value` field extracted
- **Immutable JDK types** (BigDecimal, UUID, java.time.*): captured via JDI
  field access, stored as string representation
- **Enums**: captured by constant name
- **Regular objects**: field-by-field via class hierarchy walk
- **Opaque JDK types**: fail-fast with `UncapturableTypeException`

### 5.3 Named Objects

Users can pass a `Map<String, Object>` of named objects. These are:
1. Stored in `HeapObjectBridge` with negative keys
2. Looked up via JDI to get their `uniqueID()`
3. Registered with `JdiHeapWalker` so the resulting `ObjectSnapshot` carries the name

The "this" reference of the calling frame is auto-named unless explicitly provided.

---

## 6. Restore Operation

### 6.1 Validation

Before restoring, `SnapshotValidator`:
1. Force-loads all classes referenced in the snapshot (triggers instrumentation)
2. Validates bytecode hashes match (detects code changes)
3. Validates class structure hashes match (detects field layout changes)

### 6.2 Heap Reconstruction

`HeapRestorer` rebuilds the object graph in two passes:
1. **Allocate**: Create all objects without setting fields (Objenesis for regular
   objects, constructors for strings/boxed types, `Array.newInstance()` for arrays)
2. **Populate**: Set fields, array elements, and collection contents

Named objects with replacements use the live replacement object instead of
creating a new one.

### 6.3 Call Stack Replay

The replay thread:
1. Activates `ReplayState` with resume indices and a go-latch
2. Sets `restoreInProgress = true`
3. Reflectively invokes the bottom frame's method (`ReflectionHelpers.invokeBottomFrame()`)
4. Each frame's instrumented prologue checks `isReplayThread()`, reads
   `currentResumeIndex()`, and jumps to the appropriate resume stub
5. Non-deepest stubs call `advanceFrame()` and jump to `BEFORE_INVOKE_N`,
   which executes the original invoke, entering the next method
6. The deepest stub calls `deactivate()` and jumps to `BEFORE_INVOKE_N`,
   which calls `Durable.freeze()` -- detected as restore-in-progress, blocks
   on the go-latch

### 6.4 JDI Local Variable Setting (Single-Pass)

The JDI worker:
1. Waits for the replay thread to reach `awaitGoLatch()` by polling thread status
   and verifying stack frames
2. Pre-loads all classes referenced by snapshot local variable types
3. Suspends the thread and sets ALL locals in ALL frames in a single pass

Frame matching: JDI frames (top-to-bottom) are matched against snapshot frames
(bottom-to-top) by class name and method name. Infrastructure frames are skipped.

Object references are pinned via `disableCollection()` during `setValue()` to
prevent GC from collecting them between resolution and assignment.

### 6.5 Resume

`RestoredThread.resume()` counts down the go-latch, causing `awaitGoLatch()` to
return. `freeze()` returns normally. User code continues from the freeze point
with all local variables restored.

---

## 7. JDWP Port Discovery

The library needs to connect to its own JVM via JDI/JDWP. Port discovery
follows this resolution order:

1. Cached port from agent startup
2. System property `durable.jdwp.port`
3. Parsed from JVM command-line arguments (`-agentlib:jdwp...address=...`)
4. Platform-specific listening port enumeration (Linux: `/proc/net/tcp`,
   macOS: `lsof`, Windows: `netstat`) with nonce verification
5. Ephemeral port scanning near a probe port, with nonce verification
6. Default: port 44892

Nonce verification: `DurableAgent.jdwpDiscoveryNonce` is set at premain to a
random UUID. Port candidates are verified by connecting via JDI and reading this
field to confirm the connection belongs to THIS JVM.

---

## 8. Thread Safety Model

All freeze and restore operations are serialized via `synchronized(Durable.class)`.
Only one freeze or restore can be in progress at a time. This is a fundamental
constraint of the architecture:

- JDI self-attach provides a single debugger connection
- `ReplayState` uses static fields (`goLatch`, `restoreError`) that are protected
  by this serialization
- `HeapObjectBridge` is shared mutable state cleared between operations

Within a single operation, thread coordination uses:
- `lock.wait()/notifyAll()` for freeze worker completion
- `CountDownLatch` for the restore go-latch
- `volatile` fields for cross-thread visibility of JDI connection, JDWP port,
  and agent load state

The `FreezeFlag` uses an `IdentityHashMap`-backed `synchronizedSet` of `Thread`
references, avoiding thread ID reuse issues and ensuring GC eligibility once
the frozen thread terminates.

---

## 8.1 Configurable Timeouts

All key timeouts are configurable via system properties:

| Property | Default | Location | Purpose |
|----------|---------|----------|---------|
| `durable.freeze.timeout.ms` | 30,000 ms | ThreadFreezer | Caller thread wait for freeze worker |
| `durable.jdi.wait.timeout.ms` | 30,000 ms | ThreadRestorer | JDI worker wait for replay thread |
| `durable.restore.timeout.seconds` | 300 s | ReplayState | Go-latch wait for `RestoredThread.resume()` |

---

## 9. Serialization Format

The snapshot is a standard Java `Serializable` object graph:

```
ThreadSnapshot
  +-- Instant capturedAt
  +-- String threadName
  +-- List<FrameSnapshot> frames          (bottom to top)
  |     +-- className, methodName, methodSignature
  |     +-- bytecodeIndex, invokeIndex
  |     +-- byte[] bytecodeHash
  |     +-- List<LocalVariable> locals
  |     +-- String lambdaBridgeInterface  (nullable)
  +-- List<ObjectSnapshot> heap
        +-- long id
        +-- String className
        +-- ObjectKind kind
        +-- Map<String, ObjectRef> fields
        +-- ObjectRef[] arrayElements
        +-- byte[] classStructureHash
        +-- String name (nullable)
```

`ObjectRef` is a sealed hierarchy: `PrimitiveRef`, `HeapRef`, `NullRef`.

---

## 10. Build & Packaging

- **Maven** with Java 8 source/target compatibility
- **ASM 9.9.1** for bytecode manipulation (shaded to avoid classpath conflicts)
- **Objenesis 3.5** for constructor-less instantiation (shaded)
- **JUnit 5** for testing
- **Maven Shade Plugin** relocates ASM and Objenesis into
  `ai.jacc.durableThreads.shaded.*`
- Manifest declares `Premain-Class: ai.jacc.durableThreads.DurableAgent`
- JDK 8 profile adds `tools.jar`; JDK 9+ profile adds module flags for `jdk.jdi`
  and `jdk.attach`

---

## 11. Error Handling Strategy

The library follows a **fail-fast** philosophy:

| Condition | Exception | When |
|-----------|-----------|------|
| Agent not loaded | `AgentNotLoadedException` | freeze/restore |
| Code changed between freeze/restore | `BytecodeMismatchException` | restore |
| Non-empty operand stack at freeze site | `NonEmptyStackException` | freeze |
| Uncapturable JDK type in heap | `UncapturableTypeException` | freeze |
| Thread termination after freeze | `ThreadFrozenError` (Error) | freeze |
| 0 user frames captured | `RuntimeException` | freeze |
| Invoke index lookup failure | `RuntimeException` | freeze |
| Missing debug info (-g) | `RuntimeException` | freeze/restore |
| JDI connection failure | `RuntimeException` | freeze/restore |
| Restore timeout | `RuntimeException` | restore |

`ThreadFrozenError` extends `Error` (not `Exception`) to bypass catch-all
exception handlers in user code.
