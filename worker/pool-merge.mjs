/**
 * The endpoint pool merger.
 *
 * Kept in its own file, free of any Worker API, so it runs under plain Node and is tested
 * against real pool documents rather than eyeballed. The Worker is the thin part; this is
 * the part that can be wrong.
 */

/** Protocols the phone's core can actually dial. Used only when a caller asks for a subset. */
export const DIALABLE = ['vless', 'trojan', 'ss'];

/**
 * Pools are served three ways: plain lines, one big base64 blob, or base64 with whitespace.
 * Decoding is attempted only when the body does not already look like a list of URIs, so a
 * plain document that happens to be valid base64 is never mangled.
 */
export function decodeDocument(body) {
  if (!body) return '';
  if (/(^|\n)\s*(vless|vmess|trojan|ss|hysteria2?|hy2|tuic):\/\//i.test(body)) return body;
  const compact = body.replace(/\s+/g, '');
  if (compact.length < 16 || !/^[A-Za-z0-9+/=_-]+$/.test(compact)) return body;
  try {
    const decoded = Buffer.from(compact.replace(/-/g, '+').replace(/_/g, '/'), 'base64')
      .toString('utf8');
    return /:\/\//.test(decoded) ? decoded : body;
  } catch {
    return body;
  }
}

/**
 * The identity of an endpoint, for removing duplicates.
 *
 * Deliberately protocol + host + port + credential, matching what the app already does, so the
 * two agree on what "the same server" means. The label after # is ignored: the same server
 * appears in five pools under five different advertising names.
 */
export function endpointKey(uri) {
  const withoutLabel = uri.split('#')[0];
  const scheme = withoutLabel.slice(0, withoutLabel.indexOf('://')).toLowerCase();
  const rest = withoutLabel.slice(withoutLabel.indexOf('://') + 3);
  const at = rest.lastIndexOf('@');
  const credential = at > 0 ? rest.slice(0, at) : '';
  const hostPart = (at > 0 ? rest.slice(at + 1) : rest).split('?')[0].split('/')[0];
  return `${scheme === 'hy2' ? 'hysteria2' : scheme}|${hostPart.toLowerCase()}|${credential}`;
}

/** Every proxy URI in a document, in the order it appeared, junk and comments skipped. */
export function extractEndpoints(document) {
  const found = [];
  for (const rawLine of decodeDocument(document).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#') || line.startsWith('//')) continue;
    if (!/^(vless|vmess|trojan|ss|hysteria2|hy2|tuic):\/\/.+/i.test(line)) continue;
    if (line.length > 2048) continue;
    found.push(line);
  }
  return found;
}

/**
 * Merges every fetched document into one deduplicated list.
 *
 * Interleaves the sources rather than concatenating them: a phone that only reads the first
 * N lines would otherwise get N endpoints from one pool instead of a spread across all of
 * them, and one bad pool would then decide the whole connection.
 *
 * @param documents  the raw bodies that were fetched, in source order
 * @param options    protocols: keep only these; limit: stop after this many endpoints
 */
export function mergePools(documents, options = {}) {
  const protocols = options.protocols && options.protocols.length
    ? new Set(options.protocols.map((p) => p.toLowerCase()))
    : null;
  const limit = options.limit || 4000;

  const perSource = documents.map((doc) => extractEndpoints(doc));
  const seen = new Set();
  const merged = [];
  const depth = Math.max(0, ...perSource.map((list) => list.length));

  for (let i = 0; i < depth && merged.length < limit; i++) {
    for (const list of perSource) {
      if (merged.length >= limit) break;
      if (i >= list.length) continue;
      const uri = list[i];
      const scheme = uri.slice(0, uri.indexOf('://')).toLowerCase();
      const normalised = scheme === 'hy2' ? 'hysteria2' : scheme;
      if (protocols && !protocols.has(normalised)) continue;
      const key = endpointKey(uri);
      if (seen.has(key)) continue;
      seen.add(key);
      merged.push(uri);
    }
  }
  return merged;
}
