# Research Request: JDI LocalVariable Slot Reuse and Method-Wide Scope Extension

## Context

We are building a library (Durable Java Threads) that freezes and restores Java thread execution state using bytecode instrumentation (ASM) and the Java Debug Interface (JDI). During restoration, we need to set local variable values in stack frames via JDI's `StackFrame.setValue(LocalVariable, Value)`.

## The Problem

When we restore a thread, we rebuild its call stack by having each frame's injected prologue jump into the original code section at the point where the original invoke instruction is. This means all frames on the stack are positioned in their original code sections, and we need JDI to be able to set ALL local variables (both parameters and non-parameters) in ALL frames simultaneously.

JDI's `StackFrame.setValue()` only works for variables that are "in scope" at the current bytecode index (BCI). A variable's scope is defined by the `LocalVariableTable` attribute in the class file, which maps each variable to a (startBCI, length) range.

### Our Attempted Solution

We use ASM's `ClassVisitor` / `MethodVisitor` to instrument classes at load time. We tried extending ALL local variable scopes to method-wide by emitting `visitLocalVariable(name, desc, sig, methodStartLabel, methodEndLabel, slot)` for every variable, instead of using the original start/end labels.

### Why It Fails: Slot Reuse

The Java compiler (javac) reuses local variable slots. For example:

```java
void example(int n) {
    for (int i = 0; i < n; i++) {
        System.out.println(i);
    }
    String result = "done";  // 'result' reuses the slot that 'i' occupied
    System.out.println(result);
}
```

In the compiled bytecode, `i` (int, slot 2) and `result` (String, slot 2) share the same slot but have non-overlapping scopes in the original `LocalVariableTable`. When we extend both to method-wide scope, we get two overlapping entries for slot 2:
- `i`, type `I`, scope: method-wide
- `result`, type `Ljava/lang/String;`, scope: method-wide

This causes JDI's `setValue()` to fail with type errors — it finds the wrong variable entry for the slot and rejects the value because the types don't match. Or it picks `result` when we're trying to set `i`, etc.

## What We Need

We need a way to set local variable values via JDI at BCIs where, according to the ORIGINAL `LocalVariableTable`, those variables are out of scope — but where, from our perspective, the slots DO contain valid values that we initialized via our injected bytecode.

### Specific Constraints

1. **Must work on Java 8 through Java 25** — the solution must be compatible across all these JDK versions
2. **Uses ASM 9.9.1** for bytecode manipulation
3. **The class file is being transformed at load time** via a `ClassFileTransformer` (java agent) — we have full control over the bytecode and metadata emitted
4. **We're in the same JVM** — the JDI connection is a self-attach to our own JVM via JDWP
5. **The values we need to set are known** — we have the correct value for each slot, we just can't set them because JDI says the variable is out of scope
6. **We already have a working bypass for setValue on Java 8** — we use reflection to access JDI internals (`JDWP$StackFrame$SetValues$SlotInfo`) to bypass JDI's client-side type check. See `ThreadRestorer.setValueBypassTypeCheck()`.

### Possible Approaches to Investigate

1. **Manipulating the LocalVariableTable more carefully** — Can we emit entries that avoid conflicts? For example, only extending the scope of the variable that's "active" at each invoke BCI, using the per-invoke scope maps we already compute?

2. **Using JDWP directly** — JDI's `setValue()` does a client-side check against the `LocalVariableTable`. The underlying JDWP `StackFrame.SetValues` command just sets a slot by number — it doesn't check scopes. Can we bypass JDI entirely and send JDWP commands directly? We already have a partial implementation of this in `setValueBypassTypeCheck()`.

3. **Multiple LocalVariableTable entries with non-overlapping scopes** — Instead of extending everything to method-wide, could we emit multiple scope ranges for each variable that cover both the original scope AND the stub/invoke BCIs? ASM's `visitLocalVariable` takes start/end labels — can we emit multiple entries for the same variable covering different ranges?

4. **Using LocalVariableTypeTable** — Is there a way to use the generic signature table to disambiguate?

5. **Modifying the slot allocation** — Can we prevent slot reuse entirely by increasing `maxLocals` and assigning each variable a unique slot? This would waste stack space but might be simpler.

6. **JVMTI alternatives** — Is there a way to set local variables via JVMTI instead of JDI that doesn't have the scope restriction?

7. **Other approaches** used by frameworks that face similar problems (debuggers, hot-swap tools, continuation libraries like Quasar/Loom, serialization frameworks that restore stack state).

## Current Code References

- `PrologueInjector.emitLocalVariables()` — where we emit the LocalVariableTable entries
- `PrologueInjector.buildPerInvokeScopeMaps()` — computes which variables are in scope at each invoke position
- `ThreadRestorer.setFrameLocals()` — where JDI setValue is called
- `ThreadRestorer.setValueBypassTypeCheck()` — existing JDWP bypass for Java 8 ClassNotLoadedException

## Desired Outcome

A technique that allows us to set any local variable slot to a value via JDI (or equivalent), regardless of whether the `LocalVariableTable` says that variable is in scope at the current BCI. The technique must work across Java 8-25.
