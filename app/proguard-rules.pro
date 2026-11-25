# StreamBox TV release rules.
# Keep the app's own classes intact (the theme loads TrViewInflater by name, receivers/services are
# declared in the manifest); libraries are still shrunk and optimised.
-keep class com.streambox.tv.** { *; }
# FFmpeg audio decoder is loaded by name by DefaultRenderersFactory.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# OkHttp / Okio optional platform integrations
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
