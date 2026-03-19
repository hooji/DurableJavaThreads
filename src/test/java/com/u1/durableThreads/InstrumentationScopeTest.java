package com.u1.durableThreads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentationScopeTest {

    @Test
    void nullArgsMeansInstrumentAll() {
        InstrumentationScope scope = InstrumentationScope.parse(null);
        assertTrue(scope.isInScope("com/example/MyClass"));
        assertTrue(scope.isInScope("org/thirdparty/Lib"));
    }

    @Test
    void emptyArgsMeansInstrumentAll() {
        InstrumentationScope scope = InstrumentationScope.parse("");
        assertTrue(scope.isInScope("com/example/MyClass"));
    }

    @Test
    void includesRestrictsToSpecifiedPackages() {
        InstrumentationScope scope = InstrumentationScope.parse("includes=com.myapp;com.mylib");
        assertTrue(scope.isInScope("com/myapp/Foo"));
        assertTrue(scope.isInScope("com/myapp/sub/Bar"));
        assertTrue(scope.isInScope("com/mylib/Baz"));
        assertFalse(scope.isInScope("com/thirdparty/Lib"));
        assertFalse(scope.isInScope("org/example/Other"));
    }

    @Test
    void excludesBlocksSpecifiedPackages() {
        InstrumentationScope scope = InstrumentationScope.parse("excludes=com.thirdparty;org.logging");
        assertTrue(scope.isInScope("com/myapp/Foo"));
        assertFalse(scope.isInScope("com/thirdparty/Lib"));
        assertFalse(scope.isInScope("com/thirdparty/sub/Deep"));
        assertFalse(scope.isInScope("org/logging/Logger"));
        assertTrue(scope.isInScope("org/other/Util"));
    }

    @Test
    void includesAndExcludesCombined() {
        InstrumentationScope scope = InstrumentationScope.parse(
                "includes=com.myapp&excludes=com.myapp.generated");
        assertTrue(scope.isInScope("com/myapp/Foo"));
        assertTrue(scope.isInScope("com/myapp/service/Bar"));
        assertFalse(scope.isInScope("com/myapp/generated/Proto"));
        assertFalse(scope.isInScope("com/other/Outside"));
    }

    @Test
    void toStringDescribesScope() {
        assertEquals("InstrumentationScope[ALL]", InstrumentationScope.ALL.toString());
        assertFalse(InstrumentationScope.parse("includes=com.foo").toString().contains("ALL"));
    }
}
