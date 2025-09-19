########################################
# WebRTC + JNI (фикс релиз-крэша)
########################################

# Не обфусцировать и не выкидывать WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Сохранить все native-методы (важно для JNI линковки)
-keepclasseswithmembers class * {
    native <methods>;
}

# Вспомогательные интерфейсы/внутренние классы WebRTC
-keep class org.webrtc.PeerConnection$Observer { *; }
-keep class org.webrtc.SdpObserver { *; }
-keep class org.webrtc.VideoSink { *; }
-keep class org.webrtc.EglBase$Context { *; }
-keep class org.webrtc.audio.** { *; }
-keep class org.webrtc.voiceengine.** { *; }

# Сохраняем важные атрибуты (генерики/аннотации и т.д.)
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature

########################################
# Firebase / Play Services
########################################
-dontwarn com.google.**
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Firestore c рефлексией (на всякий случай)
-keepclassmembers class * {
  @com.google.firebase.firestore.PropertyName <methods>;
  @com.google.firebase.firestore.PropertyName <fields>;
}

########################################
# Kotlin/Coroutines (обычно безопасно)
########################################
-dontwarn kotlinx.coroutines.**
