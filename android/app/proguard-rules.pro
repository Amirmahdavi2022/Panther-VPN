# Native JNI entry points are registered by HEV's JNI_OnLoad.
-keep class hev.htproxy.TProxyService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Tink arrives transitively through androidx.security:security-crypto in vpnLib. It is built
# for server use too, so parts of it reference libraries that are simply not on an Android
# classpath - google-http-client and joda-time for its KeysDownloader, App Engine for its
# hosted variants. None of that is reachable from anything we call.
#
# Note the previous blanket "-keep class com.google.crypto.tink.**" was actively harmful: it
# forced R8 to retain KeysDownloader, which is exactly the class dragging in the missing
# references. Let R8 shrink Tink normally and only silence the optional dependencies.
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.element.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn com.google.appengine.**
-dontwarn com.google.apphosting.**
-dontwarn org.joda.time.**
-dontwarn javax.naming.**

# The OpenVPN engine. Its service and profile are reached through JNI, AIDL and reflection over
# a serialised profile, none of which R8 can see, so shrinking them produces a build that only
# fails once a user presses connect.
-keep class de.blinkt.openvpn.** { *; }
-keep interface de.blinkt.openvpn.** { *; }
-keepclassmembers class de.blinkt.openvpn.VpnProfile { *; }
-dontwarn de.blinkt.openvpn.**

# The Global engine. It is a gomobile library, so every call in and out goes through generated
# JNI bridges that R8 has no way to follow. These are the library's own published keep rules;
# they are repeated here rather than relied on, because a local AAR's consumer rules are easy to
# lose and the failure mode is a NoClassDefFoundError at connect time rather than a build error.
-keep class go.** { *; }
-keep class psi.** { *; }
-keep class ca.psiphon.** { *; }

# The Stealth engine. Another gomobile library, so the same reasoning as above applies: every
# call crosses a generated JNI bridge R8 cannot follow, and the failure mode is a
# NoClassDefFoundError the moment someone taps connect rather than anything the build would
# catch. These are the library's own published rules, repeated here rather than relied on.
-keep class libv2ray.** { *; }
-keep interface libv2ray.** { *; }
-dontwarn libv2ray.**
