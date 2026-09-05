package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Checks the config the Stealth engine hands to its core.
 *
 * <p>Runs two ways on purpose. CI runs the {@code @Test} methods, and
 * {@code java -cp <classes> com.firstham.aethergui.XrayConfigTest} runs the same checks with a
 * count at the end, which is how they get exercised while there is no Android toolchain around.
 */
public final class XrayConfigTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    private static ProxyConfig parse(String uri) {
        ProxyConfig config = ProxyConfig.parse(uri);
        if (config == null) throw new IllegalStateException("The fixture did not parse: " + uri);
        return config;
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static final String VLESS_REALITY =
            "vless://11111111-2222-3333-4444-555555555555@example.net:443"
            + "?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome"
            + "&pbk=abcdefgHIJKLmnop0123456789&sid=7f&spx=%2F&type=tcp&flow=xtls-rprx-vision"
            + "#Reality%20node";

    private static final String VLESS_WS_TLS =
            "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@cdn.example.com:8443"
            + "?encryption=none&security=tls&type=ws&host=front.example.com&path=%2Fride"
            + "&sni=front.example.com&alpn=h2%2Chttp%2F1.1#WS";

    private static final String TROJAN_GRPC =
            "trojan://s3cret@relay.example.org:443?security=tls&type=grpc&serviceName=tunnel"
            + "&mode=multi&sni=relay.example.org#Trojan";

    private static final String SHADOWSOCKS =
            "ss://YWVzLTI1Ni1nY206aHVudGVyMg%3D%3D@ss.example.com:8388#SS";

    // --- checks --------------------------------------------------------------------------------

    @Test public void publishesSocksOnTheFixedLoopbackPort() {
        String json = XrayConfig.build(parse(VLESS_REALITY));
        check(json.contains("\"port\":" + XrayConfig.SOCKS_PORT), "inbound uses the fixed port");
        check(json.contains("\"listen\":\"127.0.0.1\""), "inbound listens on loopback only");
        check(json.contains("\"protocol\":\"socks\""), "inbound is a SOCKS proxy");
        check(json.contains("\"udp\":true"), "inbound forwards UDP");
        check(XrayConfig.SOCKS_PORT != 1819, "does not collide with the Turbo engine's port");
    }

    @Test public void carriesEveryRealityField() {
        String json = XrayConfig.build(parse(VLESS_REALITY));
        check(json.contains("\"security\":\"reality\""), "reality is declared");
        check(json.contains("\"publicKey\":\"abcdefgHIJKLmnop0123456789\""), "public key survives");
        check(json.contains("\"shortId\":\"7f\""), "short id survives");
        check(json.contains("\"spiderX\":\"/\""), "spiderX survives, url-decoded");
        check(json.contains("\"serverName\":\"www.microsoft.com\""), "sni becomes the server name");
        check(json.contains("\"fingerprint\":\"chrome\""), "fingerprint survives");
        check(json.contains("\"flow\":\"xtls-rprx-vision\""), "flow is kept alongside reality");
        check(json.contains("\"id\":\"11111111-2222-3333-4444-555555555555\""), "uuid survives");
    }

    @Test public void dropsFlowWhenThereIsNoTlsToCarryIt() {
        // A pool entry that names a flow but no security would be rejected wholesale by the core.
        // Losing the option is worth keeping the endpoint.
        ProxyConfig plain = parse("vless://11111111-2222-3333-4444-555555555555@plain.example:80"
                + "?encryption=none&type=tcp&flow=xtls-rprx-vision#Plain");
        String json = XrayConfig.build(plain);
        check(!json.contains("\"flow\""), "flow is dropped on an unsecured connection");
        check(json.contains("\"security\":\"none\""), "security is honestly reported as none");
    }

    @Test public void mapsWebsocketAndTls() {
        String json = XrayConfig.build(parse(VLESS_WS_TLS));
        check(json.contains("\"network\":\"ws\""), "network is ws");
        check(json.contains("\"wsSettings\""), "ws settings are present");
        check(json.contains("\"path\":\"/ride\""), "path is url-decoded");
        check(json.contains("\"host\":\"front.example.com\""), "host header is carried");
        check(json.contains("\"alpn\":[\"h2\",\"http/1.1\"]"), "alpn is split into a list");
        check(!json.contains("allowInsecure"), "the removed allowInsecure option is never emitted");
    }

