# Durable Java Threads — Architecture Deep Dive

**Version:** 1.4.1
**Date:** 2026-03-31

## 1. Overview

Durable Java Threads is a library that enables **freezing**, **serializing**, and **resuming** Java thread execution across JVM restarts. It captures the complete execution state of a running thread — call stack, local variables, and reachable object graph — into a serializable snapshot, then reconstructs that state in a new JVM to resume execution from exactly where it left off.

The library targets stock JVMs (no custom JVM modifications) and relies on three key JVM facilities:

1. **Java Debug Interface (JDI)** — self-attach to read/write thread state
2. **Java Instrumentation API** — bytecode transformation at class load time via a `-javaagent`
3. **ASM bytecode library** — inject replay prologues into every method

### Core Invariant

A frozen thread **never** continues executing in the original JVM after `Durable.freeze()`. The original thread is always terminated via `ThreadFrozenError`. Only restored threads resume past the freeze point.

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        User Code                                 │
│  Durable.freeze(handler)              Durable.restore(snapshot)  │
└──────────┬──────────────────────────────────────┬────────────────┘
           │                                      │
           ▼                                      ▼
┌──────────────────────┐              ┌──────────────────────────┐
│    ThreadFreezer     │              │     ThreadRestorer        │
│  (JDI capture)       │              │  (Heap rebuild + replay)  │
└──────┬───────────────┘              └──────┬───────────────────┘
       │                                     │
       ▼                                     ▼
┌──────────────────────┐              ┌──────────────────────────┐
│   JdiHeapWalker      │              │     HeapRestorer          │
│   (Object graph      │              │  (Objenesis + reflection) │
│    via JDI mirrors)  │              │                           │
└──────┬───────────────┘              └──────┬───────────────────┘
       │                                     │
       ▼                                     ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Snapshot Model                                │
│  ThreadSnapshot → FrameSnapshot[] → LocalVariable[]              │
│                 → ObjectSnapshot[] (heap)                         │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                    Instrumentation Layer                          │
│  DurableAgent → DurableTransformer → PrologueInjector            │
│  (javaagent)    (ClassFileTransformer)  (ASM bytecode injection) │
│                                                                   │
│  Supporting: InvokeRegistry, RawBytecodeScanner,                 │
│              OperandStackChecker, BytecodeHasher                 │
└──────────────────────────────────────────────────────────────────┘
```

## 3. Component Details

### 3.1 DurableAgent (Java Agent Entry Point)

**File:** `DurableAgent.java`

The agent is loaded via `-javaagent:durable-threads.jar` and serves as the bootstrap. At `premain()`:

1. Stores the `Instrumentation` instance for potential later use.
2. Registers `DurableTransformer` as a `ClassFileTransformer` — every class loaded after this point gets instrumented.
3. Generates a random UUID nonce (`jdwpDiscoveryNonce`) used to verify that JDI connections belong to this JVM.
4. Eagerly detects the JDWP port to avoid delay at first freeze/restore.

The agent also caches the detected JDWP port so that repeated freeze/restore operations don't re-scan ports.

### 3.2 DurableTransformer (Class Instrumentation)

**File:** `DurableTransformer.java`

A `ClassFileTransformer` that instruments every loaded class except:
- JDK classes (`java/`, `javax/`, `jdk/`, `sun/`, `com/sun/`)
- The library's own package (`ai/jacc/durableThreads/` and all subpackages) — excluded
  via a single prefix check to avoid recursion. Specific subpackages can be whitelisted
  back in via `WHITELISTED_PREFIXES` (e.g. `e2e/` for E2E test programs)
- Shaded dependencies (ASM, Objenesis)

For each eligible class:

1. Parses original bytecode with ASM `ClassReader`.
2. Uses `PrologueInjector` (a `ClassVisitor`) to inject replay prologues into every non-abstract, non-native, non-`<init>`, non-`<clinit>` method.
3. Writes instrumented bytecode using `ClassWriter` with `COMPUTE_FRAMES` (ASM recomputes all stack map frames).
4. Stores the instrumented bytecode in `InvokeRegistry` for hash computation.
5. Builds invoke offset maps by scanning the instrumented bytecode with `RawBytecodeScanner` and correlating with `PrologueInjector`'s invoke counts.

### 3.3 PrologueInjector (Bytecode Transformation)

**File:** `PrologueInjector.java`
**Size:** ~1300 lines — the largest and most complex class in the codebase.

This is the heart of the instrumentation. For each method it:

1. **Buffers** all original bytecode operations (does not emit directly).
2. **Simulates** the operand stack to track type categories at each invoke instruction.
3. **Assigns** sequential indices to every non-constructor invoke instruction (INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE, INVOKEDYNAMIC).
4. **Builds per-invoke scope maps** tracking which local variable slots are in scope at each invoke point.
5. **Emits** the replay prologue + resume stubs + original code with labels.

#### Instrumented Method Layout

```
METHOD ENTRY:
  if (!ReplayState.isReplayThread()) goto ORIGINAL_CODE
  switch (ReplayState.currentResumeIndex()):
    case 0: goto RESUME_0
    case 1: goto RESUME_1
    ...
    case N: goto RESUME_N

