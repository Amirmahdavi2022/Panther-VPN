package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** Checks for how a probe response is turned into something the user is shown. */
public class ServiceProbeTest {

    private static Map<String, String> results(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    @Test public void normalAnswersAreOpen() {
        assertEquals(ServiceProbe.OPEN, ServiceProbe.classify(200));
        assertEquals(ServiceProbe.OPEN, ServiceProbe.classify(302));
        assertEquals(ServiceProbe.OPEN, ServiceProbe.classify(307));
        assertEquals(ServiceProbe.OPEN, ServiceProbe.classify(401));
    }

    /**
     * The distinction the whole feature rests on. A rate limit or a server fault means the request
     * arrived and was understood, so it is not a reason to go looking for another exit country.
     */
    @Test public void rateLimitsAndServerFaultsAreNotBlocks() {
        assertEquals(ServiceProbe.OPEN, ServiceProbe.classify(429));
        assertEquals(ServiceProbe.OPEN, ServiceProbe.classify(503));
    }

    @Test public void refusalsAreBlocks() {
        assertEquals(ServiceProbe.BLOCKED, ServiceProbe.classify(403));
        assertEquals(ServiceProbe.BLOCKED, ServiceProbe.classify(451));
    }

    @Test public void noAnswerIsUnreachable() {
        assertEquals(ServiceProbe.UNREACHABLE, ServiceProbe.classify(0));
        assertEquals(ServiceProbe.UNREACHABLE, ServiceProbe.classify(-1));
        assertEquals(ServiceProbe.UNREACHABLE, ServiceProbe.classify(999));
    }

    @Test public void targetsAreWellFormed() {
        assertEquals(3, ServiceProbe.targets().size());
        for (ServiceProbe.Target target : ServiceProbe.targets()) {
            assertTrue(target.host.contains("."));
            assertTrue(target.path.startsWith("/"));
            assertFalse(target.label.isEmpty());
        }
        assertEquals("ChatGPT", ServiceProbe.labelOf("chatgpt"));
        assertEquals("nope", ServiceProbe.labelOf("nope"));
    }

    @Test public void encodeDecodeRoundTrips() {
        Map<String, String> all = results(
                "chatgpt", ServiceProbe.OPEN,
                "claude", ServiceProbe.BLOCKED,
                "gemini", ServiceProbe.UNREACHABLE);
        assertEquals("chatgpt=open;claude=blocked;gemini=unreachable", ServiceProbe.encode(all));
        assertEquals(all, ServiceProbe.decode(ServiceProbe.encode(all)));
    }

    /** This string arrives over a broadcast and can come from an older build after an update. */
    @Test public void decodeToleratesRubbish() {
        assertEquals(0, ServiceProbe.decode(null).size());
        assertEquals(0, ServiceProbe.decode("").size());
        assertEquals(1, ServiceProbe.decode("chatgpt=weird;claude=open").size());
        assertEquals(1, ServiceProbe.decode("=open;claude=open").size());
        assertEquals(0, ServiceProbe.decode("a=unknown").size());
        assertEquals("", ServiceProbe.encode(null));
    }

    @Test public void oneWorkingServiceMakesTheExitWorthKeeping() {
        assertEquals(ServiceProbe.OPEN, ServiceProbe.verdict(results(
                "chatgpt", ServiceProbe.BLOCKED, "claude", ServiceProbe.OPEN)));
    }

    @Test public void verdictReportsTheWorstCaseOtherwise() {
        assertEquals(ServiceProbe.BLOCKED, ServiceProbe.verdict(results(
                "chatgpt", ServiceProbe.BLOCKED, "claude", ServiceProbe.BLOCKED)));
        assertEquals(ServiceProbe.UNREACHABLE, ServiceProbe.verdict(results(
                "chatgpt", ServiceProbe.UNREACHABLE)));
        assertEquals(ServiceProbe.UNKNOWN, ServiceProbe.verdict(new LinkedHashMap<>()));
        assertEquals(ServiceProbe.UNKNOWN, ServiceProbe.verdict(null));
    }

    @Test public void answeredMeansSomethingRepliedEitherWay() {
        assertTrue(ServiceProbe.anyAnswered(results("chatgpt", ServiceProbe.BLOCKED)));
        assertTrue(ServiceProbe.anyAnswered(results("chatgpt", ServiceProbe.OPEN)));
        assertFalse(ServiceProbe.anyAnswered(results("chatgpt", ServiceProbe.UNREACHABLE)));
        assertFalse(ServiceProbe.anyAnswered(null));
    }
}