    @Test public void mapsTrojanOverGrpc() {
        String json = XrayConfig.build(parse(TROJAN_GRPC));
        check(json.contains("\"protocol\":\"trojan\""), "protocol is trojan");
        check(json.contains("\"password\":\"s3cret\""), "password is carried");
        check(json.contains("\"serviceName\":\"tunnel\""), "grpc service name is carried");
        check(json.contains("\"multiMode\":true"), "multi mode is read from the entry");
        check(!json.contains("\"vnext\""), "trojan does not use the vless shape");
    }

    @Test public void splitsTheShadowsocksCredential() {
        String json = XrayConfig.build(parse(SHADOWSOCKS));
        check(json.contains("\"protocol\":\"shadowsocks\""), "ss is renamed for the core");
        check(json.contains("\"method\":\"aes-256-gcm\""), "cipher is taken from the credential");
        check(json.contains("\"password\":\"hunter2\""), "password is taken from the credential");
    }

    @Test public void refusesWhatTheCoreCannotDial() {
        // The parser reads these correctly; this core simply has no client that can use them.
        check(!XrayConfig.supports(parse("hysteria2://pass@h2.example.com:443#H2")),
                "hysteria2 is refused");
        check(!XrayConfig.supports(parse("hy2://pass@h2.example.com:443#H2")),
                "the hy2 alias is refused too");
        check(!XrayConfig.supports(parse("tuic://uuid@tuic.example.com:443#T")), "tuic is refused");
        check(XrayConfig.supports(parse(VLESS_REALITY)), "vless is accepted");
        check(XrayConfig.supports(parse(TROJAN_GRPC)), "trojan is accepted");
        check(XrayConfig.supports(parse(SHADOWSOCKS)), "shadowsocks is accepted");

        boolean threw = false;
        try { XrayConfig.build(parse("tuic://uuid@tuic.example.com:443#T")); }
        catch (IllegalArgumentException expected) { threw = true; }
        check(threw, "building an unsupported endpoint fails loudly rather than silently");
    }

    @Test public void filtersAListWithoutReorderingIt() {
        List<ProxyConfig> mixed = Arrays.asList(
                parse(VLESS_REALITY), parse("hysteria2://p@h2.example.com:443#H2"),
                parse(TROJAN_GRPC), parse("tuic://u@t.example.com:443#T"), parse(SHADOWSOCKS));
        List<ProxyConfig> kept = XrayConfig.supported(mixed);
        check(kept.size() == 3, "only the dialable endpoints are kept");
        check("vless".equals(kept.get(0).protocol), "order is preserved: vless first");
        check("trojan".equals(kept.get(1).protocol), "order is preserved: trojan second");
        check("ss".equals(kept.get(2).protocol), "order is preserved: ss third");
        check(XrayConfig.supported(null).isEmpty(), "a null list is empty, not a crash");
    }

    @Test public void normalisesTransportNames() {
        check("xhttp".equals(XrayConfig.network(parse(
                "vless://u@a.example:443?type=splithttp#S"))), "splithttp is read as xhttp");
        check("http".equals(XrayConfig.network(parse(
                "vless://u@a.example:443?type=h2#S"))), "h2 is read as http");
        check("kcp".equals(XrayConfig.network(parse(
                "vless://u@a.example:443?type=mkcp#S"))), "mkcp is read as kcp");
        check("tcp".equals(XrayConfig.network(parse(
                "vless://u@a.example:443?type=nonsense#S"))), "an unknown transport falls back to tcp");
        check("tls".equals(XrayConfig.security(parse(
                "vless://u@a.example:443?security=xtls#S"))), "a stale xtls entry is read as tls");
        check("none".equals(XrayConfig.security(parse(
                "vless://u@a.example:443#S"))), "a missing security value never invents encryption");
    }

