# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# libxposed loads the module entry point via the class name string in
# META-INF/xposed/java_init.list; if the entry class is allowed to be obfuscated,
# java_init.list must be rewritten accordingly to avoid the module breaking after
# release shrinking.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation class io.github.namanwtf.atom.ModuleMain {
    public <init>();
}

# R8's default rules already cover the Compose runtime; Miuix icons are ordinary
# Kotlin code, so R8 is allowed to strip unused icons.
# -dontwarn only suppresses optional platform warnings that KMP dependencies may
# emit on the Android side; it does not prevent shrinking.
-dontwarn top.yukonga.miuix.**

# libxposed service is wired in via static calls and a manifest provider; rely on R8/Android default rules to keep reachable code.
-dontwarn io.github.libxposed.service.**

# Config keys are string constants accessed via static calls, so class and member names do not need to be kept.

# ── Release logging policy ────────────────────────────────────────────────────────
# Remove only Android VERBOSE/DEBUG calls in Eta's own code; INFO/WARN/ERROR must
# be kept. Third-party dependencies decide their own logging policy.
-maximumremovedandroidloglevel 3 class io.github.namanwtf.atom.** { *; }

# XposedModule.log is not android.util.Log, so R8 cannot recognize it via the rule above.
# The debug supplier is a pure observation API; do not perform any business side
# effects inside the supplier.
-assumenosideeffects interface io.github.namanwtf.atom.core.AgentLogger {
    public abstract void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class io.github.namanwtf.atom.core.AndroidAgentLogger {
    public void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class io.github.namanwtf.atom.core.ModuleLogger {
    public void debug(kotlin.jvm.functions.Function0);
}

# ── Serialization and network dependencies ─────────────────────────────────────────────────────────
# DataStore, kotlinx.serialization, OkHttp, and Okio all ship precise consumer rules;
# do not keep entire classes or packages again at the app layer, to avoid blocking
# shrinking, inlining, and obfuscation.
# Nimbus's embedded Gson carries this CLASS-level static check annotation; it is not
# needed at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue

# Keep source and line-number attributes so release mappings can restore online stack traces.
-keepattributes SourceFile,LineNumberTable

# app_process launches the controlled first-party app and virtual-display operation entry points via fixed class names.
-keep class io.github.namanwtf.atom.agent.phone.PhoneCommandMain {
    public static void main(java.lang.String[]);
}
-keep class io.github.namanwtf.atom.agent.virtualdisplay.VirtualDisplayCommandMain {
    public static void main(java.lang.String[]);
}
