########################################
# WebRTC + JNI
########################################
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
-keepclasseswithmembers class * {
    native <methods>;
}
-keep class org.webrtc.PeerConnection$Observer { *; }
-keep class org.webrtc.SdpObserver { *; }
-keep class org.webrtc.VideoSink { *; }
-keep class org.webrtc.EglBase$Context { *; }
-keep class org.webrtc.audio.** { *; }
-keep class org.webrtc.voiceengine.** { *; }

########################################
# General Attributes
########################################
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,SourceFile,LineNumberTable

########################################
# Firebase / Play Services
########################################
-dontwarn com.google.**
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keepclassmembers class * {
  @com.google.firebase.firestore.PropertyName <methods>;
  @com.google.firebase.firestore.PropertyName <fields>;
}

########################################
# Kotlin/Coroutines
########################################
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

########################################
# App Data Models
########################################
-keep class com.example.messenger_app.data.model.** { *; }
# Specifically keep these if they are used in Firestore/Room
-keep class com.example.messenger_app.data.model.ChatModels.** { *; }
-keep class com.example.messenger_app.data.model.TransferModels.** { *; }

########################################
# Gson / Room
########################################
-keep class com.google.gson.** { *; }
-keep interface com.google.gson.** { *; }
-keep class com.example.messenger_app.data.local.Converters { *; }
-keep class com.example.messenger_app.data.local.Converters$* { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Room
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
-keep class * extends androidx.room.RoomDatabase

########################################
# Google Auth / API Client / HTTP
########################################
-keep class com.google.auth.** { *; }
-keep class com.google.api.** { *; }
-keep class com.google.http.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.common.** { *; }
-keep class io.grpc.** { *; }
-keep class io.opencensus.** { *; }
-keep class javax.annotation.** { *; }
-keep class sun.misc.Unsafe { *; }

-dontwarn com.google.auth.**
-dontwarn com.google.api.**
-dontwarn com.google.http.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.common.**
-dontwarn io.grpc.**
-dontwarn io.opencensus.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**
-dontwarn javax.security.**

########################################
# Media3 (ExoPlayer)
########################################
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }

########################################
# Coil
########################################
-dontwarn coil.**
-keep class coil.** { *; }
