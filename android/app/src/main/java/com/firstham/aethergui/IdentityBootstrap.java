package com.firstham.aethergui;

/**
 * The decisions behind registering Turbo's identity through another engine, kept free of Android
 * so they can be tested on a plain JVM.
 *
 * <p>The log phrases below are the pinned core's own (Aether v2.1.0, {@code lib.rs} and
 * {@code account.rs}), not guesses:
 * <ul>
 *   <li>{@code [+] no warp identity found; provisioning ...} and
 *       {@code [+] no masque identity found; provisioning ...} - info level, printed before the
 *       first registration request.</li>
 *   <li>{@code [!] registration retry 1/4 in ...}, {@code [!] key enrollment retry ...},
 *       {@code [!] registration failed over the direct route: ...},
 *       {@code [*] registration retrying over a camouflaged route: ...} - warn/info level, printed
 *       only while an identity is being registered or a key enrolled.</li>
 *   <li>{@code [+] identity ready: ...} / {@code [+] outer device=...} - printed right after every
 *       identity the protocol needs has been saved.</li>
 * </ul>
 * The retry lines are warn level, so they still show when the user has turned the log down to
 * {@code warn}, which hides the info-level "provisioning" line. That is why they count as proof
 * that an identity is needed too. The one retry-capable call that also runs for an existing
 * identity is the Zero Trust "device refresh", which Panther never uses; it is excluded anyway.
 */
final class IdentityBootstrap {

    /** A way out that can carry the one registration request Turbo cannot make itself. */
    enum Route { LOCAL_PROXY, BEACON, GLOBAL }

    /**
     * A proxy another app already runs on the phone goes first: if the user has one, it is a way
     * out they know works on this network, and when there is none, finding that out costs a few
     * milliseconds of loopback probes. Then Beacon, which needs nothing else to be running, which
     * is the whole situation here: Turbo has no identity yet. Global normally rides on Turbo, and
     * on a fresh install it has no server list of its own either, so asked early it spends its
     * whole start budget failing.
     */
    static final Route[] ORDER = {Route.LOCAL_PROXY, Route.BEACON, Route.GLOBAL};

    interface Courier {
        /** Registers through {@code route}; true only once the identity is saved on disk. */
        boolean register(Route route) throws Exception;
    }

    interface Cancellation {
        /** True once the user disconnected or a newer connect replaced this one. */
        boolean cancelled();
    }

    private IdentityBootstrap() { }

    /** A line (already lower-cased) proving the core has no usable identity yet. */
    static boolean needsIdentity(String lower) {
        if (lower == null) return false;
        return lower.contains("identity found; provisioning") || registrationTrouble(lower);
    }

    /** A line (already lower-cased) showing registration is not getting through directly. */
    static boolean struggling(String lower) {
        return lower != null && registrationTrouble(lower);
    }

    /** A line (already lower-cased) the core prints only after its identities are saved. */
    static boolean identityReady(String lower) {
        return lower != null && (lower.contains("identity ready") || lower.contains("outer device="));
    }

    private static boolean registrationTrouble(String lower) {
        if (lower.contains("device refresh")) return false;
        return lower.contains("registration retry")
                || lower.contains("enrollment retry")
                || lower.contains("failed over the direct route")
                || lower.contains("retrying over a camouflaged route");
    }

    /**
     * Tries each route in {@link #ORDER} until one saves the identity. Stops as soon as the connect
     * is cancelled, and never reports a route that finished after it was cancelled.
     *
     * @return the route that worked, or null
     */
    static Route run(Courier courier, Cancellation cancellation) {
        for (Route route : ORDER) {
            if (cancellation.cancelled()) return null;
            boolean saved;
            try {
                saved = courier.register(route);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception error) {
                saved = false;
            }
            if (cancellation.cancelled()) return null;
            if (saved) return route;
        }
        return null;
    }

    /**
     * Whether a registrar run has finished its job.
     *
     * <p>The log line is the fast signal. The listener is the one that does not depend on the log
     * level: the core binds its real SOCKS listener only after its identities are saved (its early
     * startup check binds the port and releases it at once). A listener only counts if it belongs to
     * this registrar, so it must still be running after the answer - a registrar that lost the port
     * to a leftover process exits straight away instead. Its own ready line counts even if it
     * exited afterwards: the line is printed only once the identity is on disk.
     */
    static boolean registrarReady(boolean registrarAlive, boolean identityLogged,
                                  boolean portAnswered, boolean aliveAfterAnswer) {
        if (identityLogged) return true;
        return registrarAlive && portAnswered && aliveAfterAnswer;
    }
}
