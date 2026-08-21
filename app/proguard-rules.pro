# ScreenStream Unified keeps WebRTC reflective/native entry points intact.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
