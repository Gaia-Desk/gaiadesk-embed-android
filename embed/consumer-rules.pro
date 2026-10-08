# libgaiadesk_embed.so calls these by name through JNI: R8 must keep them.
-keep class net.gaiadesk.embed.internal.NativeBridge { native <methods>; }
-keep interface net.gaiadesk.embed.internal.NativeHost { *; }
-keepclassmembers class * implements net.gaiadesk.embed.internal.NativeHost { public *; }