RESUME_0:  (resume stub for invoke 0)
RESUME_1:  (resume stub for invoke 1)
...
RESUME_N:  (resume stub for invoke N)

ORIGINAL_CODE:  (label)
  ... original bytecode with BEFORE_INVOKE_i and POST_INVOKE_i labels ...
```

#### Resume Stub Architecture (Direct-Jump / Single-Pass)

Each resume stub has two paths — deepest frame vs. non-deepest frame:

**Deepest frame (where freeze() was called):**
1. Initialize local variable slots to type-appropriate defaults.
2. Call `ReplayState.deactivate()` to exit replay mode.
3. Push sub-stack defaults (values that were below the invoke's args on the operand stack).
4. Push dummy arguments for the invoke.
5. `GOTO BEFORE_INVOKE_N` — jumps into original code, which calls `freeze()`.
6. `freeze()` detects `restoreInProgress` and blocks on the go-latch.
7. JDI sets all locals while thread is blocked.

**Non-deepest frame (intermediate call chain):**
1. Call `ReplayState.advanceFrame()`.
2. Initialize local variable slots to type-appropriate defaults.
3. Push sub-stack defaults + dummy arguments.
4. `GOTO BEFORE_INVOKE_N` — jumps into original code, which calls the deeper method.
5. Frame stays in its original code section with all locals in scope.

The key insight is that **all frames end up in their original code sections**, so all local variables are naturally in scope when JDI sets them.

### 3.4 ReplayState (Thread-Local Replay Control)

**File:** `ReplayState.java`

A thread-local state machine that controls replay behavior:

- **ThreadLocal `REPLAY`** — holds `ReplayData` (resume indices, frame receivers) during replay.
- **`isReplayThread()`** — checked at the top of every instrumented method's prologue. Returns false during normal execution (single not-taken branch).
- **`currentResumeIndex()`** — returns the invoke index for the current frame's tableswitch dispatch.
- **`advanceFrame()`** / **`isLastFrame()`** — frame depth tracking.
- **Go-latch** — the final blocking point; `RestoredThread.resume()` counts it down.
- **`restoreInProgress`** — flag so `freeze()` knows to block instead of actually freezing.
- **`resolveReceiver(String)`** — retrieves the pre-resolved "this" for the current frame, or creates a dummy instance via Objenesis.

### 3.5 ThreadFreezer (Freeze Operation)

**File:** `ThreadFreezer.java`

The freeze sequence:

1. **Caller (Thread A)** calls `Durable.freeze()` which calls `ThreadFreezer.freeze()`.
2. A **worker thread (Thread B)** is spawned. Thread A waits on a lock with a configurable timeout (default 30s, `durable.freeze.timeout.ms`).
3. Thread B connects to the JVM via JDI (`JdiHelper.getConnection()`).
4. Thread B finds Thread A in JDI (`JdiHelper.findThread()`).
5. Thread B suspends Thread A via JDI.
6. Thread B captures the snapshot via `captureSnapshot()`:
   - Walks JDI stack frames bottom-to-top (filtering out infrastructure frames).
   - Skips lambda frames (`$$Lambda`), capturing their functional interface for proxy creation.
   - For each user frame: computes invoke index, validates operand stack, captures locals.
   - Walks the object graph via `JdiHeapWalker`.
7. Thread B resumes Thread A (from JDI suspension).
8. Thread B calls the user's handler with the snapshot — **outside** JDI suspension, eliminating deadlock risk if the handler acquires locks held by the frozen thread.
9. Thread B marks Thread A as frozen via `FreezeFlag` and interrupts it.
10. Thread A wakes up, detects the freeze flag, and throws `ThreadFrozenError` to terminate.

#### Thread Termination Safety

The `blockForever()` method is a safety net: if the interrupt/flag mechanism somehow fails, the thread enters a sleep loop (100 iterations) then a busy spin. This guarantees a frozen thread **never** continues executing user code.

### 3.6 ThreadRestorer (Restore Operation)

**File:** `ThreadRestorer.java`

The restore sequence:

1. **Validate** the snapshot: bytecode hashes, class structure hashes (`SnapshotValidator`).
2. **Load** all classes referenced in the snapshot (triggers instrumentation).
3. **Rebuild the heap** via `HeapRestorer` (Objenesis for constructor-less instantiation).
4. **Populate `HeapObjectBridge`** with restored objects for JDI access.
5. **Compute resume indices** from the snapshot's stored invoke indices.
6. **Pre-resolve frame receivers** ("this" for each frame), including lambda bridge proxies.
7. **Create replay thread** with `ReplayState.activateWithLatch()` and `restoreInProgress = true`.
8. **Start the replay thread** — it replays the call stack via the instrumented prologues.
9. **Start JDI worker thread** (`runJdiRestore`) which:
   - Waits for the replay thread to reach `awaitGoLatch()` inside `freeze()` (configurable timeout via `durable.jdi.wait.timeout.ms`, default 30s).
   - Pre-loads all classes referenced by snapshot locals (`JdiLocalSetter.preloadSnapshotClasses()`).
   - Suspends the replay thread.
   - Sets ALL local variables in ALL frames in a single pass via JDI (`JdiLocalSetter.setLocalsViaJdi()`).
   - Resumes the replay thread.
10. **Return `RestoredThread`** — the thread is blocked on the go-latch with all state restored (go-latch timeout configurable via `durable.restore.timeout.seconds`, default 300s).

### 3.7 Snapshot Model

**Package:** `ai.jacc.durableThreads.snapshot`

All snapshot types implement `Serializable`:

| Class | Purpose |
|-------|---------|
| `ThreadSnapshot` | Top-level: timestamp, thread name, frames list, heap list |
| `FrameSnapshot` | Per-frame: class, method, signature, BCI, invoke index, bytecode hash, locals, lambda bridge interface |
| `LocalVariable` | Per-local: slot, name, type descriptor, value reference |
| `ObjectSnapshot` | Per-heap-object: ID, class name, kind (REGULAR/ARRAY/STRING/COLLECTION), fields, array elements, structure hash, name |
| `ObjectRef` | Interface: `NullRef`, `PrimitiveRef`, `HeapRef` |
| `ObjectKind` | Enum: REGULAR, ARRAY, STRING, COLLECTION |

### 3.8 Heap Walking and Restoration

**Freeze-side: `JdiHeapWalker`** — Walks the object graph through JDI mirrors. Handles:
- Primitives and strings as `PrimitiveRef`
- Object identity tracking (JDI uniqueID → snapshot ID)
- Boxed primitives (extract `value` field)
- Immutable JDK types (BigDecimal, UUID, java.time.*) via field reading
- Collections (ArrayList, HashMap, EnumSet, EnumMap, etc.) via internal structure walking
- Enums via constant name
- StringBuilder/StringBuffer via internal char/byte array reading
- Named objects for identity-preserving freeze/restore
- Opaque JDK types → fail-fast with `UncapturableTypeException`

**Restore-side: `HeapRestorer`** — Rebuilds objects from snapshots:
- Two-pass: allocate all objects first, then populate fields/elements.
- Uses Objenesis for constructor-less instantiation.
- Named object replacement (substitute live objects for frozen references).
- Special handling for boxed primitives, immutables, enums, collections, lambda placeholders.

### 3.9 HeapObjectBridge

**File:** `HeapObjectBridge.java`

A static `ConcurrentHashMap<String, Object>` that bridges the gap between the Java heap and JDI. Since JDI cannot directly create `ObjectReference` instances from local Java objects, restored objects are placed in this map. JDI reads the map via field access to obtain `ObjectReference` handles for `setValue()` calls.

### 3.10 JdiHelper (JDI Utilities)

**File:** `JdiHelper.java`

Handles JDI connection management:
- **Port detection**: Multi-strategy cascade — cached port → system property → command-line args → listening port enumeration (platform-specific: `/proc/net/tcp` on Linux, `lsof` on macOS, `netstat` on Windows) → ephemeral port scanning → default port (44892).
- **Nonce verification**: Each candidate port is verified by reading `DurableAgent.jdwpDiscoveryNonce` via JDI to confirm it belongs to this JVM.
- **Connection caching**: Maintains a `keepAliveVm` reference to prevent GC from closing the JDI socket.
- **Thread finding**: Matches by thread name (not JDI uniqueID, which is unrelated to Java thread ID).
- **ConcurrentHashMap walking**: Reads `HeapObjectBridge.objects` by walking the internal `table` array and node chains via JDI field access.

### 3.11 Supporting Internal Classes

| Class | Purpose |
|-------|---------|
| `InvokeRegistry` | Maps (class, method) → bytecode offsets of invoke instructions. Populated during instrumentation, queried during freeze to convert BCI → invoke index. |
| `RawBytecodeScanner` | Scans raw class file bytes for exact invoke instruction positions. Handles alignment-dependent tableswitch/lookupswitch padding. |
| `OperandStackChecker` | Validates that the operand stack is empty (excluding invoke args) at each freeze point. Uses ASM's `Analyzer` with raw BCI correlation. |
| `BytecodeHasher` | SHA-256 hash of method bytecode for integrity checking between freeze and restore. |
| `ClassStructureHasher` | SHA-256 hash of class field layout (names, types, hierarchy) to detect incompatible class changes. Dual implementations for JDI (freeze) and reflection (restore). |
| `FrameFilter` | Classifies stack frames as infrastructure (JDK, library) or user code. Shared between freeze and restore. |
| `ObjenesisHolder` | Shared `ObjenesisStd` singleton for constructor-less object creation. Used by HeapRestorer, ReflectionHelpers, and ReplayState. |

## 4. Key Design Decisions

### 4.1 JDI Self-Attach Architecture

The library connects to its own JVM via the Java Debug Interface. This provides:
- Read/write access to thread stack frames and local variables
- Object graph traversal through JDI mirrors
- No JVM modification required

**Trade-off:** Requires JDWP to be enabled (`-agentlib:jdwp=...`). Only one debugger can attach at a time, so all freeze/restore operations are serialized via `synchronized(Durable.class)`.

### 4.2 Universal Replay Prologue

Every non-excluded method is instrumented with a replay prologue. During normal execution, this adds one `ThreadLocal.get()` check and one not-taken branch per method call — minimal overhead.

### 4.3 Direct-Jump / Single-Pass Restore

Resume stubs jump to `BEFORE_INVOKE` labels in the original code rather than re-invoking methods from the stubs. This means:
- All frames end up in their original code sections.
- All local variables are naturally in scope.
- JDI can set ALL locals in ALL frames in one pass.
- No multi-phase latch protocol needed.

### 4.4 Invoke Index (Not BCI)

The snapshot stores invoke indices (0, 1, 2, ...) rather than raw bytecode indices. This decouples the snapshot from exact bytecode layout, making it resilient to minor recompilation changes that don't alter the method's invoke sequence.

### 4.5 Fail-Fast on Uncapturable Types

Rather than silently producing incorrect snapshots, `JdiHeapWalker` throws `UncapturableTypeException` for JDK types it can't handle (Optional, Pattern, unmodifiable collections, I/O types, etc.) with specific advice for each type.

## 5. Runtime Requirements

1. **JVM Flags:**
   - `-javaagent:durable-threads.jar`
   - `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:<port>`
2. **Debug Info:** Classes must be compiled with `-g` (debug info) for JDI to access local variables.
3. **Java 8+:** Supports Java 8 through 25+. JDK 8 requires `tools.jar` on classpath; JDK 9+ uses `--add-modules=jdk.jdi,jdk.attach`.

## 6. Serialization Format

Snapshots use standard Java serialization (`ObjectOutputStream`/`ObjectInputStream`). The `ThreadSnapshot` and all nested types implement `Serializable` with explicit `serialVersionUID` constants.

## 7. Thread Safety Model

- `Durable.freeze()` and `Durable.restore()` are serialized via `synchronized(Durable.class)`.
- `ReplayState` uses a volatile `CountDownLatch` (go-latch) and volatile `restoreInProgress`/`restoreError` fields, all protected by the `Durable.class` serialization.
- `InvokeRegistry` and `HeapObjectBridge` use `ConcurrentHashMap`.
- The `FreezeFlag` in `ThreadFreezer` uses an `IdentityHashMap`-backed `synchronizedSet` of `Thread` references (avoids thread ID reuse issues).

## 8. Error Handling Hierarchy

| Exception | When |
|-----------|------|
| `AgentNotLoadedException` | `freeze()`/`restore()` called without `-javaagent` |
| `ThreadFrozenError` (Error) | Internal: terminates the frozen thread |
| `NonEmptyParameterStackException` | Operand stack non-empty at a frame's call site |
| `BytecodeMismatchException` | Method bytecode or class structure changed between freeze and restore |
| `UncapturableTypeException` | Object graph contains a type that can't be captured/restored correctly |

## 9. Package Structure

```
ai.jacc.durableThreads/
├── Durable.java              — Public API (freeze/restore)
├── DurableAgent.java         — Java agent entry point
├── DurableTransformer.java   — ClassFileTransformer
├── PrologueInjector.java     — ASM bytecode injection
├── PrologueEmitter.java      — Emits replay prologue bytecode
├── PrologueTypes.java        — Shared data types for injection pipeline
├── OperandStackSimulator.java — Operand stack type tracking
├── ReplayState.java          — Thread-local replay state machine
├── ThreadFreezer.java        — Freeze implementation
├── ThreadRestorer.java       — Restore orchestration
├── JdiLocalSetter.java       — JDI local variable setting (frame matching, GC pinning)
├── JdiValueConverter.java    — Snapshot ObjectRef → JDI Value conversion
├── ReflectionHelpers.java    — Method lookup, dummy args, receiver creation
├── SnapshotValidator.java    — Bytecode/structure hash validation
├── RestoredThread.java       — Handle to a restored thread
├── SnapshotFileWriter.java   — File-based snapshot persistence
├── exception/
│   ├── AgentNotLoadedException.java
│   ├── BytecodeMismatchException.java
│   ├── NonEmptyParameterStackException.java
│   ├── ThreadFrozenError.java
│   └── UncapturableTypeException.java
├── internal/
│   ├── BytecodeHasher.java
│   ├── ClassStructureHasher.java
│   ├── FrameFilter.java
│   ├── HeapObjectBridge.java
│   ├── HeapRestorer.java
│   ├── InvokeRegistry.java
│   ├── JdiHeapWalker.java
│   ├── JdiHelper.java
│   ├── ObjenesisHolder.java
│   ├── OperandStackChecker.java
│   └── RawBytecodeScanner.java
└── snapshot/
    ├── FrameSnapshot.java
    ├── HeapRef.java
    ├── LocalVariable.java
    ├── NullRef.java
    ├── ObjectKind.java
    ├── ObjectRef.java
    ├── ObjectSnapshot.java
    ├── PrimitiveRef.java
    └── ThreadSnapshot.java
```
