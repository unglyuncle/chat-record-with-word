# sherpa-onnx JNI bridge classes must keep their native method signatures.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# commons-compress pulls in optional codecs we don't use.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