    @Test public void survivesHostileTextFromThePools() {
        // Pool lines are untrusted and land directly in this document. A quote in a label or a
        // parameter must not be able to break out and rewrite the config.
        ProxyConfig nasty = parse("vless://11111111-2222-3333-4444-555555555555@evil.example:443"
                + "?security=tls&sni=%22%2C%22outbounds%22%3A%5B%5D%2C%22x%22%3A%22"
                + "&type=ws&path=%2F%22%5C#break%22out");
        String json = XrayConfig.build(nasty);
        check(json.contains("\\\""), "the injected quotes are escaped");
        check(countOccurrences(json, "\"outbounds\":") == 1, "only one outbounds key survives");
        check(json.endsWith("}"), "the document still closes cleanly");
        check(isBalanced(json), "braces and brackets stay balanced");
    }

    @Test public void neverEmitsAnOptionTheCoreHasRemoved() {
        // Caught by running the real core over configs built from real pool data, not by reading
        // documentation. The core refuses the whole config rather than ignoring the field.
        ProxyConfig insecure = parse("vless://11111111-2222-3333-4444-555555555555@i.example:443"
                + "?security=tls&type=ws&allowInsecure=1&sni=i.example#I");
        check(!XrayConfig.build(insecure).contains("allowInsecure"),
                "allowInsecure=1 in the source URI is dropped, not passed through");
        ProxyConfig alt = parse("trojan://p@i.example:443?security=tls&insecure=true&sni=i.example#I");
        check(!XrayConfig.build(alt).contains("nsecure"), "the insecure alias is dropped too");
    }

    @Test public void writesNoRuleThatNeedsTheGeoDatabases() {
        // The build strips those two files. A rule referencing them would fail at runtime.
        for (String uri : new String[] { VLESS_REALITY, VLESS_WS_TLS, TROJAN_GRPC, SHADOWSOCKS }) {
            String json = XrayConfig.build(parse(uri));
            check(!json.contains("geoip:"), "no geoip rule in " + uri.substring(0, 12));
            check(!json.contains("geosite:"), "no geosite rule in " + uri.substring(0, 12));
            check(isBalanced(json), "balanced document for " + uri.substring(0, 12));
        }
    }

    @Test public void fallsBackSensiblyWhenFieldsAreMissing() {
        ProxyConfig bare = parse("vless://11111111-2222-3333-4444-555555555555@bare.example:443"
                + "?security=tls&type=ws#Bare");
        String json = XrayConfig.build(bare);
        check(json.contains("\"serverName\":\"bare.example\""),
                "the address stands in for a missing sni");
        check(json.contains("\"path\":\"/\""), "a missing path becomes root");

        ProxyConfig grpcNoService = parse("vless://11111111-2222-3333-4444-555555555555"
                + "@g.example:443?security=tls&type=grpc&path=svc#G");
        check(XrayConfig.build(grpcNoService).contains("\"serviceName\":\"svc\""),
                "grpc falls back to the path when no service name is given");

        ProxyConfig rejectsPort = ProxyConfig.parse("vless://u@a.example:99999#X");
        check(rejectsPort == null || !XrayConfig.supports(rejectsPort),
                "an impossible port never reaches the core");
    }

    @Test public void rejectsAnImpossibleSocksPort() {
        boolean threw = false;
        try { XrayConfig.build(parse(VLESS_REALITY), 70000, "warning"); }
        catch (IllegalArgumentException expected) { threw = true; }
        check(threw, "a port outside the valid range is refused");
        check(XrayConfig.build(parse(VLESS_REALITY), 1080, "debug").contains("\"loglevel\":\"debug\""),
                "the log level is passed through");
        check(XrayConfig.build(parse(VLESS_REALITY), 1080, "shout").contains("\"loglevel\":\"warning\""),
                "an unknown log level falls back rather than reaching the core");
    }

    @Test public void dialsDirectlyUnlessGivenACarrier() {
        String json = XrayConfig.build(parse(VLESS_REALITY));
        check(!json.contains("\"dialerProxy\":\"carrier\""), "a direct config never names the carrier");
        check(!json.contains("\"tag\":\"carrier\""), "a direct config has no carrier outbound");
        check(json.contains("\"tag\":\"proxy\""), "the endpoint outbound is still there");
    }

