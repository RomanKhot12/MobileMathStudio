# ── Symja (matheclipse) ─────────────────────────────────────────────────────
# Built-in functions, rule tables and symbol evaluators are wired up by name
# (Class.forName / static symbol tables), so the CAS itself is kept whole.
# Everything it does not reach (most of its ~40 transitive libraries) is still
# shrunk away by R8.
-keep class org.matheclipse.** { *; }
-keepnames class org.matheclipse.** { *; }

# apfloat picks its BuilderFactory / context implementation reflectively.
-keep class org.apfloat.** { *; }

# log4j-api discovers its provider through ServiceLoader and reflection.
-keep class org.apache.logging.log4j.** { *; }

# Installed into log4j's StackLocator by reflection (see Log4jCallerFix).
-keep class com.example.mobilemathstudio.math.Log4jCallerFix { *; }

# Hipparchus formats its localized messages through enum names.
-keepclassmembers enum org.hipparchus.** { *; }

# Desktop-only APIs referenced by optional Symja code paths (AWT, javax.script,
# JMX, Graal, etc.) are never executed on Android.
-dontwarn **
-ignorewarnings

# Keep stack traces readable in CrashGuard logs.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile
