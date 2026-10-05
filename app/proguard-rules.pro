# Keep jsoup/okhttp reflection-safe APIs.
-dontwarn org.jsoup.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# jsoup parses our target page via these.
-keepclassmembers class org.jsoup.nodes.** { *; }
