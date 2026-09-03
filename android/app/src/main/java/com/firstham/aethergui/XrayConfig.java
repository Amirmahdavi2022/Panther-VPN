package com.firstham.aethergui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns one {@link ProxyConfig} into the JSON the Stealth engine's core reads.
 *
 * <p>The core is a general-purpose proxy engine driven entirely by a JSON document. Everything the
 * Stealth engine does is decided here: which endpoint to dial, over which transport, and on which
 * local port to publish the SOCKS5 proxy that the TUN bridge is already pointed at.
 *
 * <p>Two rules shape the whole file:
 *
 * <ul>
 *   <li><b>The SOCKS port is fixed.</b> The TUN bridge is configured once, at connect time, to talk
 *       to a port on loopback. If the engine keeps that same port while swapping which server it
 *       dials out to, the TUN interface is never rebuilt and nothing on the phone notices the
 *       change. A port chosen at random per run would force the bridge to be rebuilt on every swap,
 *       which is exactly the dropped connection this design exists to avoid.
 *   <li><b>No routing rules that need the geo databases.</b> Everything goes out through the single
 *       proxy outbound. That keeps the config honest about what it does, and it means the two
 *       geo data files the core library ships can be stripped out of the build - they are 28 MB
 *       that would otherwise be carried in every APK for rules we never write.
 * </ul>
 *
 * <p>Nothing here touches Android or the core library, so it runs on a desktop JVM and is tested
 * for real rather than eyeballed.
 */
final class XrayConfig {

    /**
     * Where the Stealth engine publishes its SOCKS5 proxy.
     *
     * <p>Deliberately not the port the Turbo engine uses. During a handover both engines are alive
     * for a moment, and two listeners on one port means the second one fails to bind.
     */
    static final int SOCKS_PORT = 1820;

    /** Loopback only. The proxy must never be reachable from off the device. */
    static final String SOCKS_LISTEN = "127.0.0.1";

    private XrayConfig() { }

    /**
     * Whether the engine can actually dial this endpoint.
     *
     * <p>🚨 This is narrower than what {@link ProxyConfig} can parse, and the difference matters.
     * The pools are full of hysteria2 and tuic entries, and the parser reads them correctly, but
     * this core has no working client for either: tuic it does not implement at all, and its
     * hysteria client config carries only a version, an address and a port - there is nowhere to
     * put the password, so an endpoint built from a hysteria2 URI could never authenticate.
     * Handing those to the engine would burn test budget and connection attempts on endpoints that
     * cannot succeed, so they are filtered out here instead, at the one place that knows.
     *
     * <p>What is left still covers the large majority of every pool we fetch, including all of the
     * Reality entries, which are the ones worth having.
     */
    static boolean supports(ProxyConfig endpoint) {
        if (endpoint == null || endpoint.host == null || endpoint.host.isEmpty()) return false;
        if (endpoint.port <= 0 || endpoint.port > 65535) return false;
        if (endpoint.id == null || endpoint.id.isEmpty()) return false;
        switch (endpoint.protocol) {
            case "vless":
            case "trojan":
            case "ss":
                return true;
            default:
                return false;
        }
    }

    /** Keeps only the endpoints this engine can dial, in the order they were given. */
    static List<ProxyConfig> supported(List<ProxyConfig> endpoints) {
        List<ProxyConfig> out = new ArrayList<>();
        if (endpoints == null) return out;
        for (ProxyConfig endpoint : endpoints) {
            if (supports(endpoint)) out.add(endpoint);
        }
        return out;
    }

    /** Builds the config for one endpoint on the standard port. */
    static String build(ProxyConfig endpoint) {
        return build(endpoint, SOCKS_PORT, "warning");
    }

