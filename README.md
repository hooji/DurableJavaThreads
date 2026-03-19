# Durable Threads

**Freeze, serialize, and resume Java threads across JVM restarts.**

Durable Threads is a pure-Java library that captures the full execution state of a running thread — call stack, local variables, and heap objects — serializes it to a portable snapshot, and restores it in a new JVM process. No special JVM forks, no compiler plugins, no JVMTI native agents. It works on stock OpenJDK 21+.

[![CI](https://github.com/hooji/DurableJavaThreads/actions/workflows/ci.yml/badge.svg)](https://github.com/hooji/DurableJavaThreads/actions/workflows/ci.yml)

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

## Quick Start

### Prerequisites

- Java 21 or later (OpenJDK / Temurin recommended)
- Maven 3.9+

### Build

```bash
git clone https://github.com/hooji/DurableJavaThreads.git
cd DurableJavaThreads
mvn clean package -DskipTests
```

This produces `target/durable-threads-0.3.0-SNAPSHOT.jar` — a shaded jar that bundles ASM and Objenesis.

### Hello World

Here's the simplest possible example. A thread counts to 3, freezes itself to a file, and is later restored in a new JVM where it picks up right where it left off.

**FreezeDemo.java** — run this first:

```java
import com.u1.durableThreads.Durable;

public class FreezeDemo {
    public static void main(String[] args) throws Exception {
        Thread worker = new Thread(() -> doWork());
        worker.start();
        worker.join();
    }

    static void doWork() {
        int counter = 0;

        counter++;
        System.out.println("counter = " + counter);  // prints 1

        counter++;
        System.out.println("counter = " + counter);  // prints 2

        counter++;
        System.out.println("counter = " + counter);  // prints 3

        // Freeze the thread — serialize its entire state to a file.
        // The original thread is terminated here. Everything after
        // this line ONLY executes in a restored thread.
        Durable.freeze("./MyFrozenThread.dat");

        // --- This code only runs after restore in a new JVM ---

        counter++;
        System.out.println("counter = " + counter);  // prints 4

        counter++;
        System.out.println("counter = " + counter);  // prints 5

        System.out.println("Done!");
    }
}
```

**RestoreDemo.java** — run this in a new JVM to resume:

```java
import com.u1.durableThreads.Durable;

public class RestoreDemo {
    public static void main(String[] args) throws Exception {
        // Deserialize the snapshot and rebuild the thread.
        // The returned thread has the full call stack and all local
        // variables (including counter == 3) restored from the file.
        Thread restored = Durable.restore("./MyFrozenThread.dat");
        restored.start();
        restored.join();
        // prints 4, 5, Done!
    }
}
```

Both JVMs must be started with the agent and JDWP enabled:

```bash
# Compile
javac -cp durable-threads-0.3.0-SNAPSHOT.jar FreezeDemo.java RestoreDemo.java

# Freeze — prints 1, 2, 3 then writes ./MyFrozenThread.dat
java -javaagent:durable-threads-0.3.0-SNAPSHOT.jar \
     -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0 \
     --add-modules jdk.jdi \
     -cp .:durable-threads-0.3.0-SNAPSHOT.jar \
     FreezeDemo

# Restore — prints 4, 5, Done!
java -javaagent:durable-threads-0.3.0-SNAPSHOT.jar \
     -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0 \
     --add-modules jdk.jdi \
     -cp .:durable-threads-0.3.0-SNAPSHOT.jar \
     RestoreDemo
```

That's it. The `counter` local variable was at 3 when the thread froze. The restored thread picks up with `counter == 3` and continues from the line after `freeze()`.

### What happened under the hood

1. `Durable.freeze("./MyFrozenThread.dat")` connected to the JVM's debug interface (JDWP), walked the thread's call stack, captured every local variable (`counter`, `this`, etc.) and every heap object reachable from those variables, and serialized everything into `./MyFrozenThread.dat`. The original thread was then terminated.

2. `Durable.restore("./MyFrozenThread.dat")` deserialized the snapshot, rebuilt all the heap objects first (so object references are available), then re-entered every method on the original call stack using injected replay prologues, set all local variables via JDI, and handed back a `Thread` ready to resume from exactly where `freeze()` was called.

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

### `Durable.restore(String filePath)` / `Durable.restore(Path path)`

Deserializes a `ThreadSnapshot` from the given file and returns a `Thread` (not yet started) that will replay the call stack and resume from the freeze point when started.

### `Durable.restore(ThreadSnapshot snapshot)`

Returns a `Thread` (not yet started) that will replay the call stack and resume from the freeze point when started.

### `SnapshotFileWriter`

A `Consumer<ThreadSnapshot>` that serializes the snapshot to a file. Accepts either a `String` or `Path` in its constructor.

### `ThreadSnapshot`

A `Serializable` record containing:
- Thread name and timestamp
- `List<FrameSnapshot>` — the call stack (bottom to top)
- `List<ObjectSnapshot>` — the heap (objects referenced by locals)

## Running Tests

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

## How It Compares

| Feature | Durable Threads | Quasar/Loom | CRIU | Project Loom |
|---|---|---|---|---|
| Stock JVM | Yes | Quasar: No (agent + bytecode) | No (kernel module) | Yes (but no serialize) |
| Serialize to disk | Yes | No | Yes (process-level) | No |
| Cross-JVM restore | Yes | No | Limited | No |
| Java 21+ | Yes | Quasar: abandoned | Yes | Yes |
| Granularity | Thread | Fiber | Process | Thread |

## Project Structure

```
src/main/java/com/u1/durableThreads/
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

- **Java 21+** — uses modern language features and JDI APIs
- **JDWP** — the JVM must be started with `-agentlib:jdwp=...` for freeze/restore
- **jdk.jdi module** — add `--add-modules jdk.jdi` to the command line

## License

Dual-licensed under [Apache 2.0](LICENSE-APACHE) or [MIT](LICENSE-MIT), at your option. See [NOTICE](NOTICE) for third-party attributions.
