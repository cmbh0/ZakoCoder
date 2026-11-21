# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.simplechat.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.simplechat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.simplechat.app.**$$serializer { *; }

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# --- JSch（SSH 云工作区）---
# JSch 的算法实现是按名字查找的，混淆类名会让 SSH 握手直接失败（表现为
# "Algorithm negotiation fail"，而用户只会看到"连不上"）。
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
