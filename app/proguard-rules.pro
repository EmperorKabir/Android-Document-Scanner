# Keep line numbers for readable crash traces, hide the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# CameraX uses reflection for extension and vendor implementations.
-keep class androidx.camera.** { *; }
