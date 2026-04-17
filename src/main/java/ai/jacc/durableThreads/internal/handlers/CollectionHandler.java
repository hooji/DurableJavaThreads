package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.internal.ObjenesisHolder;
import ai.jacc.durableThreads.snapshot.ObjectKind;
import ai.jacc.durableThreads.snapshot.ObjectRef;
import ai.jacc.durableThreads.snapshot.ObjectSnapshot;
import ai.jacc.durableThreads.snapshot.PrimitiveRef;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Supported JDK collections: {@code ArrayList}, {@code LinkedList},
 * {@code HashSet}, {@code LinkedHashSet}, {@code TreeSet}, {@code HashMap},
 * {@code LinkedHashMap}, {@code TreeMap}, {@code ConcurrentHashMap},
 * {@code ArrayDeque}, {@code EnumMap}, and {@code EnumSet} (including the
 * internal {@code RegularEnumSet}/{@code JumboEnumSet} subtypes).
 *
 * <p>Capture walks the internal storage of each type directly via JDI.
 * Restore creates an empty instance of the exact type and re-inserts
 * elements (which re-hashes / re-sorts them for the current JVM).</p>
 */
public final class CollectionHandler implements TypeHandler {

    private static final Set<String> CAPTURABLE = new HashSet<>(Arrays.asList(
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.ArrayDeque",
            "java.util.EnumMap"
    ));

    private static boolean isEnumSet(String className) {
        return "java.util.EnumSet".equals(className)
                || "java.util.RegularEnumSet".equals(className)
                || "java.util.JumboEnumSet".equals(className);
    }

    @Override
    public boolean capturesType(ObjectReference objRef, ReferenceType refType, String className) {
        return CAPTURABLE.contains(className) || isEnumSet(className);
    }

    @Override
    public ObjectSnapshot capture(CaptureContext ctx, long snapId, ObjectReference objRef,
                                   ReferenceType refType, String className, String name) {
        if (isEnumSet(className)) return captureEnumSet(ctx, snapId, objRef, refType, name);
        if ("java.util.EnumMap".equals(className)) return captureEnumMap(ctx, snapId, objRef, refType, name);
        if (className.contains("Map")) return captureMap(ctx, snapId, objRef, className, name);
        return captureListOrSet(ctx, snapId, objRef, refType, className, name);
    }

