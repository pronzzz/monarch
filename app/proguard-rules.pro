# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.antigravity.monarch.** { *** Companion; }
-keepclasseswithmembers class com.antigravity.monarch.** { kotlinx.serialization.KSerializer serializer(...); }
