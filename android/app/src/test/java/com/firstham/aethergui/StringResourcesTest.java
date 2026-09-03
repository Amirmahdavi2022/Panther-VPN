package com.firstham.aethergui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Guards the rules aapt enforces on string resources but XML does not.
 *
 * <p>🚨 An unescaped apostrophe inside a {@code <string>} is perfectly valid XML and illegal to
 * aapt. It therefore passes every editor, every XML validator and every review, and then fails the
 * build minutes in at {@code mergeResources} with a message about unicode escape sequences that
 * names neither the character nor the string it is in. That cost a whole build cycle once.
 *
 * <p>This check was first written as a PowerShell step in the workflow, which was a mistake worth
 * recording: nothing in the container can run PowerShell, so the guard against a quoting error
 * shipped unrun and failed on a quoting error of its own. In Java it runs in the same suite as
 * everything else, locally and in CI, before anything expensive starts.
 */
public final class StringResourcesTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAILED: " + what);
        }
    }

    /**
     * Finds the resource directory whether the tests are run by Gradle from the module directory
     * or by hand from the repository root.
     *
     * <p>Returns null only when neither exists, and the caller fails on that rather than passing
     * quietly - a guard that silently checks nothing is worse than no guard.
     */
    private static File resourceDirectory() {
        String[] candidates = {
                "src/main/res",
                "android/app/src/main/res",
                "../app/src/main/res",
        };
        for (String candidate : candidates) {
            File directory = new File(candidate);
            if (directory.isDirectory()) return directory;
        }
        return null;
    }

    private static List<File> stringFiles(File directory) {
        List<File> found = new ArrayList<>();
        File[] children = directory.listFiles();
        if (children == null) return found;
        for (File child : children) {
            if (child.isDirectory()) found.addAll(stringFiles(child));
            else if (child.getName().equals("strings.xml")) found.add(child);
        }
        return found;
    }

    /** The character index of the first quote aapt would reject, or -1 if there is none. */
    static int firstUnescapedQuote(String text) {
        if (text == null) return -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\'' && c != '"') continue;
            if (i == 0 || text.charAt(i - 1) != '\\') return i;
        }
        return -1;
    }

    @Test public void findsTheQuoteAapWouldReject() {
        check(firstUnescapedQuote("the server's own") == 10, "an apostrophe is found");
        check(firstUnescapedQuote("the server\\'s own") == -1, "an escaped apostrophe is fine");
        check(firstUnescapedQuote("say \"hello\"") == 4, "a double quote is found");
        check(firstUnescapedQuote("say \\\"hello\\\"") == -1, "an escaped double quote is fine");
        check(firstUnescapedQuote("nothing here at all") == -1, "clean text passes");
        check(firstUnescapedQuote("'") == 0, "a leading quote has nothing before it to escape it");
        check(firstUnescapedQuote(null) == -1, "an empty string is not an error");
    }

    @Test public void everyStringResourceSurvivesAapt() throws Exception {
        File directory = resourceDirectory();
        check(directory != null, "the resource directory was found");
        if (directory == null) return;

        List<File> files = stringFiles(directory);
        check(!files.isEmpty(), "at least one strings.xml was found");

        for (File file : files) {
            Document document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(file);
            NodeList strings = document.getElementsByTagName("string");
            for (int i = 0; i < strings.getLength(); i++) {
                Element element = (Element) strings.item(i);
                String name = element.getAttribute("name");
                String text = element.getTextContent();
                int at = firstUnescapedQuote(text);
                check(at < 0, file.getName() + " / " + name + ": unescaped "
                        + (at < 0 ? "" : String.valueOf(text.charAt(at)))
                        + " at character " + at + " - put a backslash in front of it");
            }
        }
    }

    @Test public void theVersionChipIsNotWrittenByHand() throws Exception {
        // app_version is generated from versionName by a resValue in build.gradle. A hand-written
        // copy here would both collide with it and be free to drift again, which is exactly how
        // the chip came to read v2.0.0 for sixteen releases.
        File directory = resourceDirectory();
        if (directory == null) return;
        for (File file : stringFiles(directory)) {
            Document document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(file);
            NodeList strings = document.getElementsByTagName("string");
            for (int i = 0; i < strings.getLength(); i++) {
                Element element = (Element) strings.item(i);
                check(!"app_version".equals(element.getAttribute("name")),
                        "app_version is generated, not declared in " + file.getName());
            }
        }
    }

    /** CI runs this; it fails the build if any check above failed. */
    @Test public void everyCheckPasses() throws Exception {
        runAllChecks();
        assertTrue("string resource checks failed: " + failures, failures == 0);
    }

    void runAllChecks() throws Exception {
        findsTheQuoteAapWouldReject();
        everyStringResourceSurvivesAapt();
        theVersionChipIsNotWrittenByHand();
    }

    /** Standalone entry point, for running these checks without an Android toolchain around. */
    public static void main(String[] args) throws Exception {
        new StringResourcesTest().runAllChecks();
        System.out.println("StringResources: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
