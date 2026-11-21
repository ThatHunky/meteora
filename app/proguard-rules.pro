# socket.io / engine.io use reflection-free code, but keep json for org.json bridge
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# youtubedl-android maps yt-dlp JSON with Jackson.
-keep class com.yausername.** { *; }
-dontwarn com.fasterxml.jackson.**
