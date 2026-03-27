# Durable Java Threads: Architecture and Technical Overview

**Version:** 1.3.1
**Date:** 2026-03-27

## 1. Introduction

Durable Java Threads is a library that enables freezing, serializing, and resuming Java thread execution across JVM restarts. A running thread can be suspended at a `Durable.freeze()` call, its complete execution state (call stack, local variables, and reachable object graph) can be captured into a portable snapshot, and that snapshot can later be deserialized in a new JVM to resume execution from the exact freeze point.

This is accomplished without any modifications to the JVM itself. The library operates entirely using three standard JVM facilities:

- **Java Instrumentation API** (`java.lang.instrument`) for bytecode transformation at class load time
- **Java Debug Interface (JDI)** for reading and writing thread state (stack frames, local variables) at runtime
- **ASM bytecode manipulation library** for injecting replay prologues into user code

### 1.1 Key Design Constraints

- **No JVM modification required:** Works on stock OpenJDK 8+
- **No special compiler required:** Standard `javac` with `-g` (debug info) flag
- **Empty operand stack requirement:** `freeze()` must be called when all frames in the call stack have an empty operand stack (no intermediate expression values) because JDI cannot read or write operand stack values
- **Single freeze/restore at a time:** All freeze and restore operations are serialized via `synchronized(Durable.class)` due to the JDI self-attach architecture's single-debugger constraint

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        User Code                                │
│   Durable.freeze(handler)  /  Durable.restore(snapshot)        │
└─────────────┬──────────────────────────────┬────────────────────┘
              │                              │
              ▼                              ▼
┌─────────────────────┐        ┌──────────────────────────┐
│    ThreadFreezer     │        │     ThreadRestorer        │
│  (capture thread)    │        │  (rebuild & resume)       │
└─────────┬───────────┘        └─────────┬────────────────┘
          │                              │
          ▼                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     JDI (Java Debug Interface)                  │
│  Self-attach via JDWP · Suspend/resume · Read/write locals     │
└─────────────────────────────────────────────────────────────────┘
          │                              │
          ▼                              ▼
┌─────────────────────┐        ┌──────────────────────────┐
│  JdiHeapWalker       │        │     HeapRestorer          │
│  (capture objects)   │        │  (rebuild objects)        │
└─────────┬───────────┘        └─────────┬────────────────┘
          │                              │
          ▼                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Snapshot Model                              │
│  ThreadSnapshot → FrameSnapshot[] → LocalVariable[]             │
│                 → ObjectSnapshot[] (heap)                        │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│              Bytecode Instrumentation (load time)                │
│  DurableAgent → DurableTransformer → PrologueInjector           │
│  Injects replay prologues into every user method                │
└─────────────────────────────────────────────────────────────────┘
```

## 3. Component Descriptions

### 3.1 Public API (`Durable.java`)

The sole entry point for users. Provides overloaded `freeze()` and `restore()` methods.

**Freeze overloads:**
- `freeze(Consumer<ThreadSnapshot> handler)` — freeze with a callback
- `freeze(String filePath)` / `freeze(Path path)` — freeze to a file
- `freeze(handler, Map<String, Object> namedObjects)` — freeze with named heap objects that can be replaced during restore

**Restore overloads:**
- `restore(ThreadSnapshot snapshot)` — restore, resume, and wait for completion
- `restore(snapshot, namedReplacements)` — restore with live object substitutions
- `restore(snapshot, namedReplacements, resume, awaitCompletion)` — full control over resumption

All operations are serialized via `synchronized(Durable.class)`.

### 3.2 Java Agent (`DurableAgent.java`, `DurableTransformer.java`)

**DurableAgent** is the `-javaagent` entry point. At `premain`:
1. Registers `DurableTransformer` as a `ClassFileTransformer`
2. Sets a UUID nonce for JDWP port discovery verification
3. Eagerly detects the JDWP port

**DurableTransformer** intercepts every class load and:
1. Filters out excluded classes (JDK, shaded deps, library internals)
2. Uses `PrologueInjector` to inject the replay prologue into every method
3. Stores the instrumented bytecode in `InvokeRegistry` for hash computation
4. Builds invoke offset maps (BCP → invoke index) via `RawBytecodeScanner`

### 3.3 Replay Prologue Injection (`PrologueInjector.java`)

This is the most complex component. It is an ASM `ClassVisitor` that transforms every non-abstract, non-native, non-constructor, non-clinit method to include a replay prologue. The injected structure is:

```
METHOD ENTRY:
  if (!ReplayState.isReplayThread()) goto ORIGINAL_CODE
  switch (ReplayState.currentResumeIndex()):
    case 0: goto RESUME_0
    case 1: goto RESUME_1
    ...

