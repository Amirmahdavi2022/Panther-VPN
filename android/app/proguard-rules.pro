# Native JNI entry points are registered by HEV's JNI_OnLoad.
-keep class hev.htproxy.TProxyService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Tink, pulled in transitively by androidx.security:security-crypto via vpnLib, references
# compile-only annotations that are absent at runtime. R8 treats the dangling references as
# errors, so tell it they are expected rather than adding a dependency purely to satisfy them.
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.lang.model.element.**
-keep class com.google.crypto.tink.** { *; }

# The OpenVPN engine. Its service and profile are reached through JNI, AIDL and reflection over
# a serialised profile, none of which R8 can see, so shrinking them produces a build that only
# fails once a user presses connect.
-keep class de.blinkt.openvpn.** { *; }
-keep interface de.blinkt.openvpn.** { *; }
-keepclassmembers class de.blinkt.openvpn.VpnProfile { *; }
-dontwarn de.blinkt.openvpn.**