    @Test public void splitsTheHelloOnTheDirectRouteOnly() {
        String direct = XrayConfig.build(parse(VLESS_REALITY));
        check(direct.contains("\"tag\":\"fragment\""), "the direct route declares the fragment outbound");
        check(direct.contains("\"dialerProxy\":\"fragment\""),
                "the endpoint socket is opened by the fragment outbound");
        check(direct.contains("\"packets\":\"tlshello\""), "it is the hello that gets split");
        check(direct.contains("\"TcpNoDelay\":true"),
                "without this the pieces can be coalesced back into one write");

        String chained = XrayConfig.build(parse(VLESS_REALITY), "127.0.0.1:1819");
        check(!chained.contains("\"tag\":\"fragment\""),
                "the chained route is already inside a tunnel and is left alone");
        check(chained.contains("\"dialerProxy\":\"carrier\""), "the chained route still uses the carrier");
    }

    @Test public void leavesAnUnsecuredEndpointUnfragmented() {
        // Nothing to split: there is no TLS hello on a plain connection, so the outbound would be
        // pure cost and one more thing that can go wrong.
        String json = XrayConfig.build(parse("vless://11111111-2222-3333-4444-555555555555@1.2.3.4:80"
                + "?type=ws&path=%2F#plain"));
        check(!json.contains("\"tag\":\"fragment\""), "no fragment outbound without TLS");
        check(!json.contains("dialerProxy"), "and nothing to dial through");
    }

    @Test public void keepsTheCoresOwnLookupsOutOfTheTunnelItIsStillBuilding() {
        // 🚨 The regression this exists to stop: with no rules, the core's DNS falls to the first
        // outbound, which is the endpoint we have not connected to yet. Every hostname endpoint
        // then times out and is recorded as dead while being perfectly healthy.
        String direct = XrayConfig.build(parse(VLESS_REALITY));
        check(direct.contains("\"port\":\"53\",\"outboundTag\":\"direct\""),
                "the core's own lookups go out on this network, not through the endpoint");
        check(direct.contains("\"tag\":\"direct\""), "the direct outbound is declared");

        String chained = XrayConfig.build(parse(VLESS_REALITY), "127.0.0.1:1819");
        check(chained.contains("\"port\":\"53\",\"outboundTag\":\"carrier\""),
                "chained, the lookups follow the same route the endpoint is dialled over");

        // The rule that makes the one above safe. Without it, port 53 from the phone's own apps
        // would match and leave the device unencrypted.
        int appRule = direct.indexOf("\"inboundTag\":[\"socks-in\"],\"outboundTag\":\"proxy\"");
        int dnsRule = direct.indexOf("\"port\":\"53\"");
        check(appRule >= 0, "everything from the phone is routed into the tunnel explicitly");
        check(appRule < dnsRule, "and it is matched before the port 53 rule, or app DNS would leak");
    }

    @Test public void resolvesOverTcpBecauseTheCarrierCannotCarryUdp() {
        // The carrier's SOCKS proxy has no UDP ASSOCIATE, so a UDP resolver would make every
        // hostname endpoint unresolvable on the one route that works from a filtered network.
        String json = XrayConfig.build(parse(VLESS_REALITY), "127.0.0.1:1819");
        check(json.contains("\"tcp://1.1.1.1\""), "the resolver is reached over TCP");
        check(!json.contains("\"servers\":[\"1.1.1.1\""), "no bare UDP resolver survives");
    }

    @Test public void dialsThroughTheCarrierWhenGivenOne() {
        String json = XrayConfig.build(parse(VLESS_REALITY), "127.0.0.1:1819");
        check(json.contains("\"dialerProxy\":\"carrier\""),
                "the endpoint socket is opened by the carrier outbound");
        check(json.contains("\"tag\":\"carrier\""), "the carrier outbound is declared");
        check(json.contains("\"protocol\":\"socks\",\"settings\":{\"servers\":[{\"address\":\"127.0.0.1\",\"port\":1819}]}"),
                "the carrier points at the tunnel already running on this device");
    }