RESUME_N (deepest frame):
  emitLocalDefaults(N)       // initialize locals to type-correct defaults
  ReplayState.deactivate()   // exit replay mode
  pushSubStackDefaults       // fill operand stack to match normal path
  pushDummyArguments          // fill invoke args with defaults
  goto BEFORE_INVOKE_N       // jump into original code

RESUME_N (non-deepest frame):
  ReplayState.advanceFrame()
  emitLocalDefaults(N)
  pushSubStackDefaults
  pushDummyArguments
  goto BEFORE_INVOKE_N       // jump into original code → invokes deeper method

ORIGINAL_CODE:
  ... (original bytecode, with BEFORE_INVOKE_N / POST_INVOKE_N labels) ...
```

Key design decisions in the prologue:

- **Direct-jump architecture:** Resume stubs jump directly to `BEFORE_INVOKE` labels in the original code section rather than re-invoking methods. This puts all frames into their original code sections with all locals in scope, enabling single-pass JDI local variable setting.
- **Operand stack simulation:** During the buffering pass, PrologueInjector maintains a simulated operand stack to track the type of each stack entry. This allows resume stubs to push type-correct default values for the sub-stack (items below the invoke's arguments at each call site).
- **Per-invoke scope maps:** Tracks which local variable slots are in scope at each invoke point by walking buffered store instructions and LocalVariableTable entries. Resume stubs initialize only those slots with type-correct defaults.
- **Uninitialized reference handling:** Invokes whose sub-stack contains uninitialized references ('U' from `NEW` before `<init>`) get no-op stubs that jump to the original code start, since these can never be freeze points.

### 3.4 Replay State Machine (`ReplayState.java`)

Thread-local state that drives replay. During normal execution, `isReplayThread()` returns `false` (a single not-taken branch per method entry — near-zero overhead).

During replay, provides:
- `currentResumeIndex()` — the invoke index for the tableswitch dispatch
- `advanceFrame()` — move to the next deeper frame
- `isLastFrame()` — check if this is the deepest frame
- `resolveReceiver(className)` — get the pre-restored receiver for virtual calls

Latching protocol for synchronization with the JDI worker:
- **goLatch:** The replay thread blocks in `freeze()` via `awaitGoLatch()`. Released by `RestoredThread.resume()` to allow user code to continue.
- **restoreInProgress flag:** Tells `freeze()` whether the current call is a real freeze or a restore replay (in which case it should block on the go-latch instead of actually freezing).

Also contains boxing/unboxing helper methods that are deliberately placed in `ReplayState` so that `RawBytecodeScanner` filters them out (it excludes all ReplayState invokes from invoke index counting).

### 3.5 Thread Freezing (`ThreadFreezer.java`)

Implements the freeze operation:

1. **Thread A** (user's thread) enters `freeze()` and spawns **Thread B** (worker)
2. **Thread B** connects to the JVM via JDI self-attach
3. **Thread B** suspends Thread A (double-suspend for resilience against spurious resumes)
4. **Thread B** drains pending JDI events to clear stale resumes from prior cycles
5. **Thread B** captures the snapshot:
   - Walks the JDI stack frames bottom-to-top
   - Filters out infrastructure frames (JDK, library internals)
   - Skips lambda frames (captures the synthetic delegate instead)
   - For each user frame: captures local variables, computes invoke index, computes bytecode hash
   - Walks the reachable object graph via `JdiHeapWalker`
6. **Thread B** calls the user's handler with the snapshot
7. **Thread B** terminates Thread A by marking it frozen and interrupting it
8. **Thread A** catches the interrupt, checks `FreezeFlag`, and throws `ThreadFrozenError`

The freeze flag uses a `Collections.synchronizedSet` of thread IDs to coordinate the interrupt-based termination between the worker and caller threads.

### 3.6 Thread Restoration (`ThreadRestorer.java`)

The restore sequence:

1. **Validate snapshot:** Check frame count, force-load all referenced classes (triggers instrumentation), validate bytecode hashes, validate class structure hashes
2. **Rebuild heap:** `HeapRestorer` uses Objenesis to instantiate objects without constructors, then sets fields reflectively. Named objects can be substituted with live replacements.
3. **Populate HeapObjectBridge:** Store restored objects in a static `ConcurrentHashMap` so JDI can access them
4. **Compute replay state:** Extract invoke indices from the snapshot; pre-resolve receivers for each frame
5. **Create replay thread:** A new thread activates `ReplayState` with latches, sets `restoreInProgress`, then invokes the bottom frame via reflection
6. **Start JDI worker:** Connects via JDI, waits for the replay thread to reach `awaitGoLatch()`, suspends it, pre-loads referenced classes, sets ALL locals in ALL frames in a single pass, then cleans up
7. **Return `RestoredThread`:** The caller gets a handle with the thread parked on the go-latch. Calling `resume()` counts down the latch, `freeze()` returns normally, and user code continues.

### 3.7 JDI Infrastructure (`JdiHelper.java`)

Handles all JDI connection management:

**Port detection (resolution order):**
1. Agent's cached port (detected eagerly at startup)
2. System property `durable.jdwp.port`
3. Parsed from JVM command-line arguments
4. Platform-specific listening port enumeration (Linux: `/proc/net/tcp`, macOS: `lsof`, Windows: `netstat`) with nonce verification
5. Ephemeral port scan with nonce verification
6. Default port `44892`

**Connection management:**
- Cached connection reuse (`keepAliveVm` to prevent GC/disconnection)
- Lock-protected connect (`JDI_CONNECT_LOCK`)
- ConcurrentHashMap traversal utilities for HeapObjectBridge access via JDI

**Thread finding:**
- Matches by thread name (not by `uniqueID()`, which has no relation to Java thread IDs)

### 3.8 Heap Walking and Restoration

**JdiHeapWalker** (freeze time):
- Walks the object graph via JDI mirrors
- Handles: primitives, strings, arrays, boxed primitives, enums, immutable JDK types (BigDecimal, UUID, java.time.*, etc.), JDK collections (ArrayList, HashMap, HashSet, etc.), StringBuilder/StringBuffer, and regular user objects
- Computes class structure hashes for each captured object
- Supports named objects for identity-preserving round-trips

**HeapRestorer** (restore time):
- Two-pass allocation: first allocate all objects (Objenesis for regular, constructor for strings/collections), then populate fields/elements
- Named object substitution: live objects replace snapshot objects by name
- Handles: strings, arrays, boxed primitives, enums, immutable JDK types, collections, and regular objects

**HeapObjectBridge:**
- Static `ConcurrentHashMap<String, Object>` that JDI reads via field access to obtain `ObjectReference` handles for restored objects

### 3.9 Invoke Registry and Bytecode Scanning

**InvokeRegistry:**
- Maps `(class, method)` → ordered list of invoke instruction bytecode offsets
- Populated during instrumentation by `DurableTransformer`
- Used at freeze time to convert JDI's reported BCP to an invoke index
- Stores instrumented bytecode for hash computation

**RawBytecodeScanner:**
- Parses raw class file bytes to find exact bytecode positions of invoke instructions
- Resolves constant pool references to filter out ReplayState calls and `<init>` invokes
- Handles all variable-length instructions (tableswitch, lookupswitch, wide)

**BytecodeHasher:**
- Computes SHA-256 hashes of method bytecode (opcodes, operands, referenced names)
- Used to detect code changes between freeze and restore

**ClassStructureHasher:**
- Computes SHA-256 hashes of a class's field layout (names, types, hierarchy)
- Two implementations: JDI-based (freeze time) and reflection-based (restore time)
- Detects incompatible class structure changes

### 3.10 Snapshot Model

All snapshot classes are `Serializable` with explicit `serialVersionUID`:

- **ThreadSnapshot:** Timestamp, thread name, ordered list of frames (bottom to top), list of heap objects
- **FrameSnapshot:** Class name, method name, method signature, BCI, invoke index, bytecode hash, locals list, optional lambda bridge interface
- **ObjectSnapshot:** ID, class name, kind (REGULAR/ARRAY/STRING/COLLECTION), field map, array elements, class structure hash, optional name
- **LocalVariable:** Slot index, name, type descriptor, value (ObjectRef)
- **ObjectRef hierarchy:** `NullRef`, `PrimitiveRef` (boxed primitives + strings), `HeapRef` (ID referencing an ObjectSnapshot)

## 4. Lifecycle Flows

### 4.1 Freeze Flow

```
User Code              ThreadFreezer (worker)         JDI
---------              ----------------------         ---
freeze(handler) ──►    spawn worker thread    ──►    connect(port)
wait on lock           suspend target thread  ◄──    JDI suspend
                       capture stack frames
                       walk heap via JdiHeapWalker
                       call handler(snapshot)
                       mark frozen + interrupt ──►
