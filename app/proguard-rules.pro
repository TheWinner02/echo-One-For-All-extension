# Echo supplies Kotlin/common through its own class loader. Optimizing against
# bundled runtime copies can rebind public facade calls to inaccessible owners.
# Keep shrinking in release, but preserve the original cross-loader call sites.
-dontoptimize
# Rhino's optional desktop Java-object conversion and JSR-223 integration are
# not used by the Android YouTube extractor. These APIs do not exist on Android.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
# Desktop JSR-223 bridge and JVM dynamic linker; unused by Android extraction.
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Optional IDE debugger detection in Ktor (desktop JVM only).
-dontwarn java.lang.management.**

# Preserve the extractor and JavaScript runtime entry points used reflectively.
# Ported from the standalone YouTube extension; limited to its bundled libraries.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter { *; }
-keep class org.schabi.newpipe.** { *; }
-keep class dev.toastbits.ytmkt.** { *; }
-keep class io.ktor.** { *; }
