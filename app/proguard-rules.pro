# Room
-keep class androidx.room.** { *; }
# Keep data classes used for JSON-ish serialization / reflection by Room & Koin
-keepclassmembers class com.jarvis2.app.data.db.** { *; }
-keepattributes *Annotation*
