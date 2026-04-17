# Durable Threads

**Freeze, serialize, and resume Java threads across JVM restarts.**

Durable Threads is a pure-Java library that captures the full execution state of a running thread — call stack, local variables, and heap objects — serializes it to a portable snapshot, and restores it in a new JVM process. No special JVM forks, no compiler plugins, no JVMTI native agents. It works on stock OpenJDK 8+.

[![CI](https://github.com/hooji/DurableJavaThreads/actions/workflows/ci.yml/badge.svg)](https://github.com/hooji/DurableJavaThreads/actions/workflows/ci.yml)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-blue)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0%20%2F%20MIT-green)](LICENSE-APACHE)
[![Release](https://img.shields.io/github/v/release/hooji/DurableJavaThreads)](https://github.com/hooji/DurableJavaThreads/releases/latest)
[![Pure Java](https://img.shields.io/badge/Pure%20Java-no%20native%20code-brightgreen)]()
[![Stock JVM](https://img.shields.io/badge/Stock%20JVM-no%20custom%20build-brightgreen)]()

## Quick Start

### Download

Download [`durable-threads-1.5.0.jar`](https://github.com/hooji/DurableJavaThreads/releases/download/v1.5.0/durable-threads-1.5.0.jar) from the [latest release](https://github.com/hooji/DurableJavaThreads/releases/latest). This is a shaded jar that bundles all dependencies (ASM and Objenesis).

### Hello World

The main thread runs a loop from 0 to 10 and freezes itself at `i == 5`. A second JVM restores the thread, which picks up at `i == 5` and finishes the loop.

**FreezeDemo.java** — run this first:

```java
import ai.jacc.durableThreads.Durable;

public class FreezeDemo {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i <= 10; i++) {
            System.out.println("i=" + i);

            if (i == 5) {
                System.out.println("About to freeze!");
                Durable.freeze("./snapshot.dat");
                // Everything below only runs after restore
                System.out.println("Resumed!");
            }
        }
        System.out.println("Done!");
    }
}
```

**RestoreDemo.java** — run this in a new JVM to resume:

```java
import ai.jacc.durableThreads.Durable;

public class RestoreDemo {
    public static void main(String[] args) throws Exception {
        Durable.restore("./snapshot.dat");
        // prints: Resumed!, i=6, i=7, ... i=10, Done!
    }
}
```

Both JVMs must be started with the agent and JDWP enabled:

```bash
% javac -g -cp durable-threads-1.5.0.jar FreezeDemo.java RestoreDemo.java

% java -javaagent:durable-threads-1.5.0.jar \
       -agentlib:jdwp=transport=dt_socket,server=y,suspend=n \
       -cp .:durable-threads-1.5.0.jar \
       FreezeDemo
i=0
i=1
i=2
i=3
i=4
i=5
About to freeze!

% java -javaagent:durable-threads-1.5.0.jar \
       -agentlib:jdwp=transport=dt_socket,server=y,suspend=n \
       -cp .:durable-threads-1.5.0.jar \
       RestoreDemo
Resumed!
i=6
i=7
i=8
i=9
i=10
Done!
```

The library automatically discovers the JDWP port — no need to specify an explicit `address=PORT`. If you prefer a fixed port, you can add `address=44892` (or any port) to the `-agentlib:jdwp` argument.

The loop variable `i` was 5 when the thread froze. The restored thread resumes from the line after `freeze()` with `i == 5` and continues the loop to completion.

### What happened under the hood

1. `Durable.freeze("./snapshot.dat")` connected to the JVM's debug interface (JDWP), walked the thread's call stack, captured every local variable (`i`, etc.) and every heap object reachable from those variables, and serialized everything into `./snapshot.dat`. The original thread was then terminated.

2. `Durable.restore("./snapshot.dat")` deserialized the snapshot, rebuilt all heap objects, re-entered every method on the original call stack using injected replay prologues, and jumped into each method's original code section. With all frames in their original code, JDI set every local variable in a single pass. Then `freeze()` returned normally and the loop continued.

### More control

For full control over serialization, use the `Consumer<ThreadSnapshot>` form:

```java
Durable.freeze(snapshot -> {
    // snapshot is a plain Serializable object.
    // Write it to a file, a database, S3 — anywhere.
    myDatabase.save("workflow-123", serialize(snapshot));
});
```

Or use the built-in `SnapshotFileWriter`:

```java
Durable.freeze(new SnapshotFileWriter("checkpoint.dat"));
```

### Named objects

Tag heap objects with names at freeze time, then substitute live replacements at restore time. This lets you reconnect a restored thread to fresh external resources (database connections, service clients, etc.) without the thread knowing anything changed:

```java
// Freeze with named objects
Map<String, Object> named = Map.of("db", databaseConn, "config", appConfig);
Durable.freeze("checkpoint.dat", named);

// Restore with replacements — the thread sees the NEW objects
Map<String, Object> replacements = Map.of("db", newDbConn, "config", newConfig);
Durable.restore("checkpoint.dat", replacements);
```

The `this` reference of the calling frame is automatically named `"this"` unless you explicitly provide it.

### Multi-instantiation

A single snapshot can be restored any number of times. Each restore creates an independent thread with its own copy of the heap:

```java
ThreadSnapshot snapshot = loadSnapshot("checkpoint.dat");
for (int i = 0; i < 10; i++) {
    Map<String, Object> params = Map.of("workerId", new WorkerId(i));
    Durable.restore(snapshot, params);  // 10 independent threads from one snapshot
}
```

Combined with named replacements, this enables fork-join patterns where a frozen computation is parameterized and fanned out.

### Evergreen threads

A thread can freeze and restore repeatedly, creating a durable execution loop:

```java
while (true) {
    Event event = waitForEvent();        // may take days
    process(event);
    Durable.freeze("workflow.dat");      // checkpoint to database
    // After restore, loop continues from here
}
```

Between freeze and restore, the thread consumes no resources. The snapshot lives in a database, S3, or disk. A framework detects the triggering event and calls `restore()` — the thread picks up exactly where it left off.

### Portable snapshots

Every snapshot carries `SnapshotEnvironment` metadata: the exact JDK runtime version, vendor, VM name (HotSpot / OpenJ9 / GraalVM), OS name and architecture, and the classpath that was in effect at freeze time. A restore-side tool can use this to verify compatibility or to print precise diagnostics when the target JVM differs:

```java
ThreadSnapshot snapshot = loadSnapshot("checkpoint.dat");
SnapshotEnvironment env = snapshot.environment();
System.out.println("Frozen on " + env.javaVendor() + " " + env.javaRuntimeVersion()
        + " / " + env.javaVmName() + " / " + env.osArch());
```

For **fully self-contained** restore on a JVM that doesn't have the original classpath available, opt in to bundling user class file bytes inside the snapshot:

```java
Durable.setEmbedClassBytecodes(true);   // global toggle, default off
Durable.freeze("checkpoint.dat");
```

When the embedded bytes are present, restore will install any missing user classes via `Instrumentation.appendToSystemClassLoaderSearch` before wiring the heap. The cost is snapshot size — typically 2–3× larger than bare snapshots — so the feature is **off by default**. Leave it off for normal in-place restore; turn it on when you need a portable checkpoint that can run anywhere with a compatible JDK.

## API

### `Durable.freeze(String filePath)` / `Durable.freeze(Path path)`

Freezes the calling thread, serializing the snapshot to the given file. The original thread is terminated after the snapshot is written. Code after `freeze()` only executes in restored threads.

### `Durable.freeze(Consumer<ThreadSnapshot> handler)`

Freezes the calling thread. The handler receives the snapshot for custom persistence. The original thread is terminated after the handler returns. Code after `freeze()` only executes in restored threads.

All freeze overloads accept an optional `Map<String, Object> namedObjects` parameter to tag heap objects with names for replacement at restore time.

### `Durable.restore(String filePath)` / `Durable.restore(Path path)` / `Durable.restore(ThreadSnapshot snapshot)`

Restores the thread from a snapshot and resumes it. The simple overloads restore, resume, and wait for the thread to complete. Code after `freeze()` in the original source runs in the restored thread.

### `Durable.restore(ThreadSnapshot snapshot, Map<String, Object> namedReplacements, boolean resume, boolean awaitCompletion)`

Advanced overload with explicit control. Returns a `RestoredThread` handle. All JDI work is complete before this method returns — the thread is alive but parked on an internal latch.

- `namedReplacements` — live objects to substitute for named objects in the snapshot (may be null)
- `resume` — if `true`, releases the thread to continue executing
- `awaitCompletion` — if `true` (and `resume` is also true), blocks until the restored thread finishes

### `RestoredThread`

Handle to a restored thread. Call `resume()` to release the thread, or `thread()` to get the underlying `Thread`.

### `SnapshotFileWriter`

A `Consumer<ThreadSnapshot>` that serializes the snapshot to a file. Accepts either a `String` or `Path` in its constructor.

### `Durable.setEmbedClassBytecodes(boolean)` / `Durable.isEmbedClassBytecodes()`

Global toggle controlling whether subsequent `freeze()` calls bundle the original user class file bytes inside the snapshot's `SnapshotEnvironment`. Default: **off**. Turn on for portable restore on a JVM without the original classpath; leave off for smaller snapshot files (the typical case). Read lazily at each `freeze`, so callers can flip it per-checkpoint.

### `ThreadSnapshot`

A `Serializable` record containing:
- Thread name and timestamp
- `List<FrameSnapshot>` — the call stack (bottom to top)
- `List<ObjectSnapshot>` — the heap (objects referenced by locals)
- `SnapshotEnvironment` — metadata about the freeze JVM (see below)

### `SnapshotEnvironment`

Metadata captured at freeze time so a consumer can reconstruct a compatible JVM or diagnose an incompatible one:

- `libraryVersion()` — Durable Threads version that wrote the snapshot
- `javaVersion()` / `javaRuntimeVersion()` / `javaSpecificationVersion()` — e.g. `21.0.2`, `21.0.2+13-LTS`, `21`
- `javaVendor()` — e.g. `Eclipse Adoptium`, `Oracle Corporation`
- `javaVmName()` — e.g. `OpenJDK 64-Bit Server VM`, `Eclipse OpenJ9 VM`
- `osName()` / `osArch()` / `archDataModel()` — e.g. `Linux`, `aarch64`, `64`
- `classpath()` — `java.class.path` as it was at freeze time
- `classEntries()` — per-class source-location and bytecode-hash entries; each entry optionally carries the original class file bytes when `Durable.setEmbedClassBytecodes(true)` was in effect

Fields added after library v1.4.1 may be `null` when reading older snapshots.

## How It Works

```
Thread running          Snapshot file          New JVM
┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│ outerMethod  │       │ frames:      │       │ outerMethod  │
│  middleMethod│ ───►  │  - outer     │ ───►  │  middleMethod│
│   innerMethod│ freeze│  - middle    │restore│   innerMethod│
│    ► freeze()│       │  - inner     │       │    ► continues
│              │       │ locals, heap │       │              │
└──────────────┘       └──────────────┘       └──────────────┘
```

1. **Bytecode instrumentation** — A Java agent (`-javaagent`) rewrites classes at load time using ASM, injecting a replay prologue into every method. This prologue is dormant during normal execution and activates only during restore.

2. **Freeze via JDI** — When you call `Durable.freeze()`, the library connects to the JVM's own debug interface (JDWP), walks the calling thread's stack frames, and captures every local variable and referenced heap object into a `ThreadSnapshot`. **Important:** the operand stack must be empty at every active call site in the frozen thread's stack — that is, `freeze()` must not be called in the middle of an expression that has pushed intermediate values onto the operand stack. In practice, this means `Durable.freeze()` should be called as a standalone statement, not nested inside another expression. The library validates this at freeze time and throws `NonEmptyParameterStackException` if violated.

3. **Serialize** — The snapshot is a plain `Serializable` object. Write it to a file, a database, S3 — anywhere.

4. **Restore via single-pass replay** — In a new JVM, `Durable.restore(snapshot)` rebuilds the heap, re-enters every method on the original call stack using the injected prologues, and jumps into each method's original code section. Every frame ends up at its original invoke position — the deepest frame calls `freeze()`, which detects restore mode and blocks on a latch. With all frames in their original code, all local variables are naturally in scope, so JDI sets every local in every frame in a single pass. When the latch is released, `freeze()` returns normally and user code continues from exactly where it left off.

For a deep dive into how bytecode offsets are computed and why the freeze/restore mapping is correct across JVM restarts, see [Bytecode Offset Computation: Correctness Analysis](docs/bytecode-offset-correctness.md).

### How It Compares

| Feature | Durable Threads | CRIU | CRaC |
|---|---|---|---|
| Stock JVM | Yes | Yes | No (special build) |
| Granularity | Single thread | Entire process | Entire JVM |
| Serialize to disk | Yes | Yes | Yes |
| Cross-machine restore | Yes | Limited | No |
| Linux-only | No | Yes | No |
| Requires root | No | Yes | No |
| Thread-level selectivity | Yes | No | No |

**CRIU** (Checkpoint/Restore in Userspace) snapshots an entire Linux process. It's powerful but requires root, only works on Linux, and operates at process granularity — you can't freeze one thread while others continue.

**CRaC** (Coordinated Restore at Checkpoint) is an OpenJDK project for fast JVM startup. It checkpoints the entire JVM, then restores it later. It requires a special JDK build (e.g., Azul Zulu with CRaC support) and is process-level, not thread-level.

**Workflow engines** like Temporal, Cadence, and AWS Step Functions achieve "durable execution" by requiring you to structure code as explicit state machines or event handlers. They're production-proven but require rewriting your logic.

**Durable Threads** is different: it captures a single thread's execution state from *inside* a stock JVM, serializes it portably, and restores it anywhere — even on a different machine. Your code stays straight-line Java.

## Building

### Prerequisites

- Java 8 or later (OpenJDK / Temurin recommended)
- Maven 3.9+

### Build from source

```bash
git clone https://github.com/hooji/DurableJavaThreads.git
cd DurableJavaThreads
mvn clean package -DskipTests
```

This produces `target/durable-threads-1.5.0.jar` — a shaded jar that bundles ASM and Objenesis.

### Running Tests

```bash
# Unit tests (fast, no agent required)
mvn test

# Full E2E tests (spawns child JVMs with agent + JDWP)
mvn package -DskipTests && mvn failsafe:integration-test failsafe:verify

# Everything
mvn clean verify

# Stress tests (each E2E scenario repeated 20x to detect intermittent failures)
mvn package -DskipTests && mvn failsafe:integration-test failsafe:verify -Pstress

# Performance benchmarks (freeze/restore latency and snapshot sizes)
mvn package -DskipTests && mvn failsafe:integration-test -Dit.test=PerformanceBenchmarkIT
```

## Dependency

### Maven

```xml
<dependency>
    <groupId>ai.jacc</groupId>
    <artifactId>durable-threads</artifactId>
    <version>1.5.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'ai.jacc:durable-threads:1.5.0'
```

> **Note:** Durable Threads is not yet published to Maven Central. For now, download the jar from the [releases page](https://github.com/hooji/DurableJavaThreads/releases) or build from source.

## Project Structure

```
src/main/java/ai/jacc/durableThreads/
├── Durable.java              # Public API
├── DurableAgent.java          # Java agent (premain)
├── DurableTransformer.java    # ClassFileTransformer
├── SnapshotFileWriter.java    # Consumer that serializes snapshots to a file
├── PrologueInjector.java      # ASM ClassVisitor — injects replay prologues
├── PrologueEmitter.java       # Emits replay prologue bytecode (resume stubs + original code)
├── OperandStackSimulator.java # Simulates JVM operand stack for type tracking
├── PrologueTypes.java         # Data types shared between injector and emitter
├── ReflectionHelpers.java     # Method lookup, descriptor matching, bottom frame invocation
├── ReplayState.java           # Thread-local replay coordination and go-latch
├── RestoredThread.java        # Handle to a restored thread (go-latch control)
├── SnapshotValidator.java     # Bytecode/structure hash validation at restore time
├── ThreadFreezer.java         # Freeze implementation (JDI stack walk)
├── ThreadRestorer.java        # Restore orchestration (heap rebuild, replay, JDI)
├── JdiValueConverter.java     # Snapshot ObjectRef → JDI Value conversion
├── JdiLocalSetter.java        # JDI local variable setting (frame matching, GC pinning)
├── exception/                 # ThreadFrozenError, AgentNotLoadedException, etc.
├── internal/                  # InvokeRegistry, BytecodeHasher, HeapRestorer, etc.
└── snapshot/                  # ThreadSnapshot, FrameSnapshot, ObjectSnapshot, etc.
```

## Limitations

- **Empty operand stack required** — `Durable.freeze()` must be called as a standalone statement. Every active stack frame in the frozen thread must have an empty operand stack at its call site (no intermediate expression values on the stack). The library enforces this at freeze time and throws `NonEmptyParameterStackException` if the constraint is violated. In practice: don't call `freeze()` inside `System.out.println(Durable.freeze(...))` or `foo(bar(), Durable.freeze(...))`.

- **Heap object references** — Objects referenced by local variables are captured and restored via JDI. Transient fields are skipped. Objects don't need to implement `Serializable` — the library extracts field data directly via reflection/JDI.

- **Lambda support** — Lambdas on the call stack are supported. You can call `freeze()` from within a lambda body, a callback passed as a lambda, or a method reference (`this::method`). The library transparently skips the `$$Lambda` dispatch frame and enters the compiler-generated synthetic method directly via a dynamic proxy bridge. Captured variables (which become parameters of the synthetic method) are restored by JDI in the single-pass restore. **Not supported:** stream pipelines (`stream().filter().map().collect()`) and deeply-chained functional APIs — these create complex JDK infrastructure frame chains that can't be replayed.

## Requirements

- **Java 8+** — compiled to Java 8 bytecode; works on any JDK 8 or later
- **JDWP** — the JVM must be started with `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n`. The library auto-discovers the JDWP port on Linux (`/proc/net/tcp`), macOS (`lsof`), and Windows (`netstat`). You can also specify a fixed port with `address=PORT`

## License

Dual-licensed under [Apache 2.0](LICENSE-APACHE) or [MIT](LICENSE-MIT), at your option. See [NOTICE](NOTICE) for third-party attributions.

## Using DurableJavaThreads with SimpleJavaTemplates

DurableJavaThreads is designed to coexist cleanly with
[SimpleJavaTemplates](https://github.com/hooji/SimpleJavaTemplates). When
both libraries are on your application classpath, you only need to specify
the `durable-threads` agent on the command line:

```bash
java -javaagent:durable-threads-1.5.0.jar \
     -agentlib:jdwp=transport=dt_socket,server=y,suspend=n \
     -cp SimpleJavaTemplates.jar:durable-threads-1.5.0.jar:your-app.jar \
     com.example.Main
```

`DurableAgent.premain` detects `SimpleJavaTemplates` on the classpath by
fully-qualified class name and auto-chains its agent before registering
DurableTransformer, so the two run in the order they need to. No second
`-javaagent` flag required. Passing one explicitly is also fine — it becomes
a no-op via `SimpleJavaTemplatesAgent.loaded`.
