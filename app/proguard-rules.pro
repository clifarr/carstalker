# Kotlinx serialization ships its own consumer rules; keep model classes explicit.
-keep class com.carstalker.app.data.LogMeta { *; }
-keep class com.carstalker.app.data.CustomPid { *; }
-keepattributes *Annotation*, InnerClasses
