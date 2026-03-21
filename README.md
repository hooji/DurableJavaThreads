# Durable Threads

**Freeze, serialize, and resume Java threads across JVM restarts.**

Durable Threads is a pure-Java library that captures the full execution state of a running thread — call stack, local variables, and heap objects — serializes it to a portable snapshot, and restores it in a new JVM process. No special JVM forks, no compiler plugins, no JVMTI native agents. It works on stock OpenJDK 8+.

[![CI](https://github.com/hooji/DurableJavaThreads/actions/workflows/ci.yml/badge.svg)](https://github.com/hooji/DurableJavaThreads/actions/workflows/ci.yml)

## Quick Start

### Download

Download [`durable-threads-1.0.1.jar`](https://github.com/hooji/DurableJavaThreads/releases/download/v1.0.0/durable-threads-1.0.1.jar) from the [latest release](https://github.com/hooji/DurableJavaThreads/releases/latest). This is a shaded jar that bundles all dependencies (ASM and Objenesis).

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
        Durable.restore("./snapshot.dat", true, true);
        // prints: Resumed!, i=6, i=7, ... i=10, Done!
    }
}
```

Both JVMs must be started with the agent and JDWP enabled:

```bash
% javac -g -cp durable-threads-1.0.1.jar FreezeDemo.java RestoreDemo.java

% java -javaagent:durable-threads-1.0.1.jar \
       -agentlib:jdwp=transport=dt_socket,server=y,suspend=n \
       --add-modules jdk.jdi \
       -cp .:durable-threads-1.0.1.jar \
       FreezeDemo
i=0
i=1
i=2
i=3
i=4
i=5
About to freeze!

% java -javaagent:durable-threads-1.0.1.jar \
       -agentlib:jdwp=transport=dt_socket,server=y,suspend=n \
       --add-modules jdk.jdi \
       -cp .:durable-threads-1.0.1.jar \
       RestoreDemo
Resumed!
i=6
i=7
i=8
i=9
i=10
Done!
```

**Note:** The `--add-modules jdk.jdi` argument is required on Java 9 and later. On Java 8, omit it — there is no module system, and JDI classes are available automatically.

The library automatically discovers the JDWP port — no need to specify an explicit `address=PORT`. If you prefer a fixed port, you can add `address=44892` (or any port) to the `-agentlib:jdwp` argument.

The loop variable `i` was 5 when the thread froze. The restored thread resumes from the line after `freeze()` with `i == 5` and continues the loop to completion.

### What happened under the hood

1. `Durable.freeze("./snapshot.dat")` connected to the JVM's debug interface (JDWP), walked the thread's call stack, captured every local variable (`i`, etc.) and every heap object reachable from those variables, and serialized everything into `./snapshot.dat`. The original thread was then terminated.

2. `Durable.restore("./snapshot.dat")` deserialized the snapshot, rebuilt all the heap objects first (so object references are available), then re-entered every method on the original call stack using injected replay prologues, set all local variables via JDI, and handed back a `Thread` ready to resume from exactly where `freeze()` was called.

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

## API

### `Durable.freeze(String filePath)` / `Durable.freeze(Path path)`

Freezes the calling thread, serializing the snapshot to the given file. The original thread is terminated after the snapshot is written. Code after `freeze()` only executes in restored threads.

### `Durable.freeze(Consumer<ThreadSnapshot> handler)`

Freezes the calling thread. The handler receives the snapshot for custom persistence. The original thread is terminated after the handler returns. Code after `freeze()` only executes in restored threads.

### `Durable.restore(String filePath)` / `Durable.restore(Path path)` / `Durable.restore(ThreadSnapshot snapshot)`

Returns a `Thread` (not yet started) that will replay the call stack and resume from the freeze point when started.

All three accept optional boolean parameters:
- `restore(..., boolean startThread)` — if `true`, starts the thread before returning.
- `restore(..., boolean startThread, boolean waitForThreadToFinish)` — if both are `true`, blocks until the restored thread completes.

### `SnapshotFileWriter`

A `Consumer<ThreadSnapshot>` that serializes the snapshot to a file. Accepts either a `String` or `Path` in its constructor.

### `ThreadSnapshot`

A `Serializable` record containing:
- Thread name and timestamp
- `List<FrameSnapshot>` — the call stack (bottom to top)
- `List<ObjectSnapshot>` — the heap (objects referenced by locals)

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

2. **Freeze via JDI** — When you call `Durable.freeze()`, the library connects to the JVM's own debug interface (JDWP), walks the calling thread's stack frames, and captures every local variable and referenced heap object into a `ThreadSnapshot`. **Important:** the operand stack must be empty at every active call site in the frozen thread's stack — that is, `freeze()` must not be called in the middle of an expression that has pushed intermediate values onto the operand stack. In practice, this means `Durable.freeze()` should be called as a standalone statement, not nested inside another expression. The library validates this at freeze time and throws `NonEmptyStackException` if violated.

3. **Serialize** — The snapshot is a plain `Serializable` object. Write it to a file, a database, S3 — anywhere.

4. **Restore via replay** — In a new JVM, `Durable.restore(snapshot)` rebuilds the heap, re-enters every method on the original call stack using the injected prologues, sets local variables via JDI, and resumes execution from exactly where `freeze()` was called.

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

This produces `target/durable-threads-1.0.1.jar` — a shaded jar that bundles ASM and Objenesis.

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
    <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'ai.jacc:durable-threads:1.0.0'
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
├── ReplayState.java           # Thread-local replay coordination
├── ThreadFreezer.java         # Freeze implementation (JDI stack walk)
├── ThreadRestorer.java        # Restore implementation (JDI local setting)
├── Version.java               # Version string
├── exception/                 # ThreadFrozenError, AgentNotLoadedException, etc.
├── internal/                  # InvokeRegistry, BytecodeHasher, HeapRestorer, etc.
└── snapshot/                  # ThreadSnapshot, FrameSnapshot, ObjectSnapshot, etc.
```

## Limitations

- **Empty operand stack required** — `Durable.freeze()` must be called as a standalone statement. Every active stack frame in the frozen thread must have an empty operand stack at its call site (no intermediate expression values on the stack). The library enforces this at freeze time and throws `NonEmptyStackException` if the constraint is violated. In practice: don't call `freeze()` inside `System.out.println(Durable.freeze(...))` or `foo(bar(), Durable.freeze(...))`.

- **Heap object references** — Objects referenced by local variables are captured and restored via JDI. Transient fields are skipped. Objects don't need to implement `Serializable` — the library extracts field data directly via reflection/JDI.

- **No lambda frames in call stack** — If `Durable.freeze()` is called from within a lambda, or if any frame in the frozen thread's call stack is a lambda-generated class (`$$Lambda`), the library throws `LambdaFrameException`. Lambda class names are JVM-specific and cannot be replayed during restore. To fix: refactor the lambda into a named method or inner class, or move the `freeze()` call outside the lambda.

## Requirements

- **Java 8+** — compiled to Java 8 bytecode; works on any JDK 8 or later
- **JDWP** — the JVM must be started with `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n`. The library auto-discovers the JDWP port on Linux (`/proc/net/tcp`), macOS (`lsof`), and Windows (`netstat`). You can also specify a fixed port with `address=PORT`
- **jdk.jdi module** (Java 9+ only) — add `--add-modules jdk.jdi` to the command line. On Java 8 this is not needed since JDI classes are on the classpath by default

## License

Dual-licensed under [Apache 2.0](LICENSE-APACHE) or [MIT](LICENSE-MIT), at your option. See [NOTICE](NOTICE) for third-party attributions.
