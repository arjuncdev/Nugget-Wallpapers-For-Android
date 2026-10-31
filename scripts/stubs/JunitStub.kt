// Minimal org.junit shapes + a reflection runner so core/src/test can be executed with a bare kotlinc.
package org.junit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test

object Assert {
    @JvmStatic fun assertTrue(cond: Boolean) { if (!cond) throw AssertionError("expected true") }
    @JvmStatic fun assertTrue(msg: String, cond: Boolean) { if (!cond) throw AssertionError(msg) }
    @JvmStatic fun assertFalse(cond: Boolean) { if (cond) throw AssertionError("expected false") }
    @JvmStatic fun assertFalse(msg: String, cond: Boolean) { if (cond) throw AssertionError(msg) }
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) { if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>") }
    @JvmStatic fun assertEquals(msg: String, expected: Any?, actual: Any?) { if (expected != actual) throw AssertionError("$msg: expected <$expected> but was <$actual>") }
    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) { if (Math.abs(expected - actual) > delta) throw AssertionError("expected <$expected> but was <$actual> (delta $delta)") }
    @JvmStatic fun assertNotNull(o: Any?) { if (o == null) throw AssertionError("expected non-null") }
    @JvmStatic fun assertNotNull(msg: String, o: Any?) { if (o == null) throw AssertionError(msg) }
    @JvmStatic fun fail(msg: String) { throw AssertionError(msg) }
}

object StubRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        var passed = 0; var failed = 0
        for (cn in args) {
            val cls = Class.forName(cn)
            for (m in cls.methods.filter { it.isAnnotationPresent(Test::class.java) }.sortedBy { it.name }) {
                val inst = cls.getDeclaredConstructor().newInstance()
                try { m.invoke(inst); passed++; println("PASS  ${cls.simpleName}.${m.name}") }
                catch (e: java.lang.reflect.InvocationTargetException) { failed++; println("FAIL  ${cls.simpleName}.${m.name}: ${e.targetException}") }
            }
        }
        println("---- $passed passed, $failed failed")
        if (failed > 0) System.exit(1)
    }
}
