# NewPipeExtractor parses YouTube/SoundCloud responses and runs YouTube's player JavaScript through
# Rhino, both by reflection; keep them whole.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.annotations.**

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# kotlinx.serialization: keep generated serializers for the app's own models.
-keepclassmembers @kotlinx.serialization.Serializable class app.lumen.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.lumen.**$$serializer { *; }
