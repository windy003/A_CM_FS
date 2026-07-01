# ClashMeta ProGuard Rules

# Keep Mobile library
-keep class mobile.** { *; }
-keep class go.** { *; }

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep data classes
-keep class com.example.clashmeta.data.** { *; }
-keep class com.example.clashmeta.ui.proxy.ProxyInfo { *; }

# Keep snakeyaml (节点复制/粘贴解析 config.yaml 用)
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
