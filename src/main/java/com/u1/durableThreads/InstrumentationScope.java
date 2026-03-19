package com.u1.durableThreads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Controls which user classes the agent instruments.
 *
 * <p>Parsed from the agent argument string. Format:</p>
 * <pre>
 * includes=com.myapp;com.mylib&amp;excludes=com.myapp.generated
 * </pre>
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>If includes is non-empty, ONLY classes in those packages are instrumented.</li>
 *   <li>Excludes are always applied (even against included packages).</li>
 *   <li>If neither is specified, all non-JDK/non-library classes are instrumented.</li>
 * </ul>
 */
final class InstrumentationScope {

    /** Empty scope — instrument everything (default). */
    static final InstrumentationScope ALL = new InstrumentationScope(List.of(), List.of());

    private final List<String> includePrefixes; // e.g. "com/myapp/"
    private final List<String> excludePrefixes; // e.g. "com/myapp/generated/"

    private InstrumentationScope(List<String> includePrefixes, List<String> excludePrefixes) {
        this.includePrefixes = includePrefixes;
        this.excludePrefixes = excludePrefixes;
    }

    /**
     * Check if a class (in internal form, e.g. "com/myapp/Foo") is in scope.
     *
     * <p>This only checks user-configured scope — the caller must still
     * check hardcoded exclusions (JDK, library internals, etc.).</p>
     */
    boolean isInScope(String internalClassName) {
        // Check excludes first
        for (String prefix : excludePrefixes) {
            if (internalClassName.startsWith(prefix)) {
                return false;
            }
        }

        // If no includes specified, everything is in scope
        if (includePrefixes.isEmpty()) {
            return true;
        }

        // Check includes
        for (String prefix : includePrefixes) {
            if (internalClassName.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Parse agent argument string.
     *
     * @param agentArgs e.g. "includes=com.myapp;com.mylib&amp;excludes=com.thirdparty"
     * @return the parsed scope, or {@link #ALL} if null/empty
     */
    static InstrumentationScope parse(String agentArgs) {
        if (agentArgs == null || agentArgs.isBlank()) {
            return ALL;
        }

        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();

        for (String part : agentArgs.split("&")) {
            part = part.trim();
            if (part.startsWith("includes=")) {
                String value = part.substring("includes=".length());
                for (String pkg : value.split(";")) {
                    pkg = pkg.trim();
                    if (!pkg.isEmpty()) {
                        includes.add(toInternalPrefix(pkg));
                    }
                }
            } else if (part.startsWith("excludes=")) {
                String value = part.substring("excludes=".length());
                for (String pkg : value.split(";")) {
                    pkg = pkg.trim();
                    if (!pkg.isEmpty()) {
                        excludes.add(toInternalPrefix(pkg));
                    }
                }
            }
        }

        if (includes.isEmpty() && excludes.isEmpty()) {
            return ALL;
        }

        return new InstrumentationScope(
                Collections.unmodifiableList(includes),
                Collections.unmodifiableList(excludes));
    }

    /** Convert "com.myapp" to "com/myapp/" for prefix matching. */
    private static String toInternalPrefix(String dotPackage) {
        String internal = dotPackage.replace('.', '/');
        return internal.endsWith("/") ? internal : internal + "/";
    }

    @Override
    public String toString() {
        if (includePrefixes.isEmpty() && excludePrefixes.isEmpty()) {
            return "InstrumentationScope[ALL]";
        }
        return "InstrumentationScope[includes=" + includePrefixes + ", excludes=" + excludePrefixes + "]";
    }
}
