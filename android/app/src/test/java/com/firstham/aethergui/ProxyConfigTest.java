package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Run with: java -cp <classes> com.firstham.aethergui.ProxyConfigTest */
public final class ProxyConfigTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    /**
     * CI runs this. Without it these checks only ever ran by hand, because a class with a main
     * method and no test annotation is invisible to the unit test task - which is exactly how a
     * suite quietly stops protecting anything.
     */
    @Test public void everyCheckPasses() {
        int before = failures;
        runAllChecks();
        assertEquals("config parser checks failed", before, failures);
        assertTrue("No checks ran", checks > 0);
    }

    static void runAllChecks() {
        parsesVlessReality();
        parsesHysteria2();
        parsesTuicAndHy2Alias();
        parsesTrojan();
        parsesShadowsocksBothShapes();
        handlesIpv6();
        rejectsGarbageInsteadOfThrowing();
        deduplicatesByServerIdentity();
        readsBase64Subscriptions();
        keepsSourceOrder();
        surviveAMixedRealisticDocument();

        System.out.println((failures == 0 ? "ALL PASS" : "FAILURES") + " — " + checks + " checks, " + failures + " failed");
    }

    private static void parsesVlessReality() {
        String uri = "vless://8f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f@104.18.20.30:443"
                + "?encryption=none&security=reality&sni=www.example.com&fp=chrome&type=tcp&flow=xtls-rprx-vision#Node%20One";
        ProxyConfig c = ProxyConfig.parse(uri);
        check(c != null, "a reality vless line parses");
        check("vless".equals(c.protocol), "protocol is vless");
        check("104.18.20.30".equals(c.host), "host is read");
        check(c.port == 443, "port is read");
        check(c.id.startsWith("8f1c2d3e"), "uuid is read");
        check("Node One".equals(c.label), "the label is percent-decoded");
        check(c.isReality(), "reality security is detected");
        check("tcp".equals(c.transport()), "transport is read");
        check("www.example.com".equals(c.params.get("sni")), "query parameters survive");
    }

    private static void parsesHysteria2() {
        ProxyConfig c = ProxyConfig.parse("hysteria2://somepassword@203.0.113.9:8443?sni=example.org&obfs=salamander#HY2");
        check(c != null && "hysteria2".equals(c.protocol), "hysteria2 parses");
        check("somepassword".equals(c.id), "the password is the credential");
        check(c.port == 8443, "hysteria2 port is read");
        check("salamander".equals(c.params.get("obfs")), "obfs parameter survives");
        check(!c.isReality(), "hysteria2 is not flagged as reality");
    }

    private static void parsesTuicAndHy2Alias() {
        ProxyConfig tuic = ProxyConfig.parse("tuic://uuid-here:password@198.51.100.7:2053?congestion_control=bbr#T");
        check(tuic != null && "tuic".equals(tuic.protocol), "tuic parses");
        check("bbr".equals(tuic.params.get("congestion_control")), "tuic parameters survive");

        // Pools publish the same protocol under two names; both must land on one identity or the
        // deduplicator will happily keep the same server twice.
        ProxyConfig alias = ProxyConfig.parse("hy2://pw@203.0.113.9:8443#same");
        ProxyConfig full = ProxyConfig.parse("hysteria2://pw@203.0.113.9:8443#same");
        check(alias != null && "hysteria2".equals(alias.protocol), "hy2 is normalised to hysteria2");
        check(alias.key().equals(full.key()), "hy2 and hysteria2 are the same server");
    }

    private static void parsesTrojan() {
        ProxyConfig c = ProxyConfig.parse("trojan://pass%40word@192.0.2.44:443?security=tls&sni=a.example#TJ");
        check(c != null && "trojan".equals(c.protocol), "trojan parses");
        check("pass@word".equals(c.id), "an encoded password is decoded");
    }

    private static void parsesShadowsocksBothShapes() {
        String credential = Base64.getEncoder().encodeToString("aes-256-gcm:secret".getBytes(StandardCharsets.UTF_8));
        ProxyConfig split = ProxyConfig.parse("ss://" + credential + "@198.51.100.20:8388#SS1");
        check(split != null && "ss".equals(split.protocol), "the split ss shape parses");
        check(split.id.equals("aes-256-gcm:secret"), "the credential is base64 decoded");
        check(split.port == 8388, "ss port is read");

        String whole = Base64.getEncoder().encodeToString(
                "aes-256-gcm:secret@198.51.100.21:8389".getBytes(StandardCharsets.UTF_8));
        ProxyConfig packed = ProxyConfig.parse("ss://" + whole + "#SS2");
        check(packed != null, "the fully base64 ss shape parses");
        check("198.51.100.21".equals(packed.host) && packed.port == 8389, "host and port survive the wrapper");
    }

    private static void handlesIpv6() {
        ProxyConfig c = ProxyConfig.parse("vless://uuid@[2001:db8::1]:2087?security=tls#v6");
        check(c != null, "a bracketed ipv6 literal parses");
        check("2001:db8::1".equals(c.host), "the brackets are stripped from the host");
        check(c.port == 2087, "the port after an ipv6 literal is read");
    }

    private static void rejectsGarbageInsteadOfThrowing() {
        String[] junk = {
                null, "", "   ", "# a comment", "// another",
                "not a uri at all", "vless://", "://nothing",
                "vless://uuid@host", "vless://uuid@host:notaport",
                "vless://@1.2.3.4:443", "vless://uuid@:443",
                "vless://uuid@1.2.3.4:99999", "vmess://anything",
                "ss://notbase64atall",
        };
        for (String line : junk) {
            check(ProxyConfig.parse(line) == null, "rejected without throwing: " + line);
        }
    }

    private static void deduplicatesByServerIdentity() {
        String document = String.join("\n",
                "vless://uuid1@1.2.3.4:443?security=reality#First",
                "vless://uuid1@1.2.3.4:443?security=reality#Renamed",   // same server, different label
                "vless://uuid1@1.2.3.4:443?security=reality&sni=x#Extra", // same server, extra param
                "vless://uuid1@1.2.3.4:8443?security=reality#OtherPort",
                "vless://uuid2@1.2.3.4:443?security=reality#OtherUuid");
        List<ProxyConfig> configs = ProxyConfig.parseDocument(document);
        check(configs.size() == 3, "cosmetic duplicates collapse but real differences do not");
        check("First".equals(configs.get(0).label), "the first occurrence is the one kept");

        // Case in the host must not create a second entry either.
        List<ProxyConfig> mixedCase = ProxyConfig.parseDocument(
                "trojan://p@Example.COM:443#a\ntrojan://p@example.com:443#b");
        check(mixedCase.size() == 1, "host case does not create a duplicate");
    }

    private static void readsBase64Subscriptions() {
        String plain = "vless://uuid@1.2.3.4:443#A\nhysteria2://pw@5.6.7.8:443#B";
        String wrapped = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        List<ProxyConfig> configs = ProxyConfig.parseDocument(wrapped);
        check(configs.size() == 2, "a base64 wrapped subscription is unwrapped");
        check("vless".equals(configs.get(0).protocol), "the first entry survives unwrapping");
        check("hysteria2".equals(configs.get(1).protocol), "the second entry survives unwrapping");

        // Line-wrapped base64 is common; whitespace must not defeat the unwrap.
        StringBuilder wrappedWithNewlines = new StringBuilder();
        for (int i = 0; i < wrapped.length(); i += 40) {
            wrappedWithNewlines.append(wrapped, i, Math.min(wrapped.length(), i + 40)).append('\n');
        }
        check(ProxyConfig.parseDocument(wrappedWithNewlines.toString()).size() == 2,
                "line-wrapped base64 is still unwrapped");
    }

    private static void keepsSourceOrder() {
        String document = "vless://a@1.1.1.1:443#1\nvless://b@2.2.2.2:443#2\nvless://c@3.3.3.3:443#3";
        List<ProxyConfig> configs = ProxyConfig.parseDocument(document);
        check(configs.size() == 3, "all three parse");
        check(configs.get(0).host.equals("1.1.1.1") && configs.get(2).host.equals("3.3.3.3"),
                "the source's own ordering is preserved");
    }

    /** The realistic case: a pool document with junk, blanks and comments mixed into it. */
    private static void surviveAMixedRealisticDocument() {
        String document = String.join("\n",
                "# updated 2026-09-02",
                "",
                "vless://uuid1@104.18.1.1:443?security=reality&sni=a.com&type=tcp#Reality%20DE",
                "garbage line that means nothing",
                "hysteria2://pw1@203.0.113.5:8443?obfs=salamander#HY2%20NL",
                "vmess://eyJhZGQiOiIxLjIuMy40In0=",
                "trojan://pw2@192.0.2.9:443?security=tls#TJ",
                "   ",
                "ss://" + Base64.getEncoder().encodeToString("aes-256-gcm:pw".getBytes(StandardCharsets.UTF_8)) + "@198.51.100.2:8388#SS",
                "vless://uuid1@104.18.1.1:443?security=reality&sni=a.com&type=tcp#duplicate");
        List<ProxyConfig> configs = ProxyConfig.parseDocument(document);
        check(configs.size() == 4, "four usable entries survive the mess, duplicate dropped");

        int reality = 0, hysteria = 0;
        for (ProxyConfig c : configs) {
            if (c.isReality()) reality++;
            if ("hysteria2".equals(c.protocol)) hysteria++;
        }
        check(reality == 1, "the reality entry is identifiable for scoring");
        check(hysteria == 1, "the hysteria2 entry is identifiable for scoring");
    }

    /**
     * Standalone entry point, for running these checks without an Android toolchain around.
     * The exit code lives here and not in runAllChecks, because a System.exit inside a unit
     * test kills the test JVM and turns a clear failure report into an opaque crash.
     */
    public static void main(String[] args) {
        runAllChecks();
        if (failures > 0) System.exit(1);
    }
}
