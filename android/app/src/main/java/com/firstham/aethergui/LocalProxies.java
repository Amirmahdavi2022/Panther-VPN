package com.firstham.aethergui;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Finds a SOCKS5 proxy that another app already runs on this phone, so Turbo can register its
 * identity through it when the network blocks Cloudflare's registration API.
 *
 * <p>Plenty of people in this situation already have something that works: a v2ray client, Tor,
 * a Clash profile. Most of them listen on a well known loopback port. Registration is one small
 * HTTPS request, so any of those can carry it, and no server or domain of ours is involved.
 *
 * <p>Only real SOCKS5 counts: the probe sends the no-auth greeting and needs the matching answer,
 * so an HTTP-only port or something unrelated listening there is skipped instead of being handed
 * to the core and failing two minutes later.
 *
 * <p>Free of Android imports on purpose, so it is tested on a plain JVM.
 */
final class LocalProxies {

    /**
     * The usual SOCKS ports, most common first: v2rayNG and its forks (10808), Orbot and other
     * Tor apps (9050), NekoBox and Husi (2080), Clash's mixed port (7890), the generic default
     * (1080).
     */
    static final int[] CANDIDATE_PORTS = {10808, 9050, 2080, 7890, 1080};

    /** Ports Panther's own engines use. A proxy there is ours, never another app's. */
    static final int[] OWN_PORTS = {1819, 1820, 1821, 1829};

    static final String HOST = "127.0.0.1";

    private LocalProxies() { }

    /** Whether {@code port} belongs to one of Panther's own engines. */
    static boolean isOwn(int port) {
        for (int own : OWN_PORTS) if (own == port) return true;
        return false;
    }

    /** Whether the two bytes a server sent back accept a SOCKS5 no-auth greeting. */
    static boolean acceptsGreeting(int version, int method) {
        return version == 0x05 && method == 0x00;
    }

    /**
     * Whether something on {@code HOST:port} answers as a SOCKS5 proxy that needs no password.
     * Never throws; any failure means no.
     */
    static boolean speaksSocks5(int port, int timeoutMs) {
        if (port <= 0 || port > 65535 || isOwn(port)) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(new byte[] {0x05, 0x01, 0x00});
            out.flush();
            InputStream in = socket.getInputStream();
            int version = in.read();
            int method = in.read();
            return acceptsGreeting(version, method);
        } catch (Exception notAProxy) {
            return false;
        }
    }

    /**
     * The first candidate port with a working SOCKS5 proxy on it, or -1. Each closed port fails
     * at once on loopback, so finding nothing costs a few milliseconds.
     */
    static int find(int[] ports, int timeoutMs) {
        if (ports == null) return -1;
        for (int port : ports) {
            if (speaksSocks5(port, timeoutMs)) return port;
        }
        return -1;
    }

    /** The upstream URL the core takes for a proxy on {@code port}. */
    static String upstream(int port) {
        return "socks5://" + HOST + ":" + port;
    }
}
