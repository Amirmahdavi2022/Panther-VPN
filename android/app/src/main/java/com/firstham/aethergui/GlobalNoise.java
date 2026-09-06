package com.firstham.aethergui;

/**
 * Decides which of the Global engine's diagnostic lines are worth keeping.
 *
 * <p>One message dominates everything else. The TUN bridge forwards UDP by asking the engine's
 * SOCKS listener for a UDP association, the listener has no UDP support and refuses, and the
 * refusal is logged - once per datagram. A single connection produced twenty-two of them in a real
 * device log, and busier ones produce far more.
 *
 * <p>They are worth suppressing rather than fixing because there is nothing to fix. The engine
 * cannot carry UDP, which is why DNS is answered inside the tunnel instead (see
 * {@link TunnelConfig}); what remains is overwhelmingly QUIC, which every browser tries first and
 * abandons for TCP within a moment. The connection is fine. Only the log suffers.
 *
 * <p>Suppressed, not discarded silently: {@link #summary(int)} reports the count once, so the
 * behaviour stays visible without a line per packet. A log that hides a real signal in repetition
 * is no more useful than one that drops it.
 *
 * <p>Free of Android imports, so the matching can be checked on a desktop JVM against the real
 * strings from a device log.
 */
final class GlobalNoise {

    /**
     * The refusal, as the engine words it. Matched on the distinctive part rather than the whole
     * line, because the surrounding prefix carries a call trace that varies between messages.
     */
    private static final String UDP_REFUSED = "command was 0x03, not 0x01";

    private GlobalNoise() { }

    /**
     * Whether a diagnostic line is the repeated UDP refusal described above.
     *
     * <p>Only that one message. Every other warning the engine emits - port-forward rejections,
     * fetch failures, tunnel errors - is a genuine signal about a connection that is not behaving,
     * and stays in the log.
     */
    static boolean isRepeatedUdpRefusal(String message) {
        return message != null && message.contains(UDP_REFUSED);
    }

    /**
     * One line describing what was suppressed, or null when there is nothing to report.
     *
     * @param count how many refusals were swallowed
     */
    static String summary(int count) {
        if (count <= 0) return null;
        return "Global refused " + count + " UDP request" + (count == 1 ? "" : "s")
                + " (QUIC; apps fall back to TCP)";
    }
}
