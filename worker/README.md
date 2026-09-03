# Panther endpoint pool worker

Serves the endpoint list the Stealth engine dials from, on `pool.ctrjmc.xyz`.

The phone used to fetch six GitHub files itself, through the tunnel, on every refresh. This
fetches them once at the edge, merges and deduplicates them, and hands back one list.

## Why there is no KV and no cron

Both were in the first design and both came out. KV throws errors daily on this account, and a
store that fails daily is worse than no store for something the app needs in order to connect.
Cloudflare's own edge cache does the same job with nothing to bind and nothing to provision: the
merged list is cached for six hours, so the upstream pools get fetched a handful of times a day
however many phones ask.

## Deploying

Workers & Pages -> Create -> Worker, then paste `pool-worker.mjs`, inline the two lines of
`pool-merge.mjs` it imports if the dashboard editor is single-file, and add a custom domain of
`pool.ctrjmc.xyz`. No bindings, no variables, no triggers.

## Endpoints

| request | what it gives back |
|---|---|
| `GET /` | every endpoint, one per line, ~350 KB |
| `GET /?protocols=dialable&limit=400` | what the app asks for, ~78 KB |
| `GET /?protocols=vless,trojan` | only those protocols |
| `GET /health` | per-source status as JSON |

`/health` is the one to open from a phone when something looks wrong: it names every source, its
HTTP status and how long it took, so a pool that has gone down is obvious.

## Behaviour worth knowing

- A source that is down, slow or serving junk costs its own entries and nothing else. The phone
  asking is usually on a network where an error instead of a shorter list means not connecting.
- If every source fails it answers 503 rather than an empty list, so the app keeps the pool it
  saved last time instead of being told its pool is empty.
- Sources are interleaved, not concatenated, so a phone reading only the first N lines gets a
  spread across all six rather than N entries from whichever pool happened to be first.
- Duplicates are matched on protocol + host + port + credential, ignoring the label after `#` —
  the same server shows up in five pools under five different advertising names.

## Tests

    node --test worker/pool-merge.test.mjs

15 checks against a real pool document, not a fixture someone typed.
