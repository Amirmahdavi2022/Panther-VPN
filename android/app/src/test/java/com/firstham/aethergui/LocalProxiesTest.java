package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Finding another app's SOCKS5 proxy on loopback, against real local sockets. */
public class LocalProxiesTest {

    @Test
    public void onlyTheNoAuthAnswerCounts() {
        assertTrue(LocalProxies.acceptsGreeting(0x05, 0x00));
        assertFalse(LocalProxies.acceptsGreeting(0x05, 0xFF)); // no acceptable method
        assertFalse(LocalProxies.acceptsGreeting(0x05, 0x02)); // wants a password
        assertFalse(LocalProxies.acceptsGreeting(0x04, 0x00));
        assertFalse(LocalProxies.acceptsGreeting(-1, -1));     // closed straight away
    }

    @Test
    public void ourOwnEnginesAreNeverMistakenForAnotherApp() {
        for (int port : LocalProxies.OWN_PORTS) {
            assertTrue(LocalProxies.isOwn(port));
            assertFalse(LocalProxies.speaksSocks5(port, 200));
        }
        for (int port : LocalProxies.CANDIDATE_PORTS) assertFalse(LocalProxies.isOwn(port));
    }

    @Test
    public void upstreamIsWhatTheCoreTakes() {
        assertEquals("socks5://127.0.0.1:10808", LocalProxies.upstream(10808));
    }

    @Test
    public void aRealSocks5ServerIsFound() throws Exception {
        try (ServerSocket server = fakeServer(new byte[] {0x05, 0x00})) {
            int port = server.getLocalPort();
            assertTrue(LocalProxies.speaksSocks5(port, 1000));
            assertEquals(port, LocalProxies.find(new int[] {closedPort(), port}, 1000));
        }
    }

    @Test
    public void aPasswordProxyIsSkipped() throws Exception {
        try (ServerSocket server = fakeServer(new byte[] {0x05, (byte) 0xFF})) {
            assertFalse(LocalProxies.speaksSocks5(server.getLocalPort(), 1000));
        }
    }

    @Test
    public void somethingThatIsNotSocksIsSkipped() throws Exception {
        try (ServerSocket server = fakeServer("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("US-ASCII"))) {
            assertFalse(LocalProxies.speaksSocks5(server.getLocalPort(), 1000));
        }
    }

    @Test
    public void aSilentListenerTimesOutInsteadOfHanging() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            long start = System.currentTimeMillis();
            assertFalse(LocalProxies.speaksSocks5(server.getLocalPort(), 300));
            assertTrue(System.currentTimeMillis() - start < 3000);
        }
    }

    @Test
    public void nothingListeningMeansNotFound() throws Exception {
        assertEquals(-1, LocalProxies.find(new int[] {closedPort()}, 300));
        assertEquals(-1, LocalProxies.find(null, 300));
        assertFalse(LocalProxies.speaksSocks5(0, 300));
        assertFalse(LocalProxies.speaksSocks5(70000, 300));
    }

    /** A one-connection server that reads the greeting and answers with {@code reply}. */
    private static ServerSocket fakeServer(byte[] reply) throws Exception {
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    byte[] greeting = new byte[3];
                    int got = 0;
                    while (got < 3) {
                        int n = in.read(greeting, got, 3 - got);
                        if (n < 0) break;
                        got += n;
                    }
                    OutputStream out = client.getOutputStream();
                    out.write(reply);
                    out.flush();
                } catch (Exception closed) {
                    return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private static int closedPort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        }
    }
}
