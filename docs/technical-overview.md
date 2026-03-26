# Durable Java Threads: Technical Overview

## What It Does

Durable Java Threads captures a running Java thread's complete execution state — call stack, local variables, and reachable object graph — serializes it to a portable snapshot, and later restores it in the same or a different JVM. The thread resumes from the exact bytecode position where it was frozen.

This is conceptually similar to OS-level checkpoint/restore (CRIU) or JVM-level CRaC, but operates entirely at the application level using bytecode instrumentation and the Java Debug Interface (JDI).

---

## Architecture Overview

The system has six major subsystems:

1. **Bytecode Instrumentation** — Injects a replay prologue into every loaded method at class-load time
2. **Freeze** — Captures thread state via JDI while the thread is suspended
3. **Snapshot Model** — Serializable data structures representing the frozen state
4. **Heap Capture/Restore** — Walks and reconstructs the reachable object graph
5. **Restore** — Rebuilds the call stack via instrumented prologues, then sets local variables via JDI
6. **JDWP Port Discovery** — Finds and verifies the debug port for self-attaching JDI

---

## Detailed Walkthrough

### 1. Bytecode Instrumentation Pipeline

**Entry point:** `DurableAgent.premain()` → registers `DurableTransformer` as a `ClassFileTransformer`

**What gets instrumented:** All user classes. Excluded: JDK (`java/`, `javax/`, `jdk/`, `sun/`, `com/sun/`), shaded dependencies (`org/objectweb/asm/`, `org/objenesis/`), and library-internal classes (`Durable`, `ReplayState`, `ThreadFreezer`, `ThreadRestorer`, etc.).

**Instrumentation process (`DurableTransformer.transform()`):**

1. Parse original class bytecode with ASM `ClassReader`
2. Pipe through `PrologueInjector` (a `ClassVisitor`) which:
   - Buffers all method bytecode ops in a first pass
   - Counts invoke instructions (excluding `<init>` calls and `ReplayState` calls)
   - Simulates the operand stack to track sub-stack types at each invoke
   - On `visitEnd()`, emits the full replay prologue followed by the original code
3. Write output via `ClassWriter` with `COMPUTE_FRAMES` (ASM recomputes all stack map frames)
4. Store the instrumented bytecode in `InvokeRegistry.INSTRUMENTED_BYTECODE`
5. Post-process: `RawBytecodeScanner` scans the instrumented bytes to find exact bytecode offsets of all invoke instructions, then `InvokeRegistry` maps these to invoke indices

**Injected prologue structure (per method, single-pass architecture):**

```
METHOD ENTRY:
  if (!ReplayState.isReplayThread())       // single not-taken branch in normal execution
    goto ORIGINAL_CODE

  switch (ReplayState.currentResumeIndex()):
    case 0: goto RESUME_0
    case 1: goto RESUME_1
    ...

RESUME_N (deepest frame):
  emitLocalDefaults()                      // init non-param locals for verifier
  deactivate()                             // exit replay mode
  push sub-stack defaults + dummy args
  goto BEFORE_INVOKE_N                     // jump into original code — calls freeze()

RESUME_N (non-deepest frame):
  advanceFrame()
  emitLocalDefaults()
  push sub-stack defaults + dummy args
  goto BEFORE_INVOKE_N                     // jump into original code — calls deeper method

ORIGINAL_CODE:
  ... original bytecode ...
  BEFORE_INVOKE_0:
  invoke_0                                 // original invoke instruction
  POST_INVOKE_0:
  ...
```

**Key design decisions:**
- ALL frames (deepest and non-deepest) jump to `BEFORE_INVOKE` in the original code section, keeping every frame in its original code where local variables are naturally in scope
- The deepest frame's invoke is the `freeze()` call — during restore, `freeze()` detects restore mode via a `ThreadLocal` flag and blocks on a go-latch instead of actually freezing
- Sub-stack defaults are pushed so the JVM verifier sees type-compatible stack shapes at merge points
- Only parameter scopes are extended to method-wide; non-parameter locals keep their original compiler-assigned scope ranges (avoids slot-reuse conflicts)

