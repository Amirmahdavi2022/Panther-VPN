package com.firstham.aethergui.vpngate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Keeps a local copy of the VPN Gate directory and refreshes it from the live feed.
 *
 * This is why the app needs no server list of its own and no release to add a location: the
 * directory is fetched at runtime, so a relay that appears in Japan today is selectable today.
 *
 * The cache exists so a failed or slow refresh is never fatal - the app falls back to the last
 * good directory and stays usable offline. Refreshes are only attempted when the cache is older
 * than {@link #REFRESH_INTERVAL_MS}; callers wanting an explicit user-triggered reload pass
 * force = true.
 */
public final class VpnGateRepository {
    public static final long REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1000L;

    private static final String CACHE_FILE = "vpngate-directory.csv";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int MAX_FEED_BYTES = 24 * 1024 * 1024;

    private final File cacheFile;

    public VpnGateRepository(File cacheDirectory) {
        this.cacheFile = new File(cacheDirectory, CACHE_FILE);
    }

    /** Age of the cached directory in milliseconds, or Long.MAX_VALUE when there is none. */
    public long cacheAgeMs() {
        if (!cacheFile.isFile()) return Long.MAX_VALUE;
        return System.currentTimeMillis() - cacheFile.lastModified();
    }

    public boolean isStale() {
        return cacheAgeMs() > REFRESH_INTERVAL_MS;
    }

    /**
     * The current directory, refreshing first when the cache is stale or force is set.
     *
     * A refresh that fails, times out, or returns something unparseable leaves the existing cache
     * untouched and returns it - a bad network moment must not empty the server list.
     */
    public List<VpnGateServer> load(boolean force) {
        if (force || isStale()) {
            try {
                String feed = download();
                List<VpnGateServer> fresh = VpnGateDirectory.parse(feed);
                // Only adopt a refresh that actually produced servers; an empty or error-page
                // response is worse than yesterday's working list.
                if (!fresh.isEmpty()) {
                    write(feed);
                    return fresh;
                }
            } catch (Exception ignored) {
                // Fall through to the cache.
            }
        }
        return cached();
    }

    /** The cached directory without touching the network. Empty when there is no usable cache. */
    public List<VpnGateServer> cached() {
        if (!cacheFile.isFile()) return Collections.emptyList();
        try {
            byte[] bytes = Files.readAllBytes(cacheFile.toPath());
            return VpnGateDirectory.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private String download() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(VpnGateDirectory.FEED_URL).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "PantherVPN");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("VPN Gate feed returned HTTP " + status);
            }

            InputStream stream = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                stream = new GZIPInputStream(stream);
            }
            return read(stream);
        } finally {
            connection.disconnect();
        }
    }

    private String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int count;
            while ((count = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
                if (buffer.size() > MAX_FEED_BYTES) {
                    throw new IOException("VPN Gate feed exceeded " + MAX_FEED_BYTES + " bytes");
                }
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** Writes via a temporary file so an interrupted write cannot corrupt a good cache. */
    private void write(String feed) {
        File temporary = new File(cacheFile.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(feed.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception ignored) {
            temporary.delete();
            return;
        }
        if (!temporary.renameTo(cacheFile)) temporary.delete();
    }
}
