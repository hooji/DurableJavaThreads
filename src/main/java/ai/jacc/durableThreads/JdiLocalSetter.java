package ai.jacc.durableThreads;

import com.sun.jdi.*;
import ai.jacc.durableThreads.internal.*;
import ai.jacc.durableThreads.snapshot.*;
import static ai.jacc.durableThreads.internal.FrameFilter.isInfrastructureFrame;

import java.util.*;

/**
 * JDI local variable manipulation: preloads classes, matches JDI frames to
 * snapshot frames, and sets local variables via JDI in a single pass.
 *
 * <p>Extracted from ThreadRestorer (Stage 4 refactoring).</p>
 */
final class JdiLocalSetter {

    /** Maximum attempts to pin an object reference via disableCollection()
     *  before giving up. Each failed attempt means GC collected the object
     *  in the narrow window between JDI resolve and the pin call. */
    private static final int MAX_PIN_ATTEMPTS = 5;

    private JdiLocalSetter() {}

    /** Pre-resolved local variable entry for batch setValue. */
    static final class LocalEntry {
        private final com.sun.jdi.LocalVariable jdiLocal;
        private final Value jdiValue;
        private final boolean isNull;

        LocalEntry(com.sun.jdi.LocalVariable jdiLocal, Value jdiValue, boolean isNull) {
            this.jdiLocal = jdiLocal;
            this.jdiValue = jdiValue;
            this.isNull = isNull;
        }

