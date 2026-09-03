/**
 * pool.ctrjmc.xyz — the endpoint list Panther's Stealth engine fetches.
 *
 * 🔑 No KV and no cron trigger, on purpose. The account this runs on throws KV errors daily, and
 * a store that fails daily is worse than no store at all for something the app depends on to
 * connect. Cloudflare's own edge cache does the same job here with nothing to bind, nothing to
 * provision and nothing to go wrong: the merged list is cached for six hours, so the upstream
 * pools are fetched a handful of times a day no matter how many phones ask.
 *
 * What the phone gains over fetching the six pools itself:
 *   - one request through the tunnel instead of six, which is most of the connect time
 *   - duplicates removed once here rather than on every phone
 *   - a pool that has gone down costs nothing, because it is dropped before the phone sees it
 *   - the list is the owner's to change without shipping an app update
 *
 * Endpoints:
 *   GET /            the merged list, text/plain, one URI per line
 *   GET /?protocols=vless,trojan,ss   only those protocols
 *   GET /?limit=500  at most that many
 *   GET /health      per-source status as JSON, for diagnosing from a phone
 */

import { mergePools, DIALABLE } from './pool-merge.mjs';

/** Where the endpoints come from. Same set the app falls back to if this Worker is unreachable. */
const SOURCES = [
  'https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt',
  'https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/protocols/hysteria2.txt',
  'https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/vless_sub.txt',
  'https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/trojan_sub.txt',
  'https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/main/config/countries/de.txt',
  'https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/main/config/countries/nl.txt',
];

const CACHE_SECONDS = 6 * 60 * 60;
const SOURCE_TIMEOUT_MS = 8000;
const MAX_LIMIT = 4000;

/**
 * Fetches one source. Never throws: a pool that is down, slow or serving junk must cost its own
 * entries and nothing else, because the phone asking is usually on a network where being handed
 * an error instead of a shorter list means not connecting at all.
 */
async function fetchSource(url) {
  const started = Date.now();
  try {
    const response = await fetch(url, {
      signal: AbortSignal.timeout(SOURCE_TIMEOUT_MS),
      cf: { cacheTtl: CACHE_SECONDS, cacheEverything: true },
      headers: { 'User-Agent': 'Panther-Pool/1.0' },
    });
    if (!response.ok) {
      return { url, ok: false, status: response.status, ms: Date.now() - started, body: '' };
    }
    const body = await response.text();
    return { url, ok: true, status: response.status, ms: Date.now() - started, body };
  } catch (error) {
    return { url, ok: false, status: 0, ms: Date.now() - started, body: '',
             error: String(error && error.name || error) };
  }
}

function parseProtocols(value) {
  if (!value) return null;
  if (value === 'dialable') return DIALABLE;
  const wanted = value.split(',').map((p) => p.trim().toLowerCase()).filter(Boolean);
  return wanted.length ? wanted : null;
}

async function buildList(url) {
  const results = await Promise.all(SOURCES.map(fetchSource));
  const protocols = parseProtocols(url.searchParams.get('protocols'));
  const requested = parseInt(url.searchParams.get('limit') || '', 10);
  const limit = Number.isFinite(requested) && requested > 0
    ? Math.min(requested, MAX_LIMIT)
    : MAX_LIMIT;
  const endpoints = mergePools(results.map((r) => r.body), { protocols, limit });
  return { results, endpoints };
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method not allowed\n', { status: 405 });
    }

    if (url.pathname === '/health') {
      const { results, endpoints } = await buildList(url);
      const body = {
        endpoints: endpoints.length,
        generated: new Date().toISOString(),
        sources: results.map((r) => ({
          url: r.url, ok: r.ok, status: r.status, ms: r.ms, bytes: r.body.length,
          ...(r.error ? { error: r.error } : {}),
        })),
      };
      return new Response(JSON.stringify(body, null, 2), {
        headers: { 'content-type': 'application/json; charset=utf-8',
                   'cache-control': 'no-store' },
      });
    }

    if (url.pathname !== '/') return new Response('Not found\n', { status: 404 });

    // The cache key carries the query string, so ?protocols=... and ?limit=... each get their
    // own cached answer instead of one variant poisoning the others.
    const cache = caches.default;
    const cacheKey = new Request(url.toString(), { method: 'GET' });
    const cached = await cache.match(cacheKey);
    if (cached) return cached;

    const { results, endpoints } = await buildList(url);

    // Serving an empty list would tell the phone its pool is empty and send it into a refresh
    // loop. If every source failed, say so with a 503 and let it keep what it saved last time.
    if (endpoints.length === 0) {
      return new Response('No endpoints available\n', {
        status: 503,
        headers: { 'cache-control': 'no-store' },
      });
    }

    const live = results.filter((r) => r.ok).length;
    const body = `# Panther endpoint pool\n`
      + `# generated ${new Date().toISOString()}\n`
      + `# ${endpoints.length} endpoints from ${live}/${results.length} sources\n`
      + endpoints.join('\n') + '\n';

    const response = new Response(body, {
      headers: {
        'content-type': 'text/plain; charset=utf-8',
        'cache-control': `public, max-age=${CACHE_SECONDS}`,
        'x-endpoint-count': String(endpoints.length),
        'x-sources-live': `${live}/${results.length}`,
      },
    });
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  },
};