    /**
     * Builds the config document.
     *
     * @param endpoint  the server to dial; must pass {@link #supports}
     * @param socksPort the loopback port to publish the SOCKS5 proxy on
     * @param logLevel  one of the core's levels: debug, info, warning, error, none
     * @throws IllegalArgumentException if the endpoint is one the engine cannot dial, so a
     *                                  mistake shows up here rather than as an opaque core error
     */
    static String build(ProxyConfig endpoint, int socksPort, String logLevel) {
        if (!supports(endpoint)) {
            throw new IllegalArgumentException("The Stealth engine cannot dial " + endpoint);
        }
        if (socksPort <= 0 || socksPort > 65535) {
            throw new IllegalArgumentException("Invalid SOCKS port: " + socksPort);
        }

        StringBuilder json = new StringBuilder(1024);
        json.append("{\"log\":{\"loglevel\":").append(quote(level(logLevel))).append("},");

        json.append("\"inbounds\":[{\"tag\":\"socks-in\",\"listen\":").append(quote(SOCKS_LISTEN))
            .append(",\"port\":").append(socksPort)
            .append(",\"protocol\":\"socks\",\"settings\":{\"auth\":\"noauth\",\"udp\":true,")
            .append("\"address\":\"127.0.0.1\"},")
            // Sniffing recovers the real hostname from the traffic itself. The bridge hands us an
            // IP address, so without this the server name never reaches the outbound and TLS to a
            // virtual host fails.
            .append("\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"],")
            .append("\"routeOnly\":false}}],");

        json.append("\"outbounds\":[");
        appendProxyOutbound(json, endpoint);
        json.append(",{\"tag\":\"block\",\"protocol\":\"blackhole\"}],");

        // AsIs keeps the sniffed name as the name: no lookup happens on this device, so a poisoned
        // resolver on the local network cannot redirect anything.
        json.append("\"routing\":{\"domainStrategy\":\"AsIs\",\"rules\":[]}}");
        return json.toString();
    }

    private static void appendProxyOutbound(StringBuilder json, ProxyConfig endpoint) {
        json.append("{\"tag\":\"proxy\",\"protocol\":").append(quote(protocolName(endpoint)))
            .append(",\"settings\":{");
        switch (endpoint.protocol) {
            case "vless":   appendVless(json, endpoint); break;
            case "trojan":  appendTrojan(json, endpoint); break;
            default:        appendShadowsocks(json, endpoint); break;
        }
        json.append("}");
        appendStreamSettings(json, endpoint);
        json.append("}");
    }

    private static String protocolName(ProxyConfig endpoint) {
        return "ss".equals(endpoint.protocol) ? "shadowsocks" : endpoint.protocol;
    }

    private static void appendVless(StringBuilder json, ProxyConfig endpoint) {
        json.append("\"vnext\":[{\"address\":").append(quote(endpoint.host))
            .append(",\"port\":").append(endpoint.port)
            .append(",\"users\":[{\"id\":").append(quote(endpoint.id))
            .append(",\"encryption\":").append(quote(orDefault(endpoint, "encryption", "none")));
        // The flow only means anything alongside TLS or Reality. Sent on a plain connection the
        // core rejects the whole config, so a pool entry with a stray flow would cost us the
        // endpoint rather than just the option.
        String flow = param(endpoint, "flow");
        if (!flow.isEmpty() && isSecured(endpoint)) json.append(",\"flow\":").append(quote(flow));
        json.append(",\"level\":0}]}]");
    }

    private static void appendTrojan(StringBuilder json, ProxyConfig endpoint) {
        json.append("\"servers\":[{\"address\":").append(quote(endpoint.host))
            .append(",\"port\":").append(endpoint.port)
            .append(",\"password\":").append(quote(endpoint.id))
            .append(",\"level\":0}]");
    }

    private static void appendShadowsocks(StringBuilder json, ProxyConfig endpoint) {
        // The credential arrives as method:password, already base64-decoded by the parser.
        String method = "aes-256-gcm";
        String password = endpoint.id;
        int colon = endpoint.id.indexOf(':');
        if (colon > 0) {
            method = endpoint.id.substring(0, colon);
            password = endpoint.id.substring(colon + 1);
        }
        json.append("\"servers\":[{\"address\":").append(quote(endpoint.host))
            .append(",\"port\":").append(endpoint.port)
            .append(",\"method\":").append(quote(method))
            .append(",\"password\":").append(quote(password))
            .append(",\"uot\":false,\"level\":0}]");
    }

