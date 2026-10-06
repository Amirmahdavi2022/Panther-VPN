package com.firstham.aethergui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The backup file for Turbo's identity: the core's own identity files, carried as they are.
 *
 * <p>Registering an identity is the one step a filtered network can block outright, and the core
 * only ever needs to do it once. Keeping a copy means clearing the app, reinstalling it or moving
 * to a new phone never has to register again.
 *
 * <p>The files are copied byte for byte, never parsed or rewritten, so the backup is exactly what
 * the core wrote and reads. Only the core's identity file names are accepted, so a restore can
 * never write anywhere else in the app's storage.
 *
 * <p>Format, plain text so it survives being sent through a messenger:
 * <pre>
 * panther-identity 1
 * aether.toml BASE64
 * aether-secondary.toml BASE64
 * </pre>
 *
 * <p>Free of Android imports on purpose, so it is tested on a plain JVM.
 */
final class IdentityBundle {

    static final String HEADER = "panther-identity 1";

    /**
     * The core's identity files, all next to {@code aether.toml}: the WireGuard identity, the
     * second one classic gool uses, the MASQUE one, and the one newer cores use for gool carried
     * over MASQUE. Anything else in the app's storage is not an identity and is never touched.
     */
    static final String[] FILE_NAMES = {
        "aether.toml", "aether-secondary.toml", "aether-masque.toml", "aether-masque-gool.toml",
    };

    /** Far above any real identity file (about 2 KB), small enough that junk is refused early. */
    static final int MAX_FILE_BYTES = 64 * 1024;
    static final int MAX_BUNDLE_CHARS = 512 * 1024;

    private IdentityBundle() { }

    static boolean isIdentityName(String name) {
        for (String known : FILE_NAMES) if (known.equals(name)) return true;
        return false;
    }

    /**
     * Whether {@code content} looks like an identity file the core wrote. Every identity it saves
     * carries both of these keys, so a stray file or the wrong document is refused.
     */
    static boolean looksLikeIdentity(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_FILE_BYTES) return false;
        String text = new String(content, StandardCharsets.UTF_8);
        return text.contains("device_id") && text.contains("wg_private_key");
    }

    /** Builds the backup text from the identity files present; null when none is usable. */
    static String encode(Map<String, byte[]> files) {
        if (files == null) return null;
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        int count = 0;
        for (String name : FILE_NAMES) {
            byte[] content = files.get(name);
            if (!looksLikeIdentity(content)) continue;
            out.append(name).append(' ')
                    .append(Base64.getEncoder().encodeToString(content)).append('\n');
            count++;
        }
        return count == 0 ? null : out.toString();
    }

    /**
     * Reads a backup back into file name to content.
     *
     * @throws IllegalArgumentException with a message fit for the user when the text is not a
     *     Panther identity backup, or holds nothing usable
     */
    static Map<String, byte[]> decode(String text) {
        if (text == null || text.length() > MAX_BUNDLE_CHARS) {
            throw new IllegalArgumentException("This file is not a Panther identity backup");
        }
        String[] lines = text.replace("\r", "").split("\n");
        int first = 0;
        while (first < lines.length && lines[first].trim().isEmpty()) first++;
        // A UTF-8 byte order mark from an editor must not make a good backup look foreign.
        if (first >= lines.length
                || !lines[first].replace(String.valueOf((char) 0xFEFF), "").trim().equals(HEADER)) {
            throw new IllegalArgumentException("This file is not a Panther identity backup");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int index = first + 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) continue;
            int space = line.indexOf(' ');
            if (space <= 0) throw new IllegalArgumentException("The backup is damaged");
            String name = line.substring(0, space);
            if (!isIdentityName(name)) throw new IllegalArgumentException("The backup is damaged");
            byte[] content;
            try {
                content = Base64.getDecoder().decode(line.substring(space + 1).trim());
            } catch (IllegalArgumentException damaged) {
                throw new IllegalArgumentException("The backup is damaged");
            }
            if (!looksLikeIdentity(content)) throw new IllegalArgumentException("The backup is damaged");
            files.put(name, content);
        }
        if (files.isEmpty()) throw new IllegalArgumentException("The backup holds no identity");
        return files;
    }
}
