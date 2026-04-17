package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.snapshot.ObjectKind;
import ai.jacc.durableThreads.snapshot.ObjectRef;
import ai.jacc.durableThreads.snapshot.ObjectSnapshot;
import ai.jacc.durableThreads.snapshot.PrimitiveRef;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable JDK types with final fields that can't be reflectively set on
 * Java 16+ ({@link java.math.BigDecimal}, {@link java.math.BigInteger},
 * {@link java.util.UUID}, {@link java.net.URI}, most of {@code java.time.*}).
 *
 * <p>Captured by reading internal fields via JDI and reconstructing the
 * canonical string representation. Restored by invoking each type's
 * {@code parse}, {@code valueOf}, {@code of}, or {@code create} factory.</p>
 */
public final class ImmutableJdkHandler implements TypeHandler {

    private static final Set<String> IMMUTABLES = new HashSet<>(Arrays.asList(
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

    /** Exposed so peer handlers (e.g., {@link EnumHandler}) can defer to us. */
    public static boolean isImmutable(String className) {
        return IMMUTABLES.contains(className);
    }

    @Override
    public boolean capturesType(ObjectReference objRef, ReferenceType refType, String className) {
        return IMMUTABLES.contains(className);
    }

    @Override
    public ObjectSnapshot capture(CaptureContext ctx, long snapId, ObjectReference objRef,
                                   ReferenceType refType, String className, String name) {
        String strValue = readAsString(objRef, refType, className);
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef(strValue));
        return new ObjectSnapshot(snapId, className, ObjectKind.STRING,
                fields, null, null, name);
    }

    @Override
    public boolean restoresSnapshot(ObjectSnapshot snap) {
        return snap.kind() == ObjectKind.STRING && IMMUTABLES.contains(snap.className());
    }

    @Override
    public Object allocate(RestoreContext ctx, ObjectSnapshot snap) {
        String value = extractString(snap);
        switch (snap.className()) {
            case "java.math.BigDecimal":      return new java.math.BigDecimal(value);
            case "java.math.BigInteger":      return new java.math.BigInteger(value);
            case "java.util.UUID":            return java.util.UUID.fromString(value);
            case "java.time.LocalDate":       return java.time.LocalDate.parse(value);
            case "java.time.LocalTime":       return java.time.LocalTime.parse(value);
            case "java.time.LocalDateTime":   return java.time.LocalDateTime.parse(value);
            case "java.time.Instant":         return java.time.Instant.parse(value);
            case "java.time.Duration":        return java.time.Duration.parse(value);
            case "java.time.ZonedDateTime":   return java.time.ZonedDateTime.parse(value);
            case "java.time.OffsetDateTime":  return java.time.OffsetDateTime.parse(value);
            case "java.time.Period":          return java.time.Period.parse(value);
            case "java.time.Year":            return java.time.Year.parse(value);
            case "java.time.YearMonth":       return java.time.YearMonth.parse(value);
            case "java.time.MonthDay":        return java.time.MonthDay.parse(value);
            case "java.time.ZoneOffset":      return java.time.ZoneOffset.of(value);
            case "java.time.ZoneId":          return java.time.ZoneId.of(value);
            case "java.net.URI":              return java.net.URI.create(value);
            default:
                throw new IllegalStateException("Unhandled immutable type: " + snap.className());
        }
    }

    // === Capture-side string reconstruction ===

    private static String readAsString(ObjectReference objRef, ReferenceType refType, String className) {
        try {
            switch (className) {
                case "java.math.BigDecimal": return readBigDecimal(objRef, refType);
                case "java.math.BigInteger": return readBigInteger(objRef, refType);
                case "java.util.UUID":       return readUuid(objRef, refType);
                case "java.net.URI":         return readUri(objRef, refType);
                default:                     return readJavaTime(objRef, refType, className);
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cannot capture immutable " + className + " via JDI field access. "
                    + "Thread freeze cannot proceed with incomplete state.", e);
        }
    }

    private static String readBigInteger(ObjectReference objRef, ReferenceType refType) {
        Field signumField = JdiFieldAccess.findField(refType, "signum");
        Field magField = JdiFieldAccess.findField(refType, "mag");
        if (signumField == null || magField == null) return "0";

        Value signumVal = objRef.getValue(signumField);
        int signum = (signumVal instanceof IntegerValue) ? ((IntegerValue) signumVal).value() : 0;
        if (signum == 0) return "0";

        Value magVal = objRef.getValue(magField);
        if (!(magVal instanceof ArrayReference)) return "0";
        ArrayReference magArr = (ArrayReference) magVal;

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

    private static String readBigDecimal(ObjectReference objRef, ReferenceType refType) {
        Field cacheField = JdiFieldAccess.findField(refType, "stringCache");
        if (cacheField != null) {
            Value cacheVal = objRef.getValue(cacheField);
            if (cacheVal instanceof StringReference) {
                return ((StringReference) cacheVal).value();
            }
        }

        Field intValField = JdiFieldAccess.findField(refType, "intVal");
        Field scaleField = JdiFieldAccess.findField(refType, "scale");
        Field intCompactField = JdiFieldAccess.findField(refType, "intCompact");

        int scale = 0;
        if (scaleField != null) {
            Value scaleVal = objRef.getValue(scaleField);
            scale = (scaleVal instanceof IntegerValue) ? ((IntegerValue) scaleVal).value() : 0;
        }

        if (intCompactField != null) {
            Value compactVal = objRef.getValue(intCompactField);
            if (compactVal instanceof LongValue) {
                long compact = ((LongValue) compactVal).value();
                if (compact != Long.MIN_VALUE) {
                    return java.math.BigDecimal.valueOf(compact, scale).toString();
                }
            }
        }

        if (intValField != null) {
            Value intValObj = objRef.getValue(intValField);
            if (intValObj instanceof ObjectReference) {
                ObjectReference biRef = (ObjectReference) intValObj;
                String biStr = readBigInteger(biRef, biRef.referenceType());
                return new java.math.BigDecimal(new java.math.BigInteger(biStr), scale).toString();
            }
        }
        return "0";
    }

    private static String readUuid(ObjectReference objRef, ReferenceType refType) {
        Field msbField = JdiFieldAccess.findField(refType, "mostSigBits");
        Field lsbField = JdiFieldAccess.findField(refType, "leastSigBits");
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

    private static String readUri(ObjectReference objRef, ReferenceType refType) {
        Field strField = JdiFieldAccess.findField(refType, "string");
        if (strField != null) {
            Value v = objRef.getValue(strField);
            if (v instanceof StringReference) {
                return ((StringReference) v).value();
            }
        }
        return "";
    }

    private static String readJavaTime(ObjectReference objRef, ReferenceType refType, String className) {
        switch (className) {
            case "java.time.LocalDate": {
                int year = JdiFieldAccess.getIntField(objRef, refType, "year");
                short month = JdiFieldAccess.getShortField(objRef, refType, "month");
                short day = JdiFieldAccess.getShortField(objRef, refType, "day");
                return java.time.LocalDate.of(year, month, day).toString();
            }
            case "java.time.LocalTime": {
                byte hour = JdiFieldAccess.getByteField(objRef, refType, "hour");
                byte minute = JdiFieldAccess.getByteField(objRef, refType, "minute");
                byte second = JdiFieldAccess.getByteField(objRef, refType, "second");
                int nano = JdiFieldAccess.getIntField(objRef, refType, "nano");
                return java.time.LocalTime.of(hour, minute, second, nano).toString();
            }
            case "java.time.LocalDateTime": {
                Field dateField = JdiFieldAccess.findField(refType, "date");
                Field timeField = JdiFieldAccess.findField(refType, "time");
                String datePart = "1970-01-01";
                String timePart = "00:00";
                if (dateField != null) {
                    Value dv = objRef.getValue(dateField);
                    if (dv instanceof ObjectReference) {
                        ObjectReference dateRef = (ObjectReference) dv;
                        datePart = readJavaTime(dateRef, dateRef.referenceType(), "java.time.LocalDate");
                    }
                }
                if (timeField != null) {
                    Value tv = objRef.getValue(timeField);
                    if (tv instanceof ObjectReference) {
                        ObjectReference timeRef = (ObjectReference) tv;
                        timePart = readJavaTime(timeRef, timeRef.referenceType(), "java.time.LocalTime");
                    }
                }
                return datePart + "T" + timePart;
            }
            case "java.time.Instant": {
                long seconds = JdiFieldAccess.getLongField(objRef, refType, "seconds");
                int nanos = JdiFieldAccess.getIntField(objRef, refType, "nanos");
                return java.time.Instant.ofEpochSecond(seconds, nanos).toString();
            }
            case "java.time.Duration": {
                long seconds = JdiFieldAccess.getLongField(objRef, refType, "seconds");
                int nanos = JdiFieldAccess.getIntField(objRef, refType, "nanos");
                return java.time.Duration.ofSeconds(seconds, nanos).toString();
            }
            case "java.time.ZonedDateTime": {
                Field dtField = JdiFieldAccess.findField(refType, "dateTime");
                Field zoneField = JdiFieldAccess.findField(refType, "zone");
                String dtStr = "1970-01-01T00:00";
                String zoneStr = "UTC";
                if (dtField != null) {
                    Value dv = objRef.getValue(dtField);
                    if (dv instanceof ObjectReference) {
                        ObjectReference dtRef = (ObjectReference) dv;
                        dtStr = readJavaTime(dtRef, dtRef.referenceType(), "java.time.LocalDateTime");
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
                Field dtField = JdiFieldAccess.findField(refType, "dateTime");
                Field offsetField = JdiFieldAccess.findField(refType, "offset");
                String dtStr = "1970-01-01T00:00";
                String offsetStr = "Z";
                if (dtField != null) {
                    Value dv = objRef.getValue(dtField);
                    if (dv instanceof ObjectReference) {
                        ObjectReference dtRef = (ObjectReference) dv;
                        dtStr = readJavaTime(dtRef, dtRef.referenceType(), "java.time.LocalDateTime");
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
                int years = JdiFieldAccess.getIntField(objRef, refType, "years");
                int months = JdiFieldAccess.getIntField(objRef, refType, "months");
                int days = JdiFieldAccess.getIntField(objRef, refType, "days");
                return java.time.Period.of(years, months, days).toString();
            }
            case "java.time.Year": {
                int year = JdiFieldAccess.getIntField(objRef, refType, "year");
                return java.time.Year.of(year).toString();
            }
            case "java.time.YearMonth": {
                int year = JdiFieldAccess.getIntField(objRef, refType, "year");
                int month = JdiFieldAccess.getIntField(objRef, refType, "month");
                return java.time.YearMonth.of(year, month).toString();
            }
            case "java.time.MonthDay": {
                int month = JdiFieldAccess.getIntField(objRef, refType, "month");
                int day = JdiFieldAccess.getIntField(objRef, refType, "day");
                return java.time.MonthDay.of(month, day).toString();
            }
            case "java.time.ZoneOffset":
                return readZoneOffset(objRef, refType);
            case "java.time.ZoneId":
                return readZoneId(objRef, refType);
            default:
                throw new RuntimeException("Unsupported immutable type: " + className);
        }
    }

    private static String readZoneOffset(ObjectReference objRef, ReferenceType refType) {
        int totalSeconds = JdiFieldAccess.getIntField(objRef, refType, "totalSeconds");
        return java.time.ZoneOffset.ofTotalSeconds(totalSeconds).toString();
    }

    private static String readZoneId(ObjectReference objRef, ReferenceType refType) {
        String actualClass = objRef.referenceType().name();
        if ("java.time.ZoneOffset".equals(actualClass)) {
            return readZoneOffset(objRef, objRef.referenceType());
        }
        Field idField = JdiFieldAccess.findField(objRef.referenceType(), "id");
        if (idField != null) {
            Value v = objRef.getValue(idField);
            if (v instanceof StringReference) {
                return ((StringReference) v).value();
            }
        }
        return "UTC";
    }

    private static String extractString(ObjectSnapshot snap) {
        ObjectRef valueRef = snap.fields().get("value");
        if (valueRef instanceof PrimitiveRef) {
            Object v = ((PrimitiveRef) valueRef).value();
            if (v instanceof String) return (String) v;
        }
        return "";
    }
}
