package dev.zeptun;

/**
 * JNI contract of the prebuilt fast TUN bridge (see NOTICE.md). The native library registers
 * these methods on exactly this class name, so neither the package nor the class may be renamed
 * or shrunk away; proguard-rules.pro keeps both.
 *
 * <p>Loading is deferred to the first call, so a device where the library cannot load only loses
 * this bridge. Callers catch {@link Throwable}: a failed load surfaces as an {@link Error}.
 */
public final class Zeptun {
    static {
        System.loadLibrary("zeptun-jni");
    }

    private Zeptun() { }

    /**
     * Starts the engine on its own thread and returns at once.
     *
     * @param service object whose {@code boolean protect(int)} is called for every upstream socket
     * @param fd      the VPN interface descriptor, which the engine does not take ownership of
     * @param config  TOML; it must name {@code fd} itself, see FastBridgeConfig
     * @return 0 on success, a negative library error code otherwise
     */
    public static native int nativeStart(Object service, int fd, String config);

    /** Stops the engine and waits for its thread. Safe to call when nothing is running. */
    public static native void nativeStop();

    public static native String nativeVersion();

    /** One field of the stats snapshot, counted after {@code version} and {@code workers}. */
    public static native long nativeCounter(int index);
}