    @Override
    public boolean restoresSnapshot(ObjectSnapshot snap) {
        return snap.kind() == ObjectKind.COLLECTION;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object allocate(RestoreContext ctx, ObjectSnapshot snap) {
        String className = snap.className();
        if ("java.util.ArrayList".equals(className)) return new java.util.ArrayList<>();
        if ("java.util.LinkedList".equals(className)) return new java.util.LinkedList<>();
        if ("java.util.HashSet".equals(className)) return new java.util.HashSet<>();
        if ("java.util.LinkedHashSet".equals(className)) return new java.util.LinkedHashSet<>();
        if ("java.util.TreeSet".equals(className)) return new java.util.TreeSet<>();
        if ("java.util.HashMap".equals(className)) return new java.util.HashMap<>();
        if ("java.util.LinkedHashMap".equals(className)) return new java.util.LinkedHashMap<>();
        if ("java.util.TreeMap".equals(className)) return new java.util.TreeMap<>();
        if ("java.util.concurrent.ConcurrentHashMap".equals(className)) return new java.util.concurrent.ConcurrentHashMap<>();
        if ("java.util.ArrayDeque".equals(className)) return new java.util.ArrayDeque<>();
        if ("java.util.EnumSet".equals(className)) {
            ObjectRef etRef = snap.fields().get("elementType");
            if (etRef instanceof PrimitiveRef) {
                String enumTypeName = (String) ((PrimitiveRef) etRef).value();
                try {
                    Class enumClass = Class.forName(enumTypeName);
                    return java.util.EnumSet.noneOf(enumClass);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Cannot load enum type for EnumSet: " + enumTypeName, e);
                }
            }
        }
        if ("java.util.EnumMap".equals(className)) {
            ObjectRef ktRef = snap.fields().get("keyType");
            if (ktRef instanceof PrimitiveRef) {
                String keyTypeName = (String) ((PrimitiveRef) ktRef).value();
                try {
                    Class enumClass = Class.forName(keyTypeName);
                    return new java.util.EnumMap(enumClass);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Cannot load enum type for EnumMap: " + keyTypeName, e);
                }
            }
        }
        try {
            Class<?> clazz = Class.forName(className);
            return ObjenesisHolder.get().newInstance(clazz);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot create collection: " + className, e);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void populate(RestoreContext ctx, Object obj, ObjectSnapshot snap) {
        ObjectRef[] elements = snap.arrayElements();
        if (elements == null || elements.length == 0) return;

        if (snap.className().contains("Map")) {
            if (!(obj instanceof java.util.Map)) {
                throw new RuntimeException("Expected Map instance for " + snap.className()
                        + " but got " + obj.getClass().getName());
            }
            java.util.Map map = (java.util.Map) obj;
            for (int i = 0; i + 1 < elements.length; i += 2) {
                Object key = ctx.resolve(elements[i]);
                Object value = ctx.resolve(elements[i + 1]);
                map.put(key, value);
            }
        } else {
            if (!(obj instanceof java.util.Collection)) {
                throw new RuntimeException("Expected Collection instance for " + snap.className()
                        + " but got " + obj.getClass().getName());
            }
            java.util.Collection coll = (java.util.Collection) obj;
            for (ObjectRef element : elements) {
                coll.add(ctx.resolve(element));
            }
        }
    }

    // === Capture implementations ===

    private static ObjectSnapshot captureEnumSet(CaptureContext ctx, long snapId, ObjectReference objRef,
                                                  ReferenceType refType, String name) {
        List<ObjectRef> elements = new ArrayList<>();
        String elementTypeName = "";

        Field elementTypeField = JdiFieldAccess.findField(refType, "elementType");
        if (elementTypeField != null) {
            Value etVal = objRef.getValue(elementTypeField);
            if (etVal instanceof ClassObjectReference) {
                elementTypeName = ((ClassObjectReference) etVal).reflectedType().name();
            }
        }

        Field universeField = JdiFieldAccess.findField(refType, "universe");
        if (universeField != null) {
            Value uniVal = objRef.getValue(universeField);
            if (uniVal instanceof ArrayReference) {
                ArrayReference universe = (ArrayReference) uniVal;

                Field elementsField = JdiFieldAccess.findField(refType, "elements");
                if (elementsField != null) {
                    Value elemsVal = objRef.getValue(elementsField);
                    if (elemsVal instanceof LongValue) {
                        // RegularEnumSet: single long bitmask
                        long bits = ((LongValue) elemsVal).value();
                        for (int i = 0; i < universe.length() && i < 64; i++) {
                            if ((bits & (1L << i)) != 0) {
                                elements.add(ctx.capture(universe.getValue(i)));
                            }
                        }
                    } else if (elemsVal instanceof ArrayReference) {
                        // JumboEnumSet: long[] bitmask array
                        ArrayReference bitsArr = (ArrayReference) elemsVal;
                        for (int wordIdx = 0; wordIdx < bitsArr.length(); wordIdx++) {
                            Value wordVal = bitsArr.getValue(wordIdx);
                            long bits = (wordVal instanceof LongValue) ? ((LongValue) wordVal).value() : 0;
                            for (int bit = 0; bit < 64; bit++) {
                                int enumIdx = wordIdx * 64 + bit;
                                if (enumIdx >= universe.length()) break;
                                if ((bits & (1L << bit)) != 0) {
                                    elements.add(ctx.capture(universe.getValue(enumIdx)));
                                }
                            }
                        }
                    }
                }
            }
        }

        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("elementType", new PrimitiveRef(elementTypeName));
        return new ObjectSnapshot(snapId, "java.util.EnumSet", ObjectKind.COLLECTION,
                fields, elements.toArray(new ObjectRef[0]), null, name);
    }

    private static ObjectSnapshot captureEnumMap(CaptureContext ctx, long snapId, ObjectReference objRef,
                                                  ReferenceType refType, String name) {
        List<ObjectRef> pairs = new ArrayList<>();
        String keyTypeName = "";

        Field keyTypeField = JdiFieldAccess.findField(refType, "keyType");
        if (keyTypeField != null) {
            Value ktVal = objRef.getValue(keyTypeField);
            if (ktVal instanceof ClassObjectReference) {
                keyTypeName = ((ClassObjectReference) ktVal).reflectedType().name();
            }
        }

        Field keyUniField = JdiFieldAccess.findField(refType, "keyUniverse");
        Field valsField = JdiFieldAccess.findField(refType, "vals");
        if (keyUniField != null && valsField != null) {
            Value kuVal = objRef.getValue(keyUniField);
            Value vsVal = objRef.getValue(valsField);
            if (kuVal instanceof ArrayReference && vsVal instanceof ArrayReference) {
                ArrayReference keys = (ArrayReference) kuVal;
                ArrayReference vals = (ArrayReference) vsVal;
                int len = Math.min(keys.length(), vals.length());
                for (int i = 0; i < len; i++) {
                    Value v = vals.getValue(i);
                    if (v != null) {
                        pairs.add(ctx.capture(keys.getValue(i)));
                        pairs.add(ctx.capture(v));
                    }
                }
            }
        }

        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("keyType", new PrimitiveRef(keyTypeName));
        return new ObjectSnapshot(snapId, "java.util.EnumMap", ObjectKind.COLLECTION,
                fields, pairs.toArray(new ObjectRef[0]), null, name);
    }

    private static ObjectSnapshot captureListOrSet(CaptureContext ctx, long snapId, ObjectReference objRef,
                                                    ReferenceType refType, String className, String name) {
        List<ObjectRef> elements = new ArrayList<>();

        Field elementDataField = JdiFieldAccess.findField(refType, "elementData");
        Field sizeField = JdiFieldAccess.findField(refType, "size");

        if (elementDataField != null && sizeField != null) {
            Value sizeVal = objRef.getValue(sizeField);
            int size = (sizeVal instanceof IntegerValue) ? ((IntegerValue) sizeVal).value() : 0;
            Value dataVal = objRef.getValue(elementDataField);
            if (dataVal instanceof ArrayReference) {
                ArrayReference arr = (ArrayReference) dataVal;
                for (int i = 0; i < Math.min(size, arr.length()); i++) {
                    elements.add(ctx.capture(arr.getValue(i)));
                }
            }
        } else if (JdiFieldAccess.findField(refType, "first") != null) {
            // LinkedList: walk the first → next node chain
            captureLinkedList(ctx, objRef, refType, elements);
        } else if (JdiFieldAccess.findField(refType, "m") != null) {
            // TreeSet: wraps a NavigableMap (TreeMap) — extract keys
            Field mField = JdiFieldAccess.findField(refType, "m");
            Value mVal = objRef.getValue(mField);
            if (mVal instanceof ObjectReference) {
                List<ObjectRef> pairs = extractMapEntries(ctx, (ObjectReference) mVal);
                for (int i = 0; i < pairs.size(); i += 2) {
                    elements.add(pairs.get(i));
                }
            }
        } else if (JdiFieldAccess.findField(refType, "elements") != null
                && JdiFieldAccess.findField(refType, "head") != null) {
            // ArrayDeque: circular buffer
            captureArrayDeque(ctx, objRef, refType, elements);
        } else {
            // HashSet/LinkedHashSet: wraps a HashMap — read the map's keys
            Field mapField = JdiFieldAccess.findField(refType, "map");
            if (mapField != null) {
                Value mapVal = objRef.getValue(mapField);
                if (mapVal instanceof ObjectReference) {
                    List<ObjectRef> pairs = extractMapEntries(ctx, (ObjectReference) mapVal);
                    for (int i = 0; i < pairs.size(); i += 2) {
                        elements.add(pairs.get(i));
                    }
                }
            }
        }

        return new ObjectSnapshot(snapId, className, ObjectKind.COLLECTION,
                Collections.<String, ObjectRef>emptyMap(),
                elements.toArray(new ObjectRef[0]), null, name);
    }

    private static void captureLinkedList(CaptureContext ctx, ObjectReference listRef,
                                           ReferenceType refType, List<ObjectRef> elements) {
        Field firstField = JdiFieldAccess.findField(refType, "first");
        if (firstField == null) return;

        Value nodeVal = listRef.getValue(firstField);
        while (nodeVal instanceof ObjectReference) {
            ObjectReference node = (ObjectReference) nodeVal;
            Field itemField = JdiFieldAccess.findField(node.referenceType(), "item");
            Field nextField = JdiFieldAccess.findField(node.referenceType(), "next");
            if (itemField == null) break;

            elements.add(ctx.capture(node.getValue(itemField)));

            if (nextField != null) {
                nodeVal = node.getValue(nextField);
            } else {
                break;
            }
        }
    }

    private static void captureArrayDeque(CaptureContext ctx, ObjectReference dequeRef,
                                           ReferenceType refType, List<ObjectRef> elements) {
        Field elementsField = JdiFieldAccess.findField(refType, "elements");
        Field headField = JdiFieldAccess.findField(refType, "head");
        Field tailField = JdiFieldAccess.findField(refType, "tail");
        if (elementsField == null || headField == null || tailField == null) return;

        Value elemsVal = dequeRef.getValue(elementsField);
        Value headVal = dequeRef.getValue(headField);
        Value tailVal = dequeRef.getValue(tailField);

        if (!(elemsVal instanceof ArrayReference)) return;
        ArrayReference arr = (ArrayReference) elemsVal;
        int head = (headVal instanceof IntegerValue) ? ((IntegerValue) headVal).value() : 0;
        int tail = (tailVal instanceof IntegerValue) ? ((IntegerValue) tailVal).value() : 0;
        int capacity = arr.length();

        int i = head;
        while (i != tail) {
            elements.add(ctx.capture(arr.getValue(i)));
            i = (i + 1) % capacity;
        }
    }

    private static ObjectSnapshot captureMap(CaptureContext ctx, long snapId, ObjectReference objRef,
                                              String className, String name) {
        List<ObjectRef> pairs = extractMapEntries(ctx, objRef);
        return new ObjectSnapshot(snapId, className, ObjectKind.COLLECTION,
                Collections.<String, ObjectRef>emptyMap(),
                pairs.toArray(new ObjectRef[0]), null, name);
    }

    /** Extract key/value pairs from a HashMap-like or TreeMap via JDI. */
    private static List<ObjectRef> extractMapEntries(CaptureContext ctx, ObjectReference mapRef) {
        List<ObjectRef> pairs = new ArrayList<>();
        try {
            ReferenceType mapType = mapRef.referenceType();

            // TreeMap: walk the red-black tree via root → left/right
            Field rootField = JdiFieldAccess.findField(mapType, "root");
            if (rootField != null && JdiFieldAccess.findField(mapType, "table") == null) {
                Value rootVal = mapRef.getValue(rootField);
                if (rootVal instanceof ObjectReference) {
                    walkTreeMapNode(ctx, (ObjectReference) rootVal, pairs);
                }
                return pairs;
            }

            // HashMap/ConcurrentHashMap/LinkedHashMap: walk table array + node chains
            Field tableField = JdiFieldAccess.findField(mapType, "table");
            if (tableField == null) return pairs;

            Value tableVal = mapRef.getValue(tableField);
            if (!(tableVal instanceof ArrayReference)) return pairs;
            ArrayReference table = (ArrayReference) tableVal;

            for (int i = 0; i < table.length(); i++) {
                Value bucketVal = table.getValue(i);
                if (!(bucketVal instanceof ObjectReference)) continue;
                ObjectReference node = (ObjectReference) bucketVal;

                while (node != null) {
                    Field keyField = JdiFieldAccess.findField(node.referenceType(), "key");
                    Field valField = JdiFieldAccess.findField(node.referenceType(), "val");
                    if (valField == null) valField = JdiFieldAccess.findField(node.referenceType(), "value");

                    if (keyField != null && valField != null) {
                        pairs.add(ctx.capture(node.getValue(keyField)));
                        pairs.add(ctx.capture(node.getValue(valField)));
                    }

                    Field nextField = JdiFieldAccess.findField(node.referenceType(), "next");
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

    private static void walkTreeMapNode(CaptureContext ctx, ObjectReference node, List<ObjectRef> pairs) {
        if (node == null) return;
        ReferenceType nodeType = node.referenceType();

        Field leftField = JdiFieldAccess.findField(nodeType, "left");
        Field rightField = JdiFieldAccess.findField(nodeType, "right");
        Field keyField = JdiFieldAccess.findField(nodeType, "key");
        Field valField = JdiFieldAccess.findField(nodeType, "value");

        if (leftField != null) {
            Value leftVal = node.getValue(leftField);
            if (leftVal instanceof ObjectReference) {
                walkTreeMapNode(ctx, (ObjectReference) leftVal, pairs);
            }
        }

        if (keyField != null && valField != null) {
            pairs.add(ctx.capture(node.getValue(keyField)));
            pairs.add(ctx.capture(node.getValue(valField)));
        }

        if (rightField != null) {
            Value rightVal = node.getValue(rightField);
            if (rightVal instanceof ObjectReference) {
                walkTreeMapNode(ctx, (ObjectReference) rightVal, pairs);
            }
        }
    }
}