### 2. Freeze Operation

**Entry point:** `Durable.freeze(handler)` → `ThreadFreezer.freeze(handler, namedObjects)`

**Thread choreography:**

1. Thread A (caller) spawns Thread B (worker), then blocks on `lock.wait(30s)`
2. Thread B acquires `FREEZE_LOCK` (serializes concurrent freeze/restore)
3. Thread B connects to JVM via JDI (`JdiHelper.connect()`)
4. Thread B finds Thread A in JDI (`JdiHelper.findThread()`)
5. Thread B drains pending JDI events (prevents stale resume events from prior cycles)
6. Thread B double-suspends Thread A (suspend count=2, resilient against one spurious resume)
7. Thread B captures the snapshot (see below)
8. Thread B calls the user's handler with the snapshot (while Thread A is still suspended)
9. Thread B double-resumes Thread A
10. Thread B sets `FreezeFlag`, interrupts Thread A
11. Thread A wakes from `lock.wait()` via `InterruptedException`, checks `FreezeFlag`, throws `ThreadFrozenError`

**Snapshot capture (`captureSnapshot()`):**
- Walks JDI stack frames bottom-to-top
- Filters out infrastructure frames (JDK, library internals)
- Rejects lambda frames (`$$Lambda`) — these can't be replayed
- For each user frame:
  - Computes bytecode hash (SHA-256 via `BytecodeHasher`)
  - Validates operand stack is empty at the call site (`OperandStackChecker`)
  - Maps BCP → invoke index via `InvokeRegistry`
  - Captures local variables (including implicit `this` for instance methods)
- Auto-names the `this` reference from the topmost user frame
- Named objects are resolved to JDI `uniqueID`s via `HeapObjectBridge`

**Heap capture (`JdiHeapWalker`):**
- Walks reachable objects from local variable references
- Special handling for JDK types: collections (ArrayList, HashMap, etc.), enums, immutable types (BigDecimal, UUID), strings, StringBuilder
- Each object gets a unique snapshot ID and is classified by `ObjectKind` (REGULAR, ARRAY, STRING, COLLECTION)
- Computes `classStructureHash` (SHA-256 of field layout) for change detection

### 3. Snapshot Data Model

```
ThreadSnapshot
├── capturedAt: Instant
├── threadName: String
├── frames: List<FrameSnapshot>           // bottom (0) to top (N-1)
│   ├── className, methodName, methodSignature
│   ├── bytecodeIndex (BCP at freeze point)
│   ├── invokeIndex (invoke instruction index for replay)
│   ├── bytecodeHash (SHA-256 of method bytecode)
│   └── locals: List<LocalVariable>
│       ├── slot, name, typeDescriptor
│       └── value: ObjectRef
│           ├── NullRef
│           ├── PrimitiveRef(Serializable)
│           └── HeapRef(long id) → ObjectSnapshot
└── heap: List<ObjectSnapshot>
    ├── id, className, kind, name
    ├── fields: Map<"Class.field", ObjectRef>
    ├── arrayElements: ObjectRef[]
    └── classStructureHash: byte[]
```

All snapshot classes implement `Serializable`. The entire snapshot is written via Java object serialization (default mechanism via `SnapshotFileWriter`).

### 4. Restore Operation

**Entry point:** `Durable.restore(snapshot)` → `ThreadRestorer.restore(snapshot, namedReplacements)`

**Preparation (main thread):**

1. Force-load all referenced classes (triggers `DurableTransformer`, populating `InvokeRegistry`)
2. Validate bytecode hashes (detect code changes between freeze and restore)
3. Validate class structure hashes (detect field layout changes)
4. Rebuild heap objects via `HeapRestorer` (Objenesis-based instantiation, two-pass: allocate then populate)
5. Populate `HeapObjectBridge` with all restored objects
6. Compute resume indices from snapshot
7. Pre-resolve receivers (`this`) for each frame from restored heap

**Single-pass restore:**

The restore creates two threads: a **replay thread** and a **JDI worker thread**.

