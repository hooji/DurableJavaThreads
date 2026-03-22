package ai.jacc.durableThreads.internal;

import com.sun.jdi.*;
import ai.jacc.durableThreads.exception.UncapturableTypeException;
import ai.jacc.durableThreads.snapshot.*;

import java.util.*;

/**
 * Walks the object graph via JDI (Java Debug Interface) to capture
 * objects reachable from local variables in a suspended thread.
 *
 * <p>Unlike {@link HeapWalker} which operates on live Java objects in the
 * same JVM, this walker reads object state through JDI mirrors, which
 * works even when the target thread is suspended.</p>
 *
 * <p>This handles objects that don't implement Serializable — we extract
 * field data directly via JDI reflection, bypassing Java serialization.</p>
 */
public final class JdiHeapWalker {

    private long nextId = 1;
    private final Map<Long, Long> jdiIdToSnapId = new HashMap<>();
    private final List<ObjectSnapshot> snapshots = new ArrayList<>();
    private final Map<Long, byte[]> classStructureHashes = new HashMap<>();

    /**
     * Maps JDI uniqueID to a user-assigned name. When an object with a matching
     * uniqueID is captured, the resulting ObjectSnapshot gets the name assigned.
     */
    private final Map<Long, String> jdiIdToName = new HashMap<>();

    /**
     * Register a JDI object reference as a named object. When this object is
     * encountered during heap walking, its ObjectSnapshot will carry the name.
     *
     * @param jdiUniqueId the JDI ObjectReference.uniqueID()
     * @param name        the user-assigned name
     */
    public void registerNamedObject(long jdiUniqueId, String name) {
        jdiIdToName.put(jdiUniqueId, name);
    }

    /**
     * Capture a JDI Value into the snapshot heap.
     *
     * @param value the JDI Value (may be null)
     * @return an ObjectRef pointing into the snapshot
     */
    public ObjectRef capture(Value value) {
        if (value == null) {
            return new NullRef();
        }

        if (value instanceof PrimitiveValue) {
            return capturePrimitive((PrimitiveValue) value);
        }

        if (value instanceof StringReference) {
            return new PrimitiveRef(((StringReference) value).value());
        }

        if (value instanceof ObjectReference) {
            return captureObject((ObjectReference) value);
        }

        return new NullRef();
    }

    /**
     * Get all captured object snapshots.
     */
    public List<ObjectSnapshot> getSnapshots() {
        return Collections.unmodifiableList(snapshots);
    }

    /**
     * Get the class structure hash for a given snapshot object ID.
     */
    public byte[] getClassStructureHash(long snapId) {
        return classStructureHashes.get(snapId);
    }

    private ObjectRef capturePrimitive(PrimitiveValue pv) {
        if (pv instanceof BooleanValue) return new PrimitiveRef(((BooleanValue) pv).value());
        if (pv instanceof ByteValue) return new PrimitiveRef(((ByteValue) pv).value());
        if (pv instanceof CharValue) return new PrimitiveRef(((CharValue) pv).value());
        if (pv instanceof ShortValue) return new PrimitiveRef(((ShortValue) pv).value());
        if (pv instanceof IntegerValue) return new PrimitiveRef(((IntegerValue) pv).value());
        if (pv instanceof LongValue) return new PrimitiveRef(((LongValue) pv).value());
        if (pv instanceof FloatValue) return new PrimitiveRef(((FloatValue) pv).value());
        if (pv instanceof DoubleValue) return new PrimitiveRef(((DoubleValue) pv).value());
        return new PrimitiveRef(0);
    }

    private ObjectRef captureObject(ObjectReference objRef) {
        long jdiId = objRef.uniqueID();

        // Already visited — return existing reference
        Long existingSnapId = jdiIdToSnapId.get(jdiId);
        if (existingSnapId != null) {
            return new HeapRef(existingSnapId);
        }

        long snapId = nextId++;
        jdiIdToSnapId.put(jdiId, snapId);

        // Check if this object has a user-assigned name
        String name = jdiIdToName.get(jdiId);

        ReferenceType refType = objRef.referenceType();
        String className = refType.name();

        if (objRef instanceof StringReference) {
            captureString(snapId, (StringReference) objRef, name);
        } else if (objRef instanceof ArrayReference) {
            // Convert JDI display name ("int[]", "java.lang.String[][]") to
            // JVM internal name ("[I", "[[Ljava.lang.String;") for Class.forName()
            String jvmArrayName = toJvmArrayName(className);
            captureArray(snapId, (ArrayReference) objRef, jvmArrayName, name);
        } else {
            captureRegularObject(snapId, objRef, refType, className, name);
        }

        return new HeapRef(snapId);
    }