        public com.sun.jdi.LocalVariable jdiLocal() { return jdiLocal; }
        public Value jdiValue() { return jdiValue; }
        public boolean isNull() { return isNull; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LocalEntry)) return false;
            LocalEntry that = (LocalEntry) o;
            return isNull == that.isNull
                    && Objects.equals(jdiLocal, that.jdiLocal)
                    && Objects.equals(jdiValue, that.jdiValue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jdiLocal, jdiValue, isNull);
        }

        @Override
        public String toString() {
            return "LocalEntry[jdiLocal=" + jdiLocal + ", jdiValue=" + jdiValue
                    + ", isNull=" + isNull + "]";
        }
    }

    /**
     * Set local variables in each frame via JDI (single-pass).
     *
     * <p>The JDI call stack looks like:</p>
     * <pre>
     * JDI frame 0:   awaitGoLatch() [or CountDownLatch internals]
     * JDI frame 1:   freeze() [detecting restore mode]
     * JDI frame 2:   deepest user method (in original code section)
     * JDI frame 3:   next method up (in original code section)
     * ...
     * JDI frame K:   bottom user method
     * JDI frame K+1: invokeBottomFrame / reflection / Thread.run
     * </pre>
     *
     * <p>All frames are in their original code sections (resume stubs jumped
     * to BEFORE_INVOKE labels), so all locals are naturally in scope.</p>
     *
     * <p>The snapshot frames are ordered [bottom, ..., top] (indices 0..N-1).
     * We match them by className + methodName, working from the top of the
     * JDI stack downward. We skip JDI frames that belong to ReplayState,
     * CountDownLatch, or other infrastructure.</p>
     *
     */
    static void setLocalsViaJdi(VirtualMachine vm, ThreadReference threadRef,
                                ThreadSnapshot snapshot,
                                Map<Long, Object> restoredHeap,
                                HeapRestorer heapRestorer) {
        try {
            List<StackFrame> jdiFrames = threadRef.frames();
            List<FrameSnapshot> snapshotFrames = snapshot.frames();

            // Match JDI frames to snapshot frames.
            // JDI is top-to-bottom, snapshot is bottom-to-top.
            int snapshotIdx = snapshotFrames.size() - 1; // start from top (deepest)

            for (int jdiIdx = 0; jdiIdx < jdiFrames.size() && snapshotIdx >= 0; jdiIdx++) {
                StackFrame jdiFrame = jdiFrames.get(jdiIdx);
                Location loc = jdiFrame.location();
                String jdiClassName = loc.declaringType().name().replace('.', '/');
                String jdiMethodName = loc.method().name();

                // Skip infrastructure frames (ReplayState, CountDownLatch, JDK, etc.)
                if (isInfrastructureFrame(jdiClassName)) {
                    continue;
                }

                FrameSnapshot snapFrame = snapshotFrames.get(snapshotIdx);

                // Match by class name and method name
                if (jdiClassName.equals(snapFrame.className())
                        && jdiMethodName.equals(snapFrame.methodName())) {
                        // With the direct-jump architecture, all frames are in their
                    // original code sections, so all locals should be in scope.
                    setFrameLocals(vm, threadRef, jdiFrame, snapFrame,
                            restoredHeap, heapRestorer);
                    snapshotIdx--;
                }
                // If no match, skip this JDI frame (it's infrastructure / reflection)
            }

        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException("Thread not suspended for local variable setting", e);
        }
    }

    /**
     * Set local variables in a single JDI frame from a snapshot frame.
     *
     * <p>Object references resolved from the heap bridge are protected from
     * garbage collection via {@link ObjectReference#disableCollection()} while
     * locals are being set. Without this, the target JVM's GC can collect the
     * mirrored object between resolution and {@code setValue()}, causing a
     * {@code ObjectCollectedException}.</p>
     */
    private static void setFrameLocals(VirtualMachine vm, ThreadReference threadRef,
                                       StackFrame jdiFrame, FrameSnapshot snapFrame,
                                       Map<Long, Object> restoredHeap,
                                       HeapRestorer heapRestorer) {
        Method method = jdiFrame.location().method();

        List<com.sun.jdi.LocalVariable> jdiLocals;
        try {
            jdiLocals = method.variables();
        } catch (AbsentInformationException e) {
            throw new RuntimeException(
                    "No debug info for method " + method.declaringType().name() + "."
                    + method.name() + ". Classes must be compiled with debug info (-g) "
                    + "for thread restore to set local variables.", e);
        }

        // Build a map of JDI locals by name for quick lookup.
        // When multiple variables share the same name and slot (e.g., two
        // for-loops both declaring 'int i'), prefer the one that is VISIBLE
        // at the current frame location. This is critical for the single-pass
        // architecture where the thread is at the freeze point's BCI.
        Map<String, com.sun.jdi.LocalVariable> jdiLocalsByName = new HashMap<>();
        for (com.sun.jdi.LocalVariable jdiLocal : jdiLocals) {
            com.sun.jdi.LocalVariable existing = jdiLocalsByName.get(jdiLocal.name());
            if (existing == null) {
                jdiLocalsByName.put(jdiLocal.name(), jdiLocal);
            } else if (jdiLocal.isVisible(jdiFrame)) {
                // Prefer the visible one (in scope at current BCI)
                jdiLocalsByName.put(jdiLocal.name(), jdiLocal);
            }
        }

        // Pre-resolve all values and pin object references to prevent GC collection.
        // The target JVM's GC can collect objects between resolve and setValue(),
        // causing ObjectCollectedException. disableCollection() prevents this.
        List<LocalEntry> entries = new ArrayList<>();
        List<ObjectReference> pinnedRefs = new ArrayList<>();

        for (ai.jacc.durableThreads.snapshot.LocalVariable snapLocal : snapFrame.locals()) {
            com.sun.jdi.LocalVariable jdiLocal = jdiLocalsByName.get(snapLocal.name());
            if (jdiLocal == null) continue;

            Value jdiValue = JdiValueConverter.convertToJdiValue(vm, snapLocal.value(),
                    restoredHeap, heapRestorer);
            boolean isNull = snapLocal.value() instanceof NullRef;

            if (jdiValue instanceof ObjectReference) {
                // Pin the object to prevent GC between resolve and setValue().
                // The window between resolve and disableCollection() is tiny
                // (microseconds), but under extreme GC pressure (e.g., ZGC with
                // small regions) the object can be collected before we pin it.
                // Retry up to MAX_PIN_ATTEMPTS times before giving up.
                boolean pinned = false;
                for (int attempt = 0; attempt < MAX_PIN_ATTEMPTS; attempt++) {
                    ObjectReference objRef = (ObjectReference) jdiValue;
                    try {
                        objRef.disableCollection();
                        pinnedRefs.add(objRef);
                        pinned = true;
                        break;
                    } catch (ObjectCollectedException alreadyGone) {
                        // Re-resolve from HeapObjectBridge for next attempt
                        jdiValue = JdiValueConverter.convertToJdiValue(vm, snapLocal.value(),
                                restoredHeap, heapRestorer);
                        if (!(jdiValue instanceof ObjectReference)) {
                            break; // resolved to non-object (shouldn't happen) — give up
                        }
                    }
                }
                if (!pinned && jdiValue instanceof ObjectReference) {
                    throw new RuntimeException(
                            "Failed to pin object reference for local '"
                            + snapLocal.name() + "' after " + MAX_PIN_ATTEMPTS
                            + " attempts — GC collected the object each time. "
                            + "This indicates extreme GC pressure during thread restore.");
                }
            }

            if (jdiValue != null || isNull) {
                entries.add(new LocalEntry(jdiLocal, jdiValue, isNull));
            }
        }

        try {
            for (LocalEntry entry : entries) {
                try {
                    jdiFrame.setValue(entry.jdiLocal(), entry.jdiValue());
                } catch (ClassNotLoadedException cnle) {
                    // On Java 8, JDI's classloader lookup can fail for bootstrap-loaded
                    // classes (like TimeUnit) when the declaring class uses the app
                    // classloader. The class IS loaded in the JVM, but JDI's
                    // LocalVariableImpl.findType() only searches the declaring type's
                    // classloader, not parent classloaders. Fixed in Java 9+.
                    //
                    // Bypass JDI's client-side type check by setting the slot value
                    // directly via reflection on JDI internals.
                    if (!setValueBypassTypeCheck(jdiFrame, entry.jdiLocal(), entry.jdiValue())) {
                        // Fallback: skip this local with a warning rather than
                        // crashing the entire restore
                        System.err.println("[DurableThreads] Warning: skipping local '"
                                + entry.jdiLocal().name() + "' — class '"
                                + cnle.className() + "' not resolvable by JDI classloader"
                                + " (Java 8 limitation)");
                    }
                } catch (InvalidTypeException e) {
                    // Lambda instances captured in the heap are restored as
                    // Object placeholders (hidden class can't be found). When
                    // JDI tries to set a local typed as a functional interface
                    // to an Object, the type check fails. Skip these silently —
                    // the frame's lambda dispatch uses the proxy receiver.
                    if (e.getMessage() != null && e.getMessage().contains("java.lang.Object")) {
                        continue;
                    }
                    throw new RuntimeException(
                            "Type mismatch setting local '" + entry.jdiLocal().name() + "' in "
                            + method.declaringType().name() + "." + method.name()
                            + ": " + e.getMessage(), e);
                } catch (IllegalArgumentException e) {
                    throw new RuntimeException(
                            "Cannot set local '" + entry.jdiLocal().name() + "' in "
                            + method.declaringType().name() + "." + method.name()
                            + " — not in scope at current BCP. "
                            + "Thread restore cannot proceed with incomplete state.",
                            e);
                } catch (com.sun.jdi.InternalException e) {
                    // JDWP Error 35 (INVALID_SLOT): variable not in scope at current BCI.
                    throw new RuntimeException(
                            "Failed to set local '" + entry.jdiLocal().name() + "' in "
                            + method.declaringType().name() + "." + method.name()
                            + ": " + e.getMessage(), e);
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Failed to set local '" + entry.jdiLocal().name() + "' in "
                            + method.declaringType().name() + "." + method.name()
                            + ": " + e.getMessage(), e);
                }
            }
        } finally {
            // Re-enable GC on all pinned references
            for (ObjectReference ref : pinnedRefs) {
                try {
                    ref.enableCollection();
                } catch (ObjectCollectedException ignored) {
                    // already collected after setValue — harmless
                }
            }
        }
    }

    /**
     * Pre-load all classes referenced by snapshot local variable types.
     * This prevents ClassNotLoadedException during setValue() later, which
     * is critical because forceLoadClass uses invokeMethod (which resumes
     * the thread and invalidates cached StackFrame references).
     */
    static void preloadSnapshotClasses(VirtualMachine vm, ThreadReference threadRef,
                                       ThreadSnapshot snapshot) {
        Set<String> classNames = new LinkedHashSet<>();
        for (FrameSnapshot frame : snapshot.frames()) {
            for (ai.jacc.durableThreads.snapshot.LocalVariable local : frame.locals()) {
                String sig = local.typeDescriptor();
                if (sig != null && sig.startsWith("L") && sig.endsWith(";")) {
                    // Convert type signature "Ljava/util/concurrent/TimeUnit;" to class name
                    String className = sig.substring(1, sig.length() - 1).replace('/', '.');
                    classNames.add(className);
                }
            }
        }

        for (String className : classNames) {
            // First, load the class locally (we're in the same JVM).
            // This ensures the class is loaded even if JDI's invokeMethod fails
            // (e.g., on Java 8 where invokeMethod fails for WAITING threads).
            try {
                Class.forName(className);
            } catch (ClassNotFoundException ignored) {}

            // Check if JDI can see the class now
            if (!vm.classesByName(className).isEmpty()) continue;

            // Try to force-load via JDI as well (for cross-classloader visibility)
            forceLoadClass(vm, threadRef, className);
        }
    }

    /**
     * Bypass JDI's client-side type check and set a stack frame slot value directly.
     *
     * <p>On Java 8, JDI's {@code StackFrameImpl.setValue()} calls
     * {@code LocalVariableImpl.findType()} which searches only the declaring type's
     * classloader. Bootstrap-loaded classes (like {@code TimeUnit}) can't be found
     * when the declaring type uses the app classloader, causing
     * {@code ClassNotLoadedException} even though the class IS loaded.</p>
     *
     * <p>This method uses reflection to access JDI internals and send the
     * JDWP StackFrame.SetValues command directly, bypassing the type check.</p>
     */
    static boolean setValueBypassTypeCheck(StackFrame frame,
                                           com.sun.jdi.LocalVariable local,
                                           Value value) {
        try {
            // Get the slot number from LocalVariableImpl
            java.lang.reflect.Method slotMethod = local.getClass().getDeclaredMethod("slot");
            slotMethod.setAccessible(true);
            int slot = (int) slotMethod.invoke(local);

            // StackFrameImpl has: ThreadReferenceImpl thread, long id
            java.lang.reflect.Field threadField = frame.getClass().getDeclaredField("thread");
            threadField.setAccessible(true);
            Object threadImpl = threadField.get(frame);

            java.lang.reflect.Field idField = frame.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            long frameId = (long) idField.get(frame);

            // Get the VirtualMachineImpl
            java.lang.reflect.Method vmMethod = frame.getClass().getMethod("virtualMachine");
            Object vmImpl = vmMethod.invoke(frame);

            // Build JDWP.StackFrame.SetValues.SlotInfo
            Class<?> slotInfoClass = Class.forName(
                    "com.sun.tools.jdi.JDWP$StackFrame$SetValues$SlotInfo");
            java.lang.reflect.Constructor<?> slotInfoCtor = slotInfoClass.getDeclaredConstructor(
                    int.class, Value.class);
            slotInfoCtor.setAccessible(true);
            Object slotInfo = slotInfoCtor.newInstance(slot, value);

            // Create SlotInfo array
            Object slotInfoArray = java.lang.reflect.Array.newInstance(slotInfoClass, 1);
            java.lang.reflect.Array.set(slotInfoArray, 0, slotInfo);

            // Call JDWP.StackFrame.SetValues.process(vm, thread, frameId, slotInfos)
            // Find the method by name since class hierarchy varies across JDK versions
            Class<?> setValuesClass = Class.forName(
                    "com.sun.tools.jdi.JDWP$StackFrame$SetValues");
            java.lang.reflect.Method processMethod = null;
            for (java.lang.reflect.Method m : setValuesClass.getDeclaredMethods()) {
                if (m.getName().equals("process") && m.getParameterTypes().length == 4) {
                    processMethod = m;
                    break;
                }
            }
            if (processMethod == null) return false;
            processMethod.setAccessible(true);
            processMethod.invoke(null, vmImpl, threadImpl, frameId, slotInfoArray);
            return true;
        } catch (Exception e) {
            // Reflection failed — different JDI internal structure than expected
            return false;
        }
    }

    private static void forceLoadClass(VirtualMachine vm, ThreadReference threadRef,
                                       String className) {
        try {
            // Find java.lang.Class in the target JVM
            List<ReferenceType> classTypes = vm.classesByName("java.lang.Class");
            if (classTypes.isEmpty()) return;
            ClassType classType = (ClassType) classTypes.get(0);

            // Find the forName(String) method
            List<Method> forNameMethods = classType.methodsByName("forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;");
            if (forNameMethods.isEmpty()) return;

            // Invoke Class.forName(className) in the target JVM
            StringReference classNameRef = vm.mirrorOf(className);
            classType.invokeMethod(threadRef, forNameMethods.get(0),
                    java.util.Collections.singletonList(classNameRef),
                    ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (Exception e) {
            // Non-fatal: the retry after this will produce a clear error
            System.err.println("[DurableThreads] Warning: failed to force-load class '"
                    + className + "' in target JVM: " + e.getMessage());
        }
    }
}
