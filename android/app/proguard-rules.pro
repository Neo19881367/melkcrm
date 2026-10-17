# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ir.melkcrm.**$$serializer { *; }
-keepclassmembers class ir.melkcrm.** { *** Companion; }
-keepclasseswithmembers class ir.melkcrm.** { kotlinx.serialization.KSerializer serializer(...); }
# Enum names are persisted
-keepclassmembers enum ir.melkcrm.** { *; }
# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
# osmdroid
-dontwarn org.osmdroid.**