    private void captureString(long snapId, StringReference sr, String name) {
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef(sr.value()));
        snapshots.add(new ObjectSnapshot(snapId, "java.lang.String",
                ObjectKind.STRING, fields, null, null, name));
    }

    private void captureArray(long snapId, ArrayReference arrRef, String className, String name) {
        List<Value> values = arrRef.getValues();
        ObjectRef[] elements = new ObjectRef[values.size()];

        for (int i = 0; i < values.size(); i++) {
            elements[i] = capture(values.get(i));
        }

        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.ARRAY, Collections.<String, ObjectRef>emptyMap(), elements, null, name));
    }

    /** Boxed primitive types whose 'value' field should be captured. */
    private static final Map<String, String> BOXED_PRIMITIVES = new HashMap<>();
    static {
        BOXED_PRIMITIVES.put("java.lang.Integer", "I");
        BOXED_PRIMITIVES.put("java.lang.Long", "J");
        BOXED_PRIMITIVES.put("java.lang.Double", "D");
        BOXED_PRIMITIVES.put("java.lang.Float", "F");
        BOXED_PRIMITIVES.put("java.lang.Short", "S");
        BOXED_PRIMITIVES.put("java.lang.Byte", "B");
        BOXED_PRIMITIVES.put("java.lang.Character", "C");
        BOXED_PRIMITIVES.put("java.lang.Boolean", "Z");
    }

    /**
     * Immutable JDK types that can be captured via their toString() representation
     * and reconstructed via parse/valueOf on restore. These have final fields that
     * cannot be set reflectively on Java 16+, so Objenesis won't work.
     */
    private static final Set<String> TOSTRING_IMMUTABLES = new HashSet<>(Arrays.asList(
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.UUID",
            "java.time.LocalDate",
            "java.time.LocalTime",
            "java.time.LocalDateTime",
            "java.time.Instant",
            "java.time.Duration",
            "java.time.ZonedDateTime",
            "java.time.OffsetDateTime",
            "java.time.Period",
            "java.time.Year",
            "java.time.YearMonth",
            "java.time.MonthDay",
            "java.time.ZoneId",
            "java.time.ZoneOffset",
            "java.net.URI"
    ));

    /** Packages whose internal fields should not be walked (JDK internals). */
    private static final String[] OPAQUE_PACKAGES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun."
    };

    private static boolean isOpaqueType(String className) {
        for (String prefix : OPAQUE_PACKAGES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Collection types we can capture by walking internals via JDI. */
    private static final Set<String> CAPTURABLE_COLLECTIONS = new HashSet<>(Arrays.asList(
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.ArrayDeque"
    ));

    /** JDK types whose content can be captured via toString(). */
    private static final Set<String> TOSTRING_CAPTURABLE = new HashSet<>(Arrays.asList(
            "java.lang.StringBuilder", "java.lang.StringBuffer"
    ));

    private void captureRegularObject(long snapId, ObjectReference objRef,
                                       ReferenceType refType, String className, String name) {
        // Known JDK collections: capture elements by walking internal storage
        if (CAPTURABLE_COLLECTIONS.contains(className)) {
            captureCollection(snapId, objRef, refType, className, name);
            return;
        }

        // StringBuilder/StringBuffer: capture content via toString()
        if (TOSTRING_CAPTURABLE.contains(className)) {
            captureStringBuilder(snapId, objRef, refType, className, name);
            return;
        }

        // Boxed primitives: extract the 'value' field so the wrapped
        // primitive survives freeze/restore. Without this, boxed values
        // inside collections (e.g., HashMap<String, Integer>) would be
        // restored as default values (0, false, etc.).
        if (BOXED_PRIMITIVES.containsKey(className)) {
            String fieldName = "value";
            Field valueField = refType.fieldByName(fieldName);
            if (valueField != null) {
                Value inner = objRef.getValue(valueField);
                ObjectRef innerRef = (inner instanceof PrimitiveValue)
                        ? capturePrimitive((PrimitiveValue) inner)
                        : new PrimitiveRef(0);
                Map<String, ObjectRef> fields = new LinkedHashMap<>();
                fields.put(className + "." + fieldName, innerRef);
                snapshots.add(new ObjectSnapshot(snapId, className,
                        ObjectKind.REGULAR, fields, null, null, name));
                return;
            }
        }

        // Immutable JDK types (BigDecimal, UUID, java.time.*, etc.):
        // capture via toString() so they can be reconstructed via parse/valueOf.
        if (TOSTRING_IMMUTABLES.contains(className)) {
            captureImmutableViaToString(snapId, objRef, refType, className, name);
            return;
        }

        // Enums (any package, including JDK enums like TimeUnit, Thread.State):
        // capture by constant name so they can be restored via Enum.valueOf().
        // Must be checked before both the opaque gate AND regular field walking,
        // because enum identity (==) would break if restored via Objenesis.
        if (isEnum(refType)) {
            captureEnum(snapId, objRef, refType, className, name);
            return;
        }

        // java.lang.Object itself has no fields — safe to capture as-is.
        // (Commonly appears as HashSet's internal PRESENT sentinel.)
        if ("java.lang.Object".equals(className)) {
            snapshots.add(new ObjectSnapshot(snapId, className,
                    ObjectKind.REGULAR, Collections.<String, ObjectRef>emptyMap(), null, null, name));
            return;
        }

        // FAIL-FAST: unknown JDK-internal types cannot be captured or restored
        // correctly. Throw now so the user can fix the problem, rather than
        // silently producing a snapshot that would restore with wrong values.
        if (isOpaqueType(className)) {
            throw new UncapturableTypeException(className,
                    getOpaqueTypeAdvice(className));
        }

        Map<String, ObjectRef> fields = new LinkedHashMap<>();

        // Walk class hierarchy via JDI
        ReferenceType current = refType;
        Set<String> visitedTypes = new HashSet<>();

        while (current != null) {
            String typeName = current.name();
            if (typeName.equals("java.lang.Object") || visitedTypes.contains(typeName)) break;
            visitedTypes.add(typeName);

            List<Field> jdiFields = current.fields();
            // Get all field values in one call (more efficient than per-field)
            Map<Field, Value> fieldValues = objRef.getValues(jdiFields);

            for (Field jdiField : jdiFields) {
                if (jdiField.isStatic()) continue;
                // Skip transient fields
                if (isTransient(jdiField)) continue;

                String fieldKey = typeName + "." + jdiField.name();
                Value value = fieldValues.get(jdiField);
                fields.put(fieldKey, capture(value));
            }

            // Walk to superclass
            if (current instanceof ClassType) {
                current = ((ClassType) current).superclass();
            } else {
                break;
            }
        }

        // Compute class structure hash
        byte[] structureHash = ClassStructureHasher.hashClassStructure(refType);
        classStructureHashes.put(snapId, structureHash);

        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.REGULAR, fields, null, structureHash, name));
    }

    /**
     * Capture a StringBuilder/StringBuffer by invoking toString() via JDI.
     * Stored as a STRING kind with the actual class name so HeapRestorer
     * can reconstruct it using the String constructor.
     */
    private void captureStringBuilder(long snapId, ObjectReference objRef,
                                       ReferenceType refType, String className, String name) {
        String content = "";
        try {
            Method toStringMethod = refType.methodsByName("toString", "()Ljava/lang/String;").get(0);
            // We need to invoke toString() on the suspended thread — but the object
            // is already fully formed, so we can read its internal state directly.
            // Instead of invoking methods (which requires a running thread), read
            // the count and value fields via JDI field access.
            com.sun.jdi.Field countField = findField(refType, "count");
            com.sun.jdi.Field valueField = findField(refType, "value");
            com.sun.jdi.Field coderField = findField(refType, "coder");

            if (valueField != null && countField != null) {
                Value countVal = objRef.getValue(countField);
                Value coderVal = coderField != null ? objRef.getValue(coderField) : null;
                Value valueVal = objRef.getValue(valueField);
                int count = (countVal instanceof IntegerValue) ? ((IntegerValue) countVal).value() : 0;
                int coder = (coderVal instanceof IntegerValue) ? ((IntegerValue) coderVal).value() : 0;

                if (valueVal instanceof ArrayReference && count > 0) {
                    ArrayReference arr = (ArrayReference) valueVal;
                    List<Value> vals = arr.getValues(0, Math.min(count, arr.length()));
                    if (coderField == null || vals.get(0) instanceof CharValue) {
                        // Java 8: value is a char[] array
                        char[] chars = new char[count];
                        for (int i = 0; i < count; i++) {
                            chars[i] = (vals.get(i) instanceof CharValue) ? ((CharValue) vals.get(i)).value() : 0;
                        }
                        content = new String(chars);
                    } else if (coder == 0) {
                        // Java 9+ LATIN1: each byte is a char
                        byte[] bytes = new byte[count];
                        for (int i = 0; i < count; i++) {
                            bytes[i] = (vals.get(i) instanceof ByteValue) ? ((ByteValue) vals.get(i)).value() : 0;
                        }
                        content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                    } else {
                        // Java 9+ UTF16: two bytes per char
                        byte[] bytes = new byte[count * 2];
                        List<Value> utf16Vals = arr.getValues(0, count * 2);
                        for (int i = 0; i < count * 2; i++) {
                            bytes[i] = (utf16Vals.get(i) instanceof ByteValue) ? ((ByteValue) utf16Vals.get(i)).value() : 0;
                        }
                        content = new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cannot read " + className + " content via JDI. "
                    + "Thread freeze cannot proceed with incomplete state.", e);
        }

        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef(content));
        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.STRING, fields, null, null, name));
    }

    /**
     * Capture an immutable JDK type by reading its string representation via JDI
     * field access. The string is stored as the "value" field so HeapRestorer can
     * reconstruct the object via parse/valueOf.
     *
     * <p>We read internal fields to build the string rather than invoking toString()
     * (which requires a running thread). Each type has its own internal layout.</p>
     */
    private void captureImmutableViaToString(long snapId, ObjectReference objRef,
                                              ReferenceType refType, String className, String name) {
        String strValue = readImmutableAsString(objRef, refType, className);
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef(strValue));
        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.STRING, fields, null, null, name));
    }

    /**
     * Read the string representation of an immutable JDK object via JDI field access.
     * Falls back to reading fields and formatting manually to avoid invoking methods
     * on the suspended thread.
     */
    private String readImmutableAsString(ObjectReference objRef,
                                          ReferenceType refType, String className) {
        try {
            switch (className) {
                case "java.math.BigDecimal":
                    return readBigDecimalAsString(objRef, refType);
                case "java.math.BigInteger":
                    return readBigIntegerAsString(objRef, refType);
                case "java.util.UUID":
                    return readUuidAsString(objRef, refType);
                case "java.net.URI":
                    return readUriAsString(objRef, refType);
                default:
                    // java.time.* types — read via internal fields
                    return readJavaTimeAsString(objRef, refType, className);
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cannot capture immutable " + className + " via JDI field access. "
                    + "Thread freeze cannot proceed with incomplete state.", e);
        }
    }

    private String readBigIntegerAsString(ObjectReference objRef, ReferenceType refType) {
        // BigInteger stores: int signum, int[] mag
        com.sun.jdi.Field signumField = findField(refType, "signum");
        com.sun.jdi.Field magField = findField(refType, "mag");
        if (signumField == null || magField == null) return "0";

        Value signumVal = objRef.getValue(signumField);
        int signum = (signumVal instanceof IntegerValue) ? ((IntegerValue) signumVal).value() : 0;
        if (signum == 0) return "0";

        Value magVal = objRef.getValue(magField);
        if (!(magVal instanceof ArrayReference)) return "0";
        ArrayReference magArr = (ArrayReference) magVal;

        // Reconstruct the magnitude as a hex string and parse
        int len = magArr.length();
        int[] mag = new int[len];
        List<Value> magValues = magArr.getValues();
        for (int i = 0; i < len; i++) {
            mag[i] = (magValues.get(i) instanceof IntegerValue)
                    ? ((IntegerValue) magValues.get(i)).value() : 0;
        }
        java.math.BigInteger bi = new java.math.BigInteger(signum, magToBytes(mag));
        return bi.toString();
    }

    private static byte[] magToBytes(int[] mag) {
        byte[] bytes = new byte[mag.length * 4];
        for (int i = 0; i < mag.length; i++) {
            bytes[i * 4]     = (byte) (mag[i] >>> 24);
            bytes[i * 4 + 1] = (byte) (mag[i] >>> 16);
            bytes[i * 4 + 2] = (byte) (mag[i] >>> 8);
            bytes[i * 4 + 3] = (byte) mag[i];
        }
        return bytes;
    }

    private String readBigDecimalAsString(ObjectReference objRef, ReferenceType refType) {
        // BigDecimal stores: BigInteger intVal, int scale, String stringCache (may be null)
        // Try stringCache first
        com.sun.jdi.Field cacheField = findField(refType, "stringCache");
        if (cacheField != null) {
            Value cacheVal = objRef.getValue(cacheField);
            if (cacheVal instanceof StringReference) {
                return ((StringReference) cacheVal).value();
            }
        }

        // Reconstruct from intVal + scale
        com.sun.jdi.Field intValField = findField(refType, "intVal");
        com.sun.jdi.Field scaleField = findField(refType, "scale");
        com.sun.jdi.Field intCompactField = findField(refType, "intCompact");

        int scale = 0;
        if (scaleField != null) {
            Value scaleVal = objRef.getValue(scaleField);
            scale = (scaleVal instanceof IntegerValue) ? ((IntegerValue) scaleVal).value() : 0;
        }

        // Try intCompact first (used for small values)
        if (intCompactField != null) {
            Value compactVal = objRef.getValue(intCompactField);
            if (compactVal instanceof LongValue) {
                long compact = ((LongValue) compactVal).value();
                if (compact != Long.MIN_VALUE) { // Long.MIN_VALUE means "use intVal"
                    return java.math.BigDecimal.valueOf(compact, scale).toString();
                }
            }
        }

        // Fall back to intVal BigInteger
        if (intValField != null) {
            Value intValObj = objRef.getValue(intValField);
            if (intValObj instanceof ObjectReference) {
                ObjectReference biRef = (ObjectReference) intValObj;
                String biStr = readBigIntegerAsString(biRef, biRef.referenceType());
                return new java.math.BigDecimal(new java.math.BigInteger(biStr), scale).toString();
            }
        }

        return "0";
    }

    private String readUuidAsString(ObjectReference objRef, ReferenceType refType) {
        com.sun.jdi.Field msbField = findField(refType, "mostSigBits");
        com.sun.jdi.Field lsbField = findField(refType, "leastSigBits");
        long msb = 0, lsb = 0;
        if (msbField != null) {
            Value v = objRef.getValue(msbField);
            msb = (v instanceof LongValue) ? ((LongValue) v).value() : 0;
        }
        if (lsbField != null) {
            Value v = objRef.getValue(lsbField);
            lsb = (v instanceof LongValue) ? ((LongValue) v).value() : 0;
        }
        return new java.util.UUID(msb, lsb).toString();
    }

    private String readUriAsString(ObjectReference objRef, ReferenceType refType) {
        com.sun.jdi.Field strField = findField(refType, "string");
        if (strField != null) {
            Value v = objRef.getValue(strField);
            if (v instanceof StringReference) {
                return ((StringReference) v).value();
            }
        }
        return "";
    }

    private String readJavaTimeAsString(ObjectReference objRef,
                                         ReferenceType refType, String className) {
        switch (className) {
            case "java.time.LocalDate": {
                int year = getIntField(objRef, refType, "year");
                short month = getShortField(objRef, refType, "month");
                short day = getShortField(objRef, refType, "day");
                return java.time.LocalDate.of(year, month, day).toString();
            }
            case "java.time.LocalTime": {
                byte hour = getByteField(objRef, refType, "hour");
                byte minute = getByteField(objRef, refType, "minute");
                byte second = getByteField(objRef, refType, "second");
                int nano = getIntField(objRef, refType, "nano");
                return java.time.LocalTime.of(hour, minute, second, nano).toString();
            }
            case "java.time.LocalDateTime": {
                // LocalDateTime has date (LocalDate) and time (LocalTime) fields
                com.sun.jdi.Field dateField = findField(refType, "date");
                com.sun.jdi.Field timeField = findField(refType, "time");
                String datePart = "1970-01-01";
                String timePart = "00:00";
                if (dateField != null) {
                    Value dv = objRef.getValue(dateField);
                    if (dv instanceof ObjectReference) {
                        ObjectReference dateRef = (ObjectReference) dv;
                        datePart = readJavaTimeAsString(dateRef, dateRef.referenceType(), "java.time.LocalDate");
                    }
                }
                if (timeField != null) {
                    Value tv = objRef.getValue(timeField);
                    if (tv instanceof ObjectReference) {
                        ObjectReference timeRef = (ObjectReference) tv;
                        timePart = readJavaTimeAsString(timeRef, timeRef.referenceType(), "java.time.LocalTime");
                    }
                }
                return datePart + "T" + timePart;
            }
            case "java.time.Instant": {
                long seconds = getLongField(objRef, refType, "seconds");
                int nanos = getIntField(objRef, refType, "nanos");
                return java.time.Instant.ofEpochSecond(seconds, nanos).toString();
            }
            case "java.time.Duration": {
                long seconds = getLongField(objRef, refType, "seconds");
                int nanos = getIntField(objRef, refType, "nanos");
                return java.time.Duration.ofSeconds(seconds, nanos).toString();
            }
            case "java.time.ZonedDateTime": {
                // ZonedDateTime has dateTime (LocalDateTime), offset (ZoneOffset), zone (ZoneId)
                com.sun.jdi.Field dtField = findField(refType, "dateTime");
                com.sun.jdi.Field zoneField = findField(refType, "zone");
                String dtStr = "1970-01-01T00:00";
                String zoneStr = "UTC";
                if (dtField != null) {
                    Value dv = objRef.getValue(dtField);
                    if (dv instanceof ObjectReference) {
                        ObjectReference dtRef = (ObjectReference) dv;
                        dtStr = readJavaTimeAsString(dtRef, dtRef.referenceType(), "java.time.LocalDateTime");
                    }
                }
                if (zoneField != null) {
                    Value zv = objRef.getValue(zoneField);
                    if (zv instanceof ObjectReference) {
                        ObjectReference zoneRef = (ObjectReference) zv;
                        zoneStr = readZoneId(zoneRef, zoneRef.referenceType());
                    }
                }
                return java.time.ZonedDateTime.of(
                        java.time.LocalDateTime.parse(dtStr),
                        java.time.ZoneId.of(zoneStr)).toString();
            }
            case "java.time.OffsetDateTime": {
                com.sun.jdi.Field dtField = findField(refType, "dateTime");
                com.sun.jdi.Field offsetField = findField(refType, "offset");
                String dtStr = "1970-01-01T00:00";
                String offsetStr = "Z";
                if (dtField != null) {
                    Value dv = objRef.getValue(dtField);
                    if (dv instanceof ObjectReference) {
                        ObjectReference dtRef = (ObjectReference) dv;
                        dtStr = readJavaTimeAsString(dtRef, dtRef.referenceType(), "java.time.LocalDateTime");
                    }
                }
                if (offsetField != null) {
                    Value ov = objRef.getValue(offsetField);
                    if (ov instanceof ObjectReference) {
                        ObjectReference oRef = (ObjectReference) ov;
                        offsetStr = readZoneOffset(oRef, oRef.referenceType());
                    }
                }
                return java.time.OffsetDateTime.of(
                        java.time.LocalDateTime.parse(dtStr),
                        java.time.ZoneOffset.of(offsetStr)).toString();
            }
            case "java.time.Period": {
                int years = getIntField(objRef, refType, "years");
                int months = getIntField(objRef, refType, "months");
                int days = getIntField(objRef, refType, "days");
                return java.time.Period.of(years, months, days).toString();
            }
            case "java.time.Year": {
                int year = getIntField(objRef, refType, "year");
                return java.time.Year.of(year).toString();
            }
            case "java.time.YearMonth": {
                int year = getIntField(objRef, refType, "year");
                int month = getIntField(objRef, refType, "month");
                return java.time.YearMonth.of(year, month).toString();
            }
            case "java.time.MonthDay": {
                int month = getIntField(objRef, refType, "month");
                int day = getIntField(objRef, refType, "day");
                return java.time.MonthDay.of(month, day).toString();
            }
            case "java.time.ZoneOffset": {
                return readZoneOffset(objRef, refType);
            }
            case "java.time.ZoneId": {
                return readZoneId(objRef, refType);
            }
            default:
                throw new RuntimeException("Unsupported immutable type: " + className);
        }
    }

    private String readZoneOffset(ObjectReference objRef, ReferenceType refType) {
        int totalSeconds = getIntField(objRef, refType, "totalSeconds");
        return java.time.ZoneOffset.ofTotalSeconds(totalSeconds).toString();
    }

    private String readZoneId(ObjectReference objRef, ReferenceType refType) {
        // ZoneId is abstract — concrete impls are ZoneOffset and ZoneRegion
        String actualClass = objRef.referenceType().name();
        if (actualClass.equals("java.time.ZoneOffset")) {
            return readZoneOffset(objRef, objRef.referenceType());
        }
        // ZoneRegion has an "id" field
        com.sun.jdi.Field idField = findField(objRef.referenceType(), "id");
        if (idField != null) {
            Value v = objRef.getValue(idField);
            if (v instanceof StringReference) {
                return ((StringReference) v).value();
            }
        }
        return "UTC";
    }

    private int getIntField(ObjectReference obj, ReferenceType type, String fieldName) {
        com.sun.jdi.Field f = findField(type, fieldName);
        if (f == null) return 0;
        Value v = obj.getValue(f);
        return (v instanceof IntegerValue) ? ((IntegerValue) v).value() : 0;
    }

    private long getLongField(ObjectReference obj, ReferenceType type, String fieldName) {
        com.sun.jdi.Field f = findField(type, fieldName);
        if (f == null) return 0;
        Value v = obj.getValue(f);
        return (v instanceof LongValue) ? ((LongValue) v).value() : 0;
    }

    private short getShortField(ObjectReference obj, ReferenceType type, String fieldName) {
        com.sun.jdi.Field f = findField(type, fieldName);
        if (f == null) return 0;
        Value v = obj.getValue(f);
        return (v instanceof ShortValue) ? ((ShortValue) v).value() : 0;
    }

    private byte getByteField(ObjectReference obj, ReferenceType type, String fieldName) {
        com.sun.jdi.Field f = findField(type, fieldName);
        if (f == null) return 0;
        Value v = obj.getValue(f);
        return (v instanceof ByteValue) ? ((ByteValue) v).value() : 0;
    }

    /**
     * Check whether a JDI type is an enum (extends java.lang.Enum).
     */
    private static boolean isEnum(ReferenceType refType) {
        if (!(refType instanceof ClassType)) return false;
        ClassType ct = ((ClassType) refType).superclass();
        while (ct != null) {
            if (ct.name().equals("java.lang.Enum")) return true;
            ct = ct.superclass();
        }
        return false;
    }

    /**
     * Capture an enum constant by its name. Stored as STRING kind so that
     * HeapRestorer can reconstruct via {@code Enum.valueOf(Class, name)}.
     */
    private void captureEnum(long snapId, ObjectReference objRef,
                              ReferenceType refType, String className, String name) {
        // On Java 8, enum constants with method overrides are anonymous subclasses
        // (e.g., TimeUnit$3 instead of TimeUnit). Use the actual enum class name
        // so that Enum.valueOf() works during restore.
        String enumClassName = className;
        if (refType instanceof ClassType) {
            ClassType ct = (ClassType) refType;
            while (ct.superclass() != null) {
                if (ct.superclass().name().equals("java.lang.Enum")) {
                    enumClassName = ct.name();
                    break;
                }
                ct = ct.superclass();
            }
        }

        // Read the 'name' field from java.lang.Enum
        String constantName = "";
        com.sun.jdi.Field nameField = findField(refType, "name");
        if (nameField != null) {
            Value v = objRef.getValue(nameField);
            if (v instanceof StringReference) {
                constantName = ((StringReference) v).value();
            }
        }
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef(constantName));
        snapshots.add(new ObjectSnapshot(snapId, enumClassName,
                ObjectKind.STRING, fields, null, null, name));
    }

    /**
     * Return user-facing advice for why a specific opaque JDK type can't be
     * frozen and what the user should do about it.
     */
    private static String getOpaqueTypeAdvice(String className) {
        // Optional types
        if (className.equals("java.util.Optional")
                || className.startsWith("java.util.Optional")) {
            return "Optional<T> cannot be frozen because its final 'value' field "
                    + "cannot be set after construction. Use T directly (with null for "
                    + "empty) instead of wrapping in Optional.";
        }

        // Unmodifiable/immutable collection wrappers
        if (className.startsWith("java.util.Collections$Unmodifiable")
                || className.startsWith("java.util.Collections$Singleton")
                || className.startsWith("java.util.Collections$Empty")
                || className.startsWith("java.util.ImmutableCollections$")) {
            return "Unmodifiable/immutable collection wrappers cannot be frozen "
                    + "because their immutability contract cannot be preserved on "
                    + "restore. Use a mutable collection (ArrayList, HashMap, etc.) "
                    + "in the frozen thread instead.";
        }

        // Thread, ClassLoader, etc. — fundamentally non-serializable
        if (className.equals("java.lang.Thread")
                || className.equals("java.lang.ThreadGroup")) {
            return "Thread objects cannot be frozen. Remove Thread references "
                    + "from local variables reachable by the frozen thread.";
        }
        if (className.contains("ClassLoader")) {
            return "ClassLoader objects cannot be frozen. Remove ClassLoader "
                    + "references from local variables reachable by the frozen thread.";
        }

        // I/O and network types
        if (className.startsWith("java.io.") || className.startsWith("java.nio.")) {
            return "I/O types (streams, channels, files) hold native resources "
                    + "that cannot be serialized. Remove I/O object references from "
                    + "local variables reachable by the frozen thread, or close them "
                    + "before freezing.";
        }
        if (className.startsWith("java.net.") && !className.equals("java.net.URI")) {
            return "Network types (sockets, connections) hold native resources "
                    + "that cannot be serialized. Remove network object references "
                    + "from local variables reachable by the frozen thread.";
        }

        // Regex Pattern
        if (className.equals("java.util.regex.Pattern")) {
            return "Pattern cannot be frozen because its compiled internal state "
                    + "cannot be restored. Store the pattern string instead and "
                    + "recompile with Pattern.compile() after restore.";
        }

        // Generic fallback
        return "This is a JDK-internal type whose fields cannot be read or "
                + "restored correctly. Avoid using this type in local variables or "
                + "fields reachable from the frozen thread. If you believe this type "
                + "should be supported, please file an issue.";
    }

    /**
     * Capture a JDK collection by walking its internal storage via JDI.
     * For List/Set: elements stored in arrayElements.
     * For Map: key/value pairs interleaved in arrayElements (k0,v0,k1,v1,...).
     */
    private void captureCollection(long snapId, ObjectReference objRef,
                                    ReferenceType refType, String className, String name) {
        if (className.contains("Map")) {
            captureMap(snapId, objRef, refType, className, name);
        } else {
            captureListOrSet(snapId, objRef, refType, className, name);
        }
    }

    private void captureListOrSet(long snapId, ObjectReference objRef,
                                   ReferenceType refType, String className, String name) {
        List<ObjectRef> elements = new ArrayList<>();

        // Try to read size and elementData (ArrayList) or navigate linked structure
        com.sun.jdi.Field sizeField = findField(refType, "size");
        com.sun.jdi.Field elementDataField = findField(refType, "elementData");

        if (elementDataField != null && sizeField != null) {
            // ArrayList-like: read elementData array up to size
            Value sizeVal = objRef.getValue(sizeField);
            int size = (sizeVal instanceof IntegerValue) ? ((IntegerValue) sizeVal).value() : 0;
            Value dataVal = objRef.getValue(elementDataField);
            if (dataVal instanceof ArrayReference) {
                ArrayReference arr = (ArrayReference) dataVal;
                for (int i = 0; i < Math.min(size, arr.length()); i++) {
                    elements.add(capture(arr.getValue(i)));
                }
            }
        } else {
            // HashSet wraps a HashMap — read the map's keys
            com.sun.jdi.Field mapField = findField(refType, "map");
            if (mapField != null) {
                Value mapVal = objRef.getValue(mapField);
                if (mapVal instanceof ObjectReference) {
                    List<ObjectRef> pairs = extractMapEntries((ObjectReference) mapVal);
                    // HashSet: only keep keys (even indices)
                    for (int i = 0; i < pairs.size(); i += 2) {
                        elements.add(pairs.get(i));
                    }
                }
            }
        }

        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.COLLECTION, Collections.<String, ObjectRef>emptyMap(), elements.toArray(new ObjectRef[0]), null, name));
    }

    private void captureMap(long snapId, ObjectReference objRef,
                             ReferenceType refType, String className, String name) {
        List<ObjectRef> pairs = extractMapEntries(objRef);
        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.COLLECTION, Collections.<String, ObjectRef>emptyMap(), pairs.toArray(new ObjectRef[0]), null, name));
    }

    /** Extract key/value pairs from a HashMap-like structure via JDI. */
    private List<ObjectRef> extractMapEntries(ObjectReference mapRef) {
        List<ObjectRef> pairs = new ArrayList<>();
        try {
            ReferenceType mapType = mapRef.referenceType();
            com.sun.jdi.Field tableField = findField(mapType, "table");
            if (tableField == null) return pairs;

            Value tableVal = mapRef.getValue(tableField);
            if (!(tableVal instanceof ArrayReference)) return pairs;
            ArrayReference table = (ArrayReference) tableVal;

            for (int i = 0; i < table.length(); i++) {
                Value bucketVal = table.getValue(i);
                if (!(bucketVal instanceof ObjectReference)) continue;
                ObjectReference node = (ObjectReference) bucketVal;

                // Walk the linked list in each bucket
                while (node != null) {
                    com.sun.jdi.Field keyField = findField(node.referenceType(), "key");
                    com.sun.jdi.Field valField = findField(node.referenceType(), "val");
                    if (valField == null) valField = findField(node.referenceType(), "value");

                    if (keyField != null && valField != null) {
                        pairs.add(capture(node.getValue(keyField)));
                        pairs.add(capture(node.getValue(valField)));
                    }

                    com.sun.jdi.Field nextField = findField(node.referenceType(), "next");
                    if (nextField != null) {
                        Value nextVal = node.getValue(nextField);
                        node = (nextVal instanceof ObjectReference) ? (ObjectReference) nextVal : null;
                    } else {
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract map entries via JDI", e);
        }
        return pairs;
    }

    private static com.sun.jdi.Field findField(ReferenceType type, String name) {
        com.sun.jdi.Field f = type.fieldByName(name);
        if (f != null) return f;
        if (type instanceof ClassType && ((ClassType) type).superclass() != null) {
            return findField(((ClassType) type).superclass(), name);
        }
        return null;
    }

    private static boolean isTransient(Field field) {
        // JDI Field doesn't have isTransient() directly, check modifiers
        return (field.modifiers() & 0x0080) != 0; // ACC_TRANSIENT = 0x0080
    }

    /**
     * Convert JDI array type display name to JVM internal name.
     * JDI gives "int[]", "byte[][]", "java.lang.String[]" etc.
     * Class.forName needs "[I", "[[B", "[Ljava.lang.String;" etc.
     */
    static String toJvmArrayName(String jdiName) {
        // Count dimensions
        int dims = 0;
        String base = jdiName;
        while (base.endsWith("[]")) {
            dims++;
            base = base.substring(0, base.length() - 2);
        }
        if (dims == 0) return jdiName; // not an array

        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < dims; i++) {
            prefix.append('[');
        }
        String descriptor;
        if ("boolean".equals(base)) descriptor = "Z";
        else if ("byte".equals(base)) descriptor = "B";
        else if ("char".equals(base)) descriptor = "C";
        else if ("short".equals(base)) descriptor = "S";
        else if ("int".equals(base)) descriptor = "I";
        else if ("long".equals(base)) descriptor = "J";
        else if ("float".equals(base)) descriptor = "F";
        else if ("double".equals(base)) descriptor = "D";
        else descriptor = "L" + base + ";";
        return prefix.toString() + descriptor;
    }
}
