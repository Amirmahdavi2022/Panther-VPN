package com.firstham.aethergui;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Asks a local SOCKS5 proxy to reach somewhere, and reports whether it could.
 *
 * <p>This exists because of how the Stealth engine's core reports itself. Starting the core only
 * means the core loaded its configuration and began listening; it says nothing about whether the
 * server on the far end answers. A pool endpoint that died an hour ago starts exactly as cleanly
 * as one that works. Without a probe the app would show "connected" over a tunnel that carries
 * nothing, which is worse than showing a failure.
 *
 * <p>So the engine dials an endpoint, then proves it here before believing it, and moves to the
 * next candidate when the proof fails.
 *
 * <p>Deliberately free of Android and of any HTTP library, so it runs on a desktop JVM and is
 * tested against a real socket rather than a mock.
 */
final class SocksProbe {

    /** Small, always-up, and answers a bare request without redirecting. */
    static final String PROBE_HOST = "cloudflare.com";
    static final int PROBE_PORT = 443;

    private SocksProbe() { }

    /**
     * Opens a connection through the proxy to {@code host:port}.
     *
     * <p>The caller owns the returned socket and must close it. A SOCKS5 reply of "succeeded"
     * means the proxy really established the far connection, so for our purpose the handshake
     * completing is itself the proof - no bytes need to be exchanged afterwards.
     *
     * @throws java.io.IOException          if the proxy cannot be reached
     * @throws IllegalStateException if the proxy refuses or the far side is unreachable
     */
    static Socket connect(String proxyHost, int proxyPort, String host, int port, int timeoutMs)
            throws Exception {
        Socket socket = new Socket();
        boolean handed = false;
        try {
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Greeting: version 5, one method offered, "no authentication".
            out.write(new byte[] { 0x05, 0x01, 0x00 });
            out.flush();
            byte[] greeting = readFully(in, 2);
            if (greeting[0] != 0x05 || greeting[1] != 0x00) {
                throw new IllegalStateException("The proxy refused an unauthenticated session");
            }

            // Request: CONNECT, address given as a name so the far end resolves it, not this
            // device. Resolving locally would leak the destination to whatever resolver the phone
            // is using, which is the thing the tunnel exists to avoid.
            byte[] name = host.getBytes(StandardCharsets.UTF_8);
            if (name.length > 255) throw new IllegalArgumentException("Host name is too long");
            byte[] request = new byte[7 + name.length];
            request[0] = 0x05;                 // version
            request[1] = 0x01;                 // connect
            request[2] = 0x00;                 // reserved
            request[3] = 0x03;                 // address is a domain name
            request[4] = (byte) name.length;
            System.arraycopy(name, 0, request, 5, name.length);
            request[5 + name.length] = (byte) ((port >> 8) & 0xFF);
            request[6 + name.length] = (byte) (port & 0xFF);
            out.write(request);
            out.flush();

            byte[] reply = readFully(in, 4);
            if (reply[0] != 0x05) throw new IllegalStateException("The proxy spoke an unknown dialect");
            if (reply[1] != 0x00) {
                throw new IllegalStateException("The proxy could not reach the destination (code "
                        + (reply[1] & 0xFF) + ")");
            }
            // The bound address trails the reply and has to be drained, or it would be read as the
            // first bytes of the caller's own stream.
            switch (reply[3]) {
                case 0x01: readFully(in, 4 + 2); break;                       // IPv4 + port
                case 0x04: readFully(in, 16 + 2); break;                      // IPv6 + port
                case 0x03: readFully(in, (readFully(in, 1)[0] & 0xFF) + 2); break; // name + port
                default: throw new IllegalStateException("The proxy returned an unknown address type");
            }

            handed = true;
            return socket;
        } finally {
            if (!handed) closeQuietly(socket);
        }
    }

    /**
     * Whether the proxy can currently carry traffic to a known-good destination.
     *
     * <p>Returns false rather than throwing: every failure mode here means the same thing to the
     * caller, which is "try the next endpoint".
     */
    static boolean reaches(String proxyHost, int proxyPort, String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(proxyHost, proxyPort, host, port, timeoutMs);
            return true;
        } catch (Exception unreachable) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    /** The standard check: can this tunnel reach the open internet at all? */
    static boolean isUsable(String proxyHost, int proxyPort, int timeoutMs) {
        return reaches(proxyHost, proxyPort, PROBE_HOST, PROBE_PORT, timeoutMs);
    }

    /** How long the proxy takes to establish a connection, or -1 if it cannot. */
    static long latencyMillis(String proxyHost, int proxyPort, String host, int port, int timeoutMs) {
        long started = System.nanoTime();
        Socket socket = null;
        try {
            socket = connect(proxyHost, proxyPort, host, port, timeoutMs);
            return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        } catch (Exception unreachable) {
            return -1L;
        } finally {
            closeQuietly(socket);
        }
    }

    /** Reads exactly this many bytes or fails; a short read here would be misread as a reply. */
    private static byte[] readFully(InputStream in, int count) throws Exception {
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count) {
            int step = in.read(buffer, read, count - read);
            if (step < 0) throw new IllegalStateException("The proxy closed the connection early");
            read += step;
        }
        return buffer;
    }

    static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (Exception ignored) { }
    }
}
