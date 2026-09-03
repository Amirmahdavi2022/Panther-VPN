package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exercises the probe against a real SOCKS5 server running in this process.
 *
 * <p>A mock would only prove the code calls the methods we expected it to call. What matters here
 * is the wire format and, above all, the difference between a proxy that answers and a tunnel that
 * works, so these tests speak the actual protocol over an actual socket.
 *
 * <p>Runs under CI through the {@code @Test} methods, and standalone through {@code main}.
 */
public final class SocksProbeTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    /** How the fake proxy behaves once the SOCKS handshake is done. */
    private enum Behaviour {
        /** Answers the request the way a working tunnel would. */
        ANSWERS,
        /**
         * Completes the handshake and then sends nothing, which is exactly what the engine's core
         * does when the server behind it is dead: it accepts locally and dials lazily.
         */
        SILENT,
        /** Sends bytes that are not an HTTP reply at all. */
        GARBAGE
    }

    /** A minimal SOCKS5 server, controllable enough to reproduce the failures that matter. */
    private static final class FakeProxy implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;
        final AtomicReference<String> requestedHost = new AtomicReference<>();
        final AtomicReference<Integer> requestedPort = new AtomicReference<>();
        final AtomicReference<String> requestLine = new AtomicReference<>();

        FakeProxy(byte replyCode, byte addressType, boolean demandAuth, Behaviour behaviour)
                throws Exception {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            worker = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket client = server.accept()) {
                        client.setSoTimeout(4000);
                        InputStream in = client.getInputStream();
                        OutputStream out = client.getOutputStream();

                        readFully(in, new byte[3]);
                        out.write(new byte[] { 0x05, demandAuth ? (byte) 0x02 : (byte) 0x00 });
                        out.flush();
                        if (demandAuth) continue;

                        byte[] head = new byte[5];
                        readFully(in, head);
                        byte[] name = new byte[head[4] & 0xFF];
                        readFully(in, name);
                        byte[] port = new byte[2];
                        readFully(in, port);
                        requestedHost.set(new String(name, StandardCharsets.UTF_8));
                        requestedPort.set(((port[0] & 0xFF) << 8) | (port[1] & 0xFF));

                        out.write(new byte[] { 0x05, replyCode, 0x00, addressType });
                        if (addressType == 0x01) out.write(new byte[6]);
                        else if (addressType == 0x04) out.write(new byte[18]);
                        else {
                            out.write(2);
                            out.write("ok".getBytes(StandardCharsets.UTF_8));
                            out.write(new byte[2]);
                        }
                        out.flush();
                        if (replyCode != 0x00) continue;
                        if (behaviour == Behaviour.SILENT) { Thread.sleep(2500); continue; }

                        StringBuilder line = new StringBuilder();
                        int c;
                        while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') line.append((char) c);
                        requestLine.set(line.toString());

                        if (behaviour == Behaviour.GARBAGE) {
                            out.write("not an http reply at all\r\n".getBytes(StandardCharsets.UTF_8));
                        } else {
                            out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                                    .getBytes(StandardCharsets.UTF_8));
                        }
                        out.flush();
                    } catch (Exception ignored) {
                        return;
                    }
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        FakeProxy(Behaviour behaviour) throws Exception {
            this((byte) 0x00, (byte) 0x01, false, behaviour);
        }

        int port() { return server.getLocalPort(); }

        @Override public void close() {
            try { server.close(); } catch (Exception ignored) { }
            worker.interrupt();
        }

        private static void readFully(InputStream in, byte[] buffer) throws Exception {
            int read = 0;
            while (read < buffer.length) {
                int step = in.read(buffer, read, buffer.length - read);
                if (step < 0) throw new IllegalStateException("short read");
                read += step;
            }
        }
    }

    // --- checks --------------------------------------------------------------------------------

    @Test public void completesTheHandshakeAndReportsSuccess() throws Exception {
        try (FakeProxy proxy = new FakeProxy(Behaviour.ANSWERS)) {
            check(SocksProbe.reaches("127.0.0.1", proxy.port(), "example.org", 443, 3000),
                    "a proxy that succeeds completes the handshake");
            check("example.org".equals(proxy.requestedHost.get()),
                    "the destination host reaches the proxy intact");
            check(Integer.valueOf(443).equals(proxy.requestedPort.get()),
                    "the destination port is encoded big-endian");
        }
    }

    /**
     * The trap this whole class exists to catch.
     *
     * <p>The engine's core answers the SOCKS handshake itself and only dials the real server once
     * a byte needs to go out. Pointed at a black-holed address it still reports success in about a
     * millisecond - measured against the real core, not assumed. Trusting the handshake would mean
     * showing "connected" over a tunnel carrying nothing, which is worse than showing a failure.
     */
    @Test public void refusesATunnelThatAnswersButCarriesNothing() throws Exception {
        try (FakeProxy proxy = new FakeProxy(Behaviour.SILENT)) {
            check(SocksProbe.reaches("127.0.0.1", proxy.port(), "example.org", 443, 2000),
                    "the handshake still succeeds, which is exactly the trap");
            check(!SocksProbe.carriesTraffic("127.0.0.1", proxy.port(), 2000),
                    "but nothing comes back, so the tunnel is not usable");
            check(SocksProbe.latencyMillis("127.0.0.1", proxy.port(), 2000) < 0,
                    "and it has no latency worth scoring");
        }
    }

    @Test public void requiresAnActualHttpReply() throws Exception {
        try (FakeProxy proxy = new FakeProxy(Behaviour.GARBAGE)) {
            check(!SocksProbe.carriesTraffic("127.0.0.1", proxy.port(), 2000),
                    "bytes alone are not proof; they have to be a reply");
        }
    }

    @Test public void acceptsATunnelThatReallyAnswers() throws Exception {
        try (FakeProxy proxy = new FakeProxy(Behaviour.ANSWERS)) {
            check(SocksProbe.carriesTraffic("127.0.0.1", proxy.port(), 3000),
                    "a real reply proves the tunnel carries traffic");
            long ms = SocksProbe.latencyMillis("127.0.0.1", proxy.port(), 3000);
            check(ms > 0 && ms < 3000, "a full round trip is measured: " + ms + "ms");
            String request = proxy.requestLine.get();
            check(request != null && request.startsWith("GET "), "a real request is sent: " + request);
        }
    }

    @Test public void sendsTheNameSoTheFarSideResolvesIt() throws Exception {
        // Resolving on the device would hand the destination to the local resolver, which is
        // exactly what the tunnel exists to prevent.
        try (FakeProxy proxy = new FakeProxy(Behaviour.ANSWERS)) {
            SocksProbe.carriesTraffic("127.0.0.1", proxy.port(), 3000);
            String seen = proxy.requestedHost.get();
            check(SocksProbe.PROBE_HOST.equals(seen), "a name is sent, not an address: " + seen);
        }
    }

    @Test public void treatsARefusalAsUnreachable() throws Exception {
        try (FakeProxy proxy = new FakeProxy((byte) 0x05, (byte) 0x01, false, Behaviour.ANSWERS)) {
            check(!SocksProbe.reaches("127.0.0.1", proxy.port(), "example.org", 443, 3000),
                    "a refusal is not mistaken for a working tunnel");
            check(!SocksProbe.carriesTraffic("127.0.0.1", proxy.port(), 3000),
                    "and it carries nothing either");
        }
    }

    @Test public void refusesAProxyThatDemandsCredentials() throws Exception {
        try (FakeProxy proxy = new FakeProxy((byte) 0x00, (byte) 0x01, true, Behaviour.ANSWERS)) {
            check(!SocksProbe.reaches("127.0.0.1", proxy.port(), "example.org", 443, 3000),
                    "an authenticating proxy is not our engine and is rejected");
        }
    }

    @Test public void drainsEveryBoundAddressShape() throws Exception {
        for (byte shape : new byte[] { 0x01, 0x03, 0x04 }) {
            try (FakeProxy proxy = new FakeProxy((byte) 0x00, shape, false, Behaviour.ANSWERS)) {
                check(SocksProbe.carriesTraffic("127.0.0.1", proxy.port(), 3000),
                        "bound address type " + shape + " is drained, not left in the stream");
            }
        }
    }

    @Test public void reportsNothingListeningAsUnreachable() throws Exception {
        int deadPort;
        try (ServerSocket scratch = new ServerSocket()) {
            scratch.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            deadPort = scratch.getLocalPort();
        }
        check(!SocksProbe.reaches("127.0.0.1", deadPort, "example.org", 443, 1500),
                "a closed port is unreachable, not an exception the caller has to handle");
        check(!SocksProbe.carriesTraffic("127.0.0.1", deadPort, 1500), "and it carries nothing");
        check(SocksProbe.latencyMillis("127.0.0.1", deadPort, 1500) < 0,
                "an unreachable proxy reports a negative latency");
    }

    /** CI runs this; it fails the build if any check above failed. */
    @Test public void everyCheckPasses() throws Exception {
        int before = failures;
        runAllChecks();
        assertEquals("SOCKS probe checks failed", before, failures);
        assertTrue("No checks ran", checks > 0);
    }

    void runAllChecks() throws Exception {
        completesTheHandshakeAndReportsSuccess();
        refusesATunnelThatAnswersButCarriesNothing();
        requiresAnActualHttpReply();
        acceptsATunnelThatReallyAnswers();
        sendsTheNameSoTheFarSideResolvesIt();
        treatsARefusalAsUnreachable();
        refusesAProxyThatDemandsCredentials();
        drainsEveryBoundAddressShape();
        reportsNothingListeningAsUnreachable();
        System.out.println("SocksProbe: " + checks + " checks, " + failures + " failures");
    }

    /**
     * Standalone entry point, for running these checks without an Android toolchain around. The
     * exit code lives here and not in runAllChecks, because a System.exit inside a unit test kills
     * the test JVM and turns a clear failure report into an opaque crash.
     */
    public static void main(String[] args) throws Exception {
        new SocksProbeTest().runAllChecks();
        if (failures > 0) System.exit(1);
    }
}