    private static void appendStreamSettings(StringBuilder json, ProxyConfig endpoint) {
        String network = network(endpoint);
        String security = security(endpoint);
        json.append(",\"streamSettings\":{\"network\":").append(quote(network))
            .append(",\"security\":").append(quote(security));

        if ("reality".equals(security)) appendReality(json, endpoint);
        else if ("tls".equals(security)) appendTls(json, endpoint);

        switch (network) {
            case "ws":          appendWebsocket(json, endpoint); break;
            case "httpupgrade": appendHttpUpgrade(json, endpoint); break;
            case "xhttp":       appendXhttp(json, endpoint); break;
            case "grpc":        appendGrpc(json, endpoint); break;
            case "http":        appendHttp2(json, endpoint); break;
            case "kcp":         appendKcp(json, endpoint); break;
            default:            appendTcp(json, endpoint); break;
        }
        json.append("}");
    }

    private static void appendReality(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"realitySettings\":{\"serverName\":").append(quote(serverName(endpoint)))
            .append(",\"fingerprint\":").append(quote(orDefault(endpoint, "fp", "chrome")))
            .append(",\"publicKey\":").append(quote(param(endpoint, "pbk")))
            .append(",\"shortId\":").append(quote(param(endpoint, "sid")))
            .append(",\"spiderX\":").append(quote(param(endpoint, "spx")))
            .append(",\"show\":false}");
    }

    private static void appendTls(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"tlsSettings\":{\"serverName\":").append(quote(serverName(endpoint)))
            .append(",\"fingerprint\":").append(quote(orDefault(endpoint, "fp", "chrome")))
            .append(",\"allowInsecure\":").append(insecure(endpoint));
        String alpn = param(endpoint, "alpn");
        if (!alpn.isEmpty()) {
            json.append(",\"alpn\":[");
            String[] parts = alpn.split(",");
            for (int i = 0; i < parts.length; i++) {
                String value = parts[i].trim();
                if (value.isEmpty()) continue;
                if (i > 0) json.append(',');
                json.append(quote(value));
            }
            json.append(']');
        }
        json.append("}");
    }

    private static void appendWebsocket(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"wsSettings\":{\"path\":").append(quote(path(endpoint)));
        String host = hostHeader(endpoint);
        if (!host.isEmpty()) json.append(",\"host\":").append(quote(host));
        json.append("}");
    }

    private static void appendHttpUpgrade(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"httpupgradeSettings\":{\"path\":").append(quote(path(endpoint)));
        String host = hostHeader(endpoint);
        if (!host.isEmpty()) json.append(",\"host\":").append(quote(host));
        json.append("}");
    }

    private static void appendXhttp(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"xhttpSettings\":{\"path\":").append(quote(path(endpoint)))
            .append(",\"mode\":").append(quote(orDefault(endpoint, "mode", "auto")));
        String host = hostHeader(endpoint);
        if (!host.isEmpty()) json.append(",\"host\":").append(quote(host));
        json.append("}");
    }

    private static void appendGrpc(StringBuilder json, ProxyConfig endpoint) {
        String service = param(endpoint, "serviceName");
        if (service.isEmpty()) service = param(endpoint, "path");
        json.append(",\"grpcSettings\":{\"serviceName\":").append(quote(service))
            .append(",\"multiMode\":").append("multi".equals(param(endpoint, "mode")))
            .append(",\"idle_timeout\":60,\"health_check_timeout\":20}");
    }

    private static void appendHttp2(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"httpSettings\":{\"path\":").append(quote(path(endpoint)));
        String host = hostHeader(endpoint);
        if (!host.isEmpty()) json.append(",\"host\":[").append(quote(host)).append(']');
        json.append("}");
    }

    private static void appendKcp(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"kcpSettings\":{\"mtu\":1350,\"tti\":50,\"uplinkCapacity\":12,")
            .append("\"downlinkCapacity\":100,\"congestion\":false,\"readBufferSize\":1,")
            .append("\"writeBufferSize\":1,\"header\":{\"type\":")
            .append(quote(orDefault(endpoint, "headerType", "none"))).append("},\"seed\":")
            .append(quote(param(endpoint, "seed"))).append("}");
    }

    private static void appendTcp(StringBuilder json, ProxyConfig endpoint) {
        // Plain TCP needs no block at all unless the entry asks to be disguised as HTTP, in which
        // case the core needs the request shape spelled out.
        if (!"http".equalsIgnoreCase(param(endpoint, "headerType"))) {
            json.append(",\"tcpSettings\":{\"header\":{\"type\":\"none\"}}");
            return;
        }
        json.append(",\"tcpSettings\":{\"header\":{\"type\":\"http\",\"request\":{\"version\":\"1.1\",")
            .append("\"method\":\"GET\",\"path\":[").append(quote(path(endpoint))).append("],")
            .append("\"headers\":{\"Host\":[").append(quote(hostHeaderOrServer(endpoint)))
            .append("],\"User-Agent\":[\"Mozilla/5.0\"],\"Accept-Encoding\":[\"gzip, deflate\"],")
            .append("\"Connection\":[\"keep-alive\"],\"Pragma\":\"no-cache\"}}}}");
    }

    // --- small readers -------------------------------------------------------------------------

    /** The transport the entry declares, normalised to the names the core accepts. */
    static String network(ProxyConfig endpoint) {
        String type = param(endpoint, "type");
        if (type.isEmpty()) type = param(endpoint, "network");
        switch (type.toLowerCase(Locale.US)) {
            case "ws":          return "ws";
            case "grpc":        return "grpc";
            case "h2":
            case "http":        return "http";
            case "httpupgrade": return "httpupgrade";
            case "xhttp":
            case "splithttp":   return "xhttp";  // renamed upstream; pools still publish both
            case "kcp":
            case "mkcp":        return "kcp";
            default:            return "tcp";
        }
    }

    /** The TLS flavour, defaulting to none so a missing value never invents encryption. */
    static String security(ProxyConfig endpoint) {
        String security = param(endpoint, "security");
        switch (security.toLowerCase(Locale.US)) {
            case "reality": return "reality";
            case "tls":
            case "xtls":    return "tls";  // xtls as a transport security is long gone; tls is the
                                           // honest reading of an entry that still says it
            default:        return "none";
        }
    }

    private static boolean isSecured(ProxyConfig endpoint) {
        return !"none".equals(security(endpoint));
    }

    /** The name to present in the handshake: the explicit one, else the header host, else the address. */
    private static String serverName(ProxyConfig endpoint) {
        String sni = param(endpoint, "sni");
        if (!sni.isEmpty()) return sni;
        String host = param(endpoint, "host");
        if (!host.isEmpty()) return host;
        return endpoint.host;
    }

    private static String hostHeader(ProxyConfig endpoint) {
        String host = param(endpoint, "host");
        if (!host.isEmpty()) return host;
        return param(endpoint, "sni");
    }

    private static String hostHeaderOrServer(ProxyConfig endpoint) {
        String host = hostHeader(endpoint);
        return host.isEmpty() ? endpoint.host : host;
    }

    private static String path(ProxyConfig endpoint) {
        String path = param(endpoint, "path");
        if (path.isEmpty()) return "/";
        return path.startsWith("/") ? path : "/" + path;
    }

    private static boolean insecure(ProxyConfig endpoint) {
        String value = param(endpoint, "allowInsecure");
        if (value.isEmpty()) value = param(endpoint, "insecure");
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static String param(ProxyConfig endpoint, String key) {
        Map<String, String> params = endpoint.params;
        if (params == null) return "";
        String value = params.get(key);
        return value == null ? "" : value.trim();
    }

    private static String orDefault(ProxyConfig endpoint, String key, String fallback) {
        String value = param(endpoint, key);
        return value.isEmpty() ? fallback : value;
    }

    private static String level(String logLevel) {
        if (logLevel == null) return "warning";
        switch (logLevel.toLowerCase(Locale.US)) {
            case "debug":
            case "info":
            case "warning":
            case "error":
            case "none":
                return logLevel.toLowerCase(Locale.US);
            default:
                return "warning";
        }
    }

    /** Quotes and escapes a value. Pool data is untrusted text and lands straight in this JSON. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