    @Test public void chainingChangesNothingAboutTheEndpointItself() {
        // The whole value of chaining is that only the first hop moves. If the transport, the
        // TLS or the credentials differed between the two, the exit would differ too.
        String direct = XrayConfig.build(parse(VLESS_REALITY));
        String chained = XrayConfig.build(parse(VLESS_REALITY), "127.0.0.1:1819");
        check(chained.contains("\"realitySettings\""), "reality survives chaining");
        check(direct.contains("\"realitySettings\""), "reality is there to begin with");
        int cut = direct.indexOf(",\"sockopt\"");
        check(cut > 0 && chained.startsWith(direct.substring(0, cut)),
                "everything before the socket options is byte-identical");
    }

    @Test public void carriesEveryProtocolAndTransportThroughTheCarrier() {
        for (String uri : new String[] { VLESS_REALITY, VLESS_WS_TLS, TROJAN_GRPC, SHADOWSOCKS }) {
            String json = XrayConfig.build(parse(uri), "127.0.0.1:1819");
            check(json.contains("\"dialerProxy\":\"carrier\""),
                    "chaining applies to " + uri.substring(0, uri.indexOf(':')));
        }
    }

    @Test public void treatsAnUnusableCarrierAsNoCarrierAtAll() {
        // A malformed address means "dial directly", which works, rather than a thrown
        // exception, which would lose an endpoint over a typo.
        for (String bad : new String[] { null, "", "   ", "127.0.0.1", "127.0.0.1:", ":1819",
                                         "127.0.0.1:port", "127.0.0.1:0", "127.0.0.1:99999" }) {
            String json = XrayConfig.build(parse(VLESS_REALITY), bad);
            check(!json.contains("\"dialerProxy\":\"carrier\""),
                    "an unusable carrier dials direct: " + bad);
            check(!json.contains("\"tag\":\"carrier\""),
                    "and declares no carrier outbound: " + bad);
        }
    }

    /** CI runs this; it fails the build if any check above failed. */
    @Test public void everyCheckPasses() {
        int before = failures;
        runAllChecks();
        assertEquals("Stealth config checks failed", before, failures);
        assertTrue("No checks ran", checks > 0);
    }

    void runAllChecks() {
        publishesSocksOnTheFixedLoopbackPort();
        carriesEveryRealityField();
        dropsFlowWhenThereIsNoTlsToCarryIt();
        mapsWebsocketAndTls();
        mapsTrojanOverGrpc();
        splitsTheShadowsocksCredential();
        refusesWhatTheCoreCannotDial();
        filtersAListWithoutReorderingIt();
        normalisesTransportNames();
        survivesHostileTextFromThePools();
        neverEmitsAnOptionTheCoreHasRemoved();
        writesNoRuleThatNeedsTheGeoDatabases();
        fallsBackSensiblyWhenFieldsAreMissing();
        rejectsAnImpossibleSocksPort();
        dialsDirectlyUnlessGivenACarrier();
        dialsThroughTheCarrierWhenGivenOne();
        chainingChangesNothingAboutTheEndpointItself();
        carriesEveryProtocolAndTransportThroughTheCarrier();
        treatsAnUnusableCarrierAsNoCarrierAtAll();
    }

    // --- helpers -------------------------------------------------------------------------------

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) { count++; at = haystack.indexOf(needle, at + needle.length()); }
        return count;
    }

    /** Cheap structural check that ignores anything inside a string literal. */
    private static boolean isBalanced(String json) {
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            switch (c) {
                case '"': inString = true; break;
                case '{': braces++; break;
                case '}': braces--; break;
                case '[': brackets++; break;
                case ']': brackets--; break;
                default: break;
            }
            if (braces < 0 || brackets < 0) return false;
        }
        return braces == 0 && brackets == 0 && !inString;
    }


    /**
     * Standalone entry point, for running these checks without an Android toolchain around.
     * The exit code lives here and not in runAllChecks, because a System.exit inside a unit
     * test kills the test JVM and turns a clear failure report into an opaque crash.
     */
    public static void main(String[] args) {
        new XrayConfigTest().runAllChecks();
        System.out.println("XrayConfig: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
