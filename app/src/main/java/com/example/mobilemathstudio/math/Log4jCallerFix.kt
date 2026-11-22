package com.example.mobilemathstudio.math

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Makes `LogManager.getLogger()` work on Android.
 *
 * log4j-api finds the class that asked for a logger through
 * `sun.reflect.Reflection.getCallerClass` (HotSpot only) or a Java 9 StackWalker
 * variant stored under META-INF/versions/9, which D8/R8 never package. On ART it
 * therefore gets no caller and throws, so every Symja class with a static logger
 * fails to initialise and the whole CAS is unusable (each solve silently fell
 * back to the local engine after Symja had loaded thousands of classes).
 *
 * The fix points log4j's caller lookup at [callerClass], a stack-trace walk.
 */
object Log4jCallerFix {
    @Volatile
    private var done = false

    fun install() {
        if (done) return
        synchronized(this) {
            if (done) return
            done = true
            try {
                val probe = runCatching { org.apache.logging.log4j.util.StackLocatorUtil.getCallerClass(1) }
                if (probe.getOrNull() != null) return
                val locator = Class.forName("org.apache.logging.log4j.util.StackLocator")
                val field = locator.getDeclaredField("GET_CALLER_CLASS_METHOD")
                field.isAccessible = true
                val lookup = Log4jCallerFix::class.java.getDeclaredMethod("callerClass", Int::class.javaPrimitiveType)
                try {
                    // ART lets an accessible static final field be reassigned.
                    field.set(null, lookup)
                } catch (_: IllegalAccessException) {
                    // HotSpot refuses; only reached when the JVM tests run R8 output.
                    putStaticViaUnsafe(field, lookup)
                }
            } catch (_: Throwable) {
                // Nothing else to try: Symja then fails as before and the local engine answers.
            }
        }
    }

    /** First frame outside log4j and reflection is the class that asked for a logger. */
    @JvmStatic
    fun callerClass(@Suppress("UNUSED_PARAMETER") depth: Int): Class<*>? {
        val loader = Log4jCallerFix::class.java.classLoader
        for (frame in Throwable().stackTrace) {
            val name = frame.className
            if (name.startsWith("org.apache.logging.log4j.") ||
                name.startsWith("java.lang.reflect.") ||
                name.startsWith("jdk.internal.") ||
                name.startsWith("sun.reflect.") ||
                name.startsWith("dalvik.") ||
                name == Log4jCallerFix::class.java.name
            ) {
                continue
            }
            return try {
                Class.forName(name, false, loader)
            } catch (_: ClassNotFoundException) {
                null
            }
        }
        return null
    }

    private fun putStaticViaUnsafe(field: Field, value: Method) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(theUnsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(theUnsafe, field) as Long
        unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(theUnsafe, base, offset, value)
    }
}
