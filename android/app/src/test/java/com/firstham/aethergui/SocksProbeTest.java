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
 * <p>A mock would only prove the code calls the methods we expected it to call. The point of this
 * class is the wire format, so the tests speak the actual protocol over an actual socket and
 * assert on the bytes that arrive.
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

    /** A minimal SOCKS5 server that answers one request with a chosen reply code. */
    private static final class FakeProxy implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;
        final AtomicReference<String> requestedHost = new AtomicReference<>();
        final AtomicReference<Integer> requestedPort = new AtomicReference<>();

        FakeProxy(byte replyCode, byte addressType, boolean offerAuth) throws Exception {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            worker = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(4000);
                    InputStream in = client.getInputStream();
                    OutputStream out = client.getOutputStream();

                    byte[] greeting = new byte[3];
                    readFully(in, greeting);
                    // Answer the greeting; offerAuth makes the server demand credentials instead.
                    out.write(new byte[] { 0x05, offerAuth ? (byte) 0x02 : (byte) 0x00 });
                    out.flush();
                    if (offerAuth) return;

                    byte[] head = new byte[5];
                    readFully(in, head);
                    byte[] name = new byte[head[4] & 0xFF];
                    readFully(in, name);
                    byte[] port = new byte[2];
                    readFully(in, port);
                    requestedHost.set(new String(name, StandardCharsets.UTF_8));
                    requestedPort.set(((port[0] & 0xFF) << 8) | (port[1] & 0xFF));

                    out.write(new byte[] { 0x05, replyCode, 0x00, addressType });
                    if (addressType == 0x01) out.write(new byte[] { 0, 0, 0, 0, 0, 0 });
                    else if (addressType == 0x04) out.write(new byte[18]);
                    else { out.write(2); out.write("ok".getBytes(StandardCharsets.UTF_8)); out.write(new byte[2]); }
                    out.flush();
                    // Hold the socket open briefly so the prober sees an established connection.
                    Thread.sleep(120);
                } catch (Exception ignored) { }
            });
            worker.setDaemon(true);
            worker.start();
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

    @Test public void completesTheHandshakeAndReportsSuccess() throws Exception {
        try (FakeProxy proxy = new FakeProxy((byte) 0x00, (byte) 0x01, false)) {
            check(SocksProbe.reaches("127.0.0.1", proxy.port(), "example.org", 443, 3000),
                    "a proxy that succeeds is reported as reachable");
            check("example.org".equals(proxy.requestedHost.get()),
                    "the destination host reaches the proxy intact");
            check(Integer.valueOf(443).equals(proxy.requestedPort.get()),
                    "the destination port is encoded big-endian");
        }
    }

    @Test public void sendsTheNameSoTheFarSideResolvesIt() throws Exception {
        // Resolving on the device would hand the destination to the local resolver, which is
        // exactly what the tunnel is meant to prevent.
        try (FakeProxy proxy = new FakeProxy((byte) 0x00, (byte) 0x01, false)) {
            SocksProbe.reaches("127.0.0.1", proxy.port(), "cloudflare.com", 443, 3000);
            String seen = proxy.requestedHost.get();
            check("cloudflare.com".equals(seen), "a name is sent, not an address: " + seen);
        }
    }

    @Test public void treatsARefusalAsUnreachable() throws Exception {
        // 0x05 is "connection refused by the destination".
        try (FakeProxy proxy = new FakeProxy((byte) 0x05, (byte) 0x01, false)) {
            check(!SocksProbe.reaches("127.0.0.1", proxy.port(), "example.org", 443, 3000),
                    "a refusal is not mistaken for a working tunnel");
        }
    }

    @Test public void refusesAProxyThatDemandsCredentials() throws Exception {
        try (FakeProxy proxy = new FakeProxy((byte) 0x00, (byte) 0x01, true)) {
            check(!SocksProbe.reaches("127.0.0.1", proxy.port(), "example.org", 443, 3000),
                    "an authenticating proxy is not our engine and is rejected");
        }
    }

    @Test public void drainsEveryBoundAddressShape() throws Exception {
        byte[] shapes = { 0x01, 0x03, 0x04 };
        for (byte shape : shapes) {
            try (FakeProxy proxy = new FakeProxy((byte) 0x00, shape, false)) {
                check(SocksProbe.reaches("127.0.0.1", proxy.port(), "example.org", 443, 3000),
                        "bound address type " + shape + " is drained rather than left in the stream");
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
        check(SocksProbe.latencyMillis("127.0.0.1", deadPort, "example.org", 443, 1500) < 0,
                "an unreachable proxy reports a negative latency");
    }

    @Test public void measuresLatencyOnASuccessfulConnection() throws Exception {
        try (FakeProxy proxy = new FakeProxy((byte) 0x00, (byte) 0x01, false)) {
            long ms = SocksProbe.latencyMillis("127.0.0.1", proxy.port(), "example.org", 443, 3000);
            check(ms > 0, "a working proxy reports a positive latency");
            check(ms < 3000, "loopback latency is well under the timeout: " + ms + "ms");
        }
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
        sendsTheNameSoTheFarSideResolvesIt();
        treatsARefusalAsUnreachable();
        refusesAProxyThatDemandsCredentials();
        drainsEveryBoundAddressShape();
        reportsNothingListeningAsUnreachable();
        measuresLatencyOnASuccessfulConnection();
    }


    /**
     * Standalone entry point, for running these checks without an Android toolchain around.
     * The exit code lives here and not in runAllChecks, because a System.exit inside a unit
     * test kills the test JVM and turns a clear failure report into an opaque crash.
     */
    public static void main(String[] args) throws Exception {
        new SocksProbeTest().runAllChecks();
        if (failures > 0) System.exit(1);
    }
}
