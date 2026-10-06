package com.firstham.aethergui;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hidden ways Turbo gets its key on a network that blocks the registration API, using the
 * newer core's {@code --register} mode ({@code libaetherreg.so}), which registers and exits.
 *
 * <p>Two tricks the tunnel core cannot do, each aimed at a different kind of block, each tried
 * against an IPv6 and an IPv4 Cloudflare address:
 * <ul>
 *   <li><b>ECH</b>: the API's name travels encrypted inside the TLS hello, under the public name
 *       {@code cloudflare-ech.com}, so a filter matching on the name never sees it.</li>
 *   <li><b>Shaped hello</b>: the registration goes through Panther's own packet-shaping proxy
 *       (byedpi), which sends the opening packets out of order, so a filter that reads the first
 *       packet has nothing whole to read.</li>
 * </ul>
 * IPv6 goes first because on some operators Cloudflare is reachable only over IPv6. On a phone
 * without IPv6 those attempts fail at once and cost nothing.
 *
 * <p>Nothing here is a server or domain of ours: the addresses are Cloudflare's own anycast.
 *
 * <p>Free of Android imports on purpose, so it is tested on a plain JVM.
 */
final class KeyRoutes {

    static final String IPV6_EDGE = "[2a06:98c1:3121::7]:443";
    static final String IPV4_EDGE = "188.114.97.6:443";

    /** Where the ECH key is looked up, as the core's --ech-dns takes it. */
    static final String ECH_DNS = "udp://1.1.1.1";

    /** Listener the registrar is told to use, so it can never collide with a running Turbo. */
    static final String REGISTRAR_SOCKS = "127.0.0.1:1829";

    /** One attempt; the core retries inside it, so this bounds a dead route. */
    static final long ATTEMPT_MS = 45_000L;

    static final class Attempt {
        final String label;
        final boolean ech;
        final boolean shaped;
        final String address;

        Attempt(String label, boolean ech, boolean shaped, String address) {
            this.label = label;
            this.ech = ech;
            this.shaped = shaped;
            this.address = address;
        }
    }

    static final Attempt[] ATTEMPTS = {
        new Attempt("shaped hello over IPv6", false, true, IPV6_EDGE),
        new Attempt("ECH over IPv6", true, false, IPV6_EDGE),
        new Attempt("ECH over IPv4", true, false, IPV4_EDGE),
        new Attempt("shaped hello over IPv4", false, true, IPV4_EDGE),
    };

    private KeyRoutes() { }

    /** The core's --register set for the protocol Turbo will run. */
    static String registerSet(String protocol) {
        String value = protocol == null ? "" : protocol.trim().toLowerCase(java.util.Locale.US);
        if ("masque".equals(value)) return "masque";
        if ("wg".equals(value)) return "wg";
        return "gool";
    }

    /** The identity files that set leaves on disk, all of which must exist for it to count. */
    static List<String> filesFor(String set) {
        List<String> files = new ArrayList<>();
        if ("masque".equals(set)) {
            files.add("aether-masque.toml");
        } else {
            files.add("aether.toml");
            if ("gool".equals(set)) files.add("aether-secondary.toml");
        }
        return files;
    }

    /** Whether every identity the set needs is on disk in {@code dir}. */
    static boolean saved(File dir, String set) {
        for (String name : filesFor(set)) {
            File file = new File(dir, name);
            if (!file.isFile() || file.length() > IdentityBundle.MAX_FILE_BYTES) return false;
            try {
                if (!IdentityBundle.looksLikeIdentity(Files.readAllBytes(file.toPath()))) return false;
            } catch (Exception unreadable) {
                return false;
            }
        }
        return true;
    }

    /**
     * The environment for one registrar run.
     *
     * @param config   the tunnel core's {@code aether.toml}, so the files land where it reads them
     * @param spoofSocks the shaping proxy's address, used only by shaped attempts
     */
    static Map<String, String> environment(Attempt attempt, String set, String config,
                                           String spoofSocks, String tmpDir) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AETHER_REGISTER", set);
        env.put("AETHER_CONFIG", config);
        env.put("AETHER_SOCKS", REGISTRAR_SOCKS);
        env.put("AETHER_LOG_LEVEL", "info");
        env.put("AETHER_ENROLL_ADDRESS", attempt.address);
        if (attempt.ech) {
            env.put("AETHER_ECH", "auto");
            env.put("AETHER_ECH_DNS", ECH_DNS);
        }
        if (attempt.shaped) env.put("AETHER_UPSTREAM", "socks5://" + spoofSocks);
        if (tmpDir != null) env.put("TMPDIR", tmpDir);
        return env;
    }
}
