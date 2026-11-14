# HandCode ProGuard Rules

# Chaquopy
-keep class com.chaquo.python.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# MediaPipe GenAI (2026-04-19) — tasks-genai 0.10.x 는 JNI 기반 LLM Inference 라
# JNI 이름/reflection 대상 클래스가 R8 에 의해 제거되면 런타임 SIGSEGV 위험.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.genai.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
