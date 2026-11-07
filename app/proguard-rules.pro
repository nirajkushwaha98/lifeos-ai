# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lifeos.ai.**$$serializer { *; }
-keepclassmembers class com.lifeos.ai.** { *** Companion; }
-keepclasseswithmembers class com.lifeos.ai.** { kotlinx.serialization.KSerializer serializer(...); }

# Crashlytics: keep file/line info for readable stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Firestore uses reflection only for POJOs; LifeOS maps documents manually via Map<String, Any?>.