1. Replay thread activates replay mode (`ReplayState.activateWithLatch()`) and sets `restoreInProgress = true`
2. Replay thread calls the bottom frame's method via reflection
3. Each frame's prologue detects replay mode, dispatches to the correct resume stub
4. Non-deepest stubs advance the frame, push dummy args, and jump to `BEFORE_INVOKE` — the original invoke fires, calling deeper methods whose prologues continue the chain
5. The deepest frame's stub deactivates replay mode, then jumps to `BEFORE_INVOKE` — the original `freeze()` call fires
6. `freeze()` detects `restoreInProgress`, blocks on the go-latch via `ReplayState.awaitGoLatch()`
7. All frames are now in their original code sections with all local variables naturally in scope
8. JDI worker waits for replay thread to reach `awaitGoLatch()` (polls thread status + stack)
9. JDI worker pre-loads all snapshot-referenced classes
10. JDI worker sets ALL local variables in ALL user frames in a single pass via JDI `setValue()`
11. JDI worker completes; `ThreadRestorer` captures the go-latch into a `RestoredThread`
12. When `RestoredThread.resume()` is called, the go-latch is counted down, `freeze()` returns normally, and user code continues from the freeze point

### 5. JDWP Port Discovery

The library self-attaches to its own JVM via JDI, which requires knowing the JDWP port.

**Resolution order (`JdiHelper.detectJdwpPort()`):**
1. Agent's cached port (detected eagerly at `premain()`)
2. System property `durable.jdwp.port`
3. Parsed from JVM command-line arguments
4. Platform-specific listening port enumeration (Linux: `/proc/net/tcp`, macOS: `lsof`, Windows: `netstat`) with nonce verification
5. Ephemeral port scan with JDI connect + nonce verification
6. Default fallback: port 44892

**Nonce verification:** At agent startup, a random nonce is set via JDI field write. When scanning ports, the library connects to candidate ports and reads back the nonce to confirm it's the same JVM.

### 6. Invoke Index Correctness

The critical invariant is that the invoke index computed at freeze time (BCP → index) must match the resume stub index at restore time. This is maintained by:

1. `PrologueInjector` assigns sequential indices to invokes during instrumentation
2. `RawBytecodeScanner` scans the instrumented bytecode to find exact BCPs
3. The "last N" rule: the last N invokes in the scanner output correspond to original code (stubs precede them in bytecode)
4. `BytecodeHasher` validates that method bytecode hasn't changed between freeze and restore

---

## Key Invariants and Constraints

1. **Classes must be compiled with `-g` (debug info)** — JDI needs `LocalVariable` tables to read/write locals
2. **No lambda frames in the call stack** — Lambda classes are generated dynamically and can't be replayed
3. **Operand stack must be empty at freeze-point invokes** (except the top frame) — The library can't capture/restore operand stack values
4. **JDWP must be enabled** — The JVM must be started with `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n`
5. **Java agent must be loaded** — `-javaagent:durable-threads.jar`
6. **Bytecode must not change between freeze and restore** — Validated via SHA-256 hashes
7. **Field layout must not change** — Validated via class structure hashes

---

## Thread Safety Model

- `FREEZE_LOCK` serializes all freeze/restore operations — no concurrent freezes
- `ReplayState` uses `ThreadLocal` for replay data but `static volatile` for latches and error state
- `LATCH_LOCK` protects latch creation/countdown race between replay thread and JDI worker
- `HeapObjectBridge` uses `ConcurrentHashMap` for thread-safe JDI access
- `InvokeRegistry` uses `ConcurrentHashMap` for concurrent class loading
- `FreezeFlag` uses `Collections.synchronizedSet` for thread ID tracking
- JDI double-suspend pattern (suspend count=2) guards against spurious resumes

---

## Dependencies

- **ASM 9.9.1** — Bytecode analysis and transformation (shaded to avoid classpath conflicts)
- **Objenesis 3.5** — Object instantiation without constructors (shaded)
- **JDI (`jdk.jdi`)** — Self-attaching debugger for thread inspection and local variable manipulation
- **Java 8+** — Source/target compatibility, with JDK 9+ module support
