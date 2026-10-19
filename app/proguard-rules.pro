# Monarch R8 rules. Hilt, Room, Glance, WorkManager and kotlinx.serialization ship their own
# consumer rules; these cover what's specific to this app.

# kotlinx.serialization: keep generated serializers for our @Serializable models (plans, Strava DTOs).
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class com.antigravity.monarch.** {
    *** Companion;
}
-keepclasseswithmembers class com.antigravity.monarch.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.antigravity.monarch.**$$serializer { *; }

# Enum names are persisted in Room and DataStore (StatType, WorkoutType, AchievementDef...), so
# their constant names must survive shrinking.
-keepclassmembers enum com.antigravity.monarch.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Credential Manager / Google ID token classes are loaded reflectively.
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.playservices.** { *; }
