# Project-specific R8/ProGuard rules.
# CameraX is already covered by its own consumer rules shipped in the AARs.

# JavaMail (javax.mail / javax.activation) is loaded reflectively — keep it.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn com.sun.mail.**
-keepattributes InnerClasses,EnclosingMethod

# ML Kit (face detection) uses reflection and runtime-inspected classes — keep them.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_common.**