throws ThreadFrozenError ◄──────────────────
```

### 4.2 Restore Flow

```
ThreadRestorer              Replay Thread           JDI Worker
--------------              -------------           ----------
validate snapshot
rebuild heap
compute replay state
create replay thread  ──►   activate ReplayState
                            invoke bottom frame
                            prologue dispatches:
                              - non-deepest: advanceFrame, goto BEFORE_INVOKE → calls deeper method
                              - deepest: deactivate, goto BEFORE_INVOKE → calls freeze()
                            freeze() detects restoreInProgress
                            blocks on goLatch
                                                      waitForThread at awaitGoLatch
                                                      suspend thread
                                                      preload classes
                                                      set ALL locals in ALL frames
                                                      clean up
return RestoredThread
                            (caller calls resume())
                            goLatch released
                            freeze() returns normally
                            user code continues...
```

## 5. Instrumentation Details

### 5.1 Classes Excluded from Instrumentation

- JDK: `java/`, `javax/`, `jdk/`, `sun/`, `com/sun/`
- Shaded deps: `org/objectweb/asm/`, `org/objenesis/`, `ai/jacc/durableThreads/shaded/`
- Library internals: `Durable`, `DurableAgent`, `DurableTransformer`, `PrologueInjector`, `ReplayState`, `ThreadFreezer`, `ThreadRestorer`, `SnapshotFileWriter`
- Internal subpackages: `internal/`, `snapshot/`, `exception/`
- IDE: `com/intellij/`

### 5.2 Methods Excluded from Instrumentation

- Abstract methods
- Native methods
- Static initializers (`<clinit>`)
- Constructors (`<init>`)

### 5.3 Normal Execution Overhead

During normal (non-replay) execution, every instrumented method entry executes:
```java
if (ReplayState.isReplayThread()) { ... }  // single ThreadLocal.get() == null check
```
This is a single `ThreadLocal.get()` returning `null`, followed by a not-taken branch — effectively zero overhead.

## 6. Build and Configuration

### 6.1 Build System

Maven-based, targeting Java 8+ (source/target 1.8). Key plugins:
- **maven-shade-plugin:** Relocates ASM and Objenesis to avoid classpath conflicts
- **maven-surefire-plugin:** Runs unit tests, excludes E2E tests
- **maven-failsafe-plugin:** Runs E2E integration tests (`**/*IT.java`)

### 6.2 JVM Arguments Required

```
-javaagent:durable-threads.jar
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:0
```

The JDWP port can be fixed (`address=*:5005`) or ephemeral (`address=*:0`). For ephemeral ports, the library uses nonce-verified port scanning to discover the correct port.

### 6.3 Dependencies

- **ASM 9.9.1:** Bytecode analysis and generation (5 modules: core, commons, util, tree, analysis)
- **Objenesis 3.5:** Constructor-less object instantiation
- **JUnit 5.14.2:** Testing (test scope only)

## 7. Concurrency Model

All freeze and restore operations are serialized:

```java
// In Durable.java
synchronized (Durable.class) {
    ThreadFreezer.freeze(handler, namedObjects);
}

synchronized (Durable.class) {
    restoredThread = ThreadRestorer.restore(snapshot, namedReplacements);
}
```

Within a freeze/restore operation:
- The **freeze worker** runs on a daemon thread and communicates with the caller via a lock/notify pattern
- The **restore JDI worker** runs on a daemon thread, polls for the replay thread to reach a known wait point, then sets locals
- Thread suspension uses **double-suspend** (suspend count = 2) for resilience against spurious JDI resumes from stale events

Static shared state (`ReplayState` latches, `HeapObjectBridge`, `InvokeRegistry`) is accessed by:
- The replay thread (reads/writes `ReplayState` ThreadLocals and latches)
- The JDI worker thread (releases latches, signals errors)
- Multiple concurrent class loads (writes to `InvokeRegistry`, which uses `ConcurrentHashMap`)

## 8. Lambda Support

Lambda frames (`$$Lambda` classes) are handled specially:
- Lambda classes are not instrumented (they bypass `ClassFileTransformer` as hidden/anonymous classes)
- During freeze, lambda frames are skipped; the enclosing class's synthetic delegate method IS captured
- The functional interface is detected and stored in `FrameSnapshot.lambdaBridgeInterface`
- During restore, a `java.lang.reflect.Proxy` implementing the functional interface is created to bridge from the caller's interface invoke to the synthetic method whose replay prologue handles the rest
