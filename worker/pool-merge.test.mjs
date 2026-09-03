import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { mergePools, extractEndpoints, endpointKey, decodeDocument } from './pool-merge.mjs';

const REAL_POOL = readFileSync(new URL('./fixtures/real-pool.txt', import.meta.url), 'utf8');

test('reads endpoints out of a real pool document', () => {
  const found = extractEndpoints(REAL_POOL);
  assert.ok(found.length > 50, `expected a real number of endpoints, got ${found.length}`);
  for (const uri of found) {
    assert.match(uri, /^(vless|vmess|trojan|ss|hysteria2|hy2|tuic):\/\//i);
  }
});

test('skips the comment headers those pools ship', () => {
  const found = extractEndpoints('#profile-title: something\n#support-url: https://t.me/x\n');
  assert.equal(found.length, 0);
});

test('leaves a plain document alone even when it looks like base64', () => {
  const plain = 'vless://aaaa@example.com:443#one\nvless://bbbb@example.org:443#two';
  assert.equal(decodeDocument(plain), plain);
});

test('decodes a base64-wrapped subscription', () => {
  const plain = 'vless://aaaa@example.com:443#one\ntrojan://p@example.org:443#two';
  const wrapped = Buffer.from(plain, 'utf8').toString('base64');
  assert.equal(decodeDocument(wrapped).trim(), plain);
  assert.equal(extractEndpoints(wrapped).length, 2);
});

test('does not mangle a document that is not base64 at all', () => {
  const junk = 'this is not a config list at all, just words';
  assert.equal(decodeDocument(junk), junk);
});

test('the same server under two different advertised names is one endpoint', () => {
  const a = 'vless://uuid-1@1.2.3.4:443?type=tcp#Free%20Fast%20Germany';
  const b = 'vless://uuid-1@1.2.3.4:443?type=tcp#JOIN%20MY%20CHANNEL';
  assert.equal(endpointKey(a), endpointKey(b));
  assert.equal(mergePools([a, b]).length, 1);
});

test('different credentials on one host stay separate', () => {
  const a = 'vless://uuid-1@1.2.3.4:443#a';
  const b = 'vless://uuid-2@1.2.3.4:443#b';
  assert.notEqual(endpointKey(a), endpointKey(b));
  assert.equal(mergePools([a, b]).length, 2);
});

test('hy2 and hysteria2 are the same protocol', () => {
  assert.equal(endpointKey('hy2://p@1.2.3.4:443#a'), endpointKey('hysteria2://p@1.2.3.4:443#b'));
});

test('deduplicates a real pool against itself', () => {
  const once = mergePools([REAL_POOL]);
  const twice = mergePools([REAL_POOL, REAL_POOL]);
  assert.equal(once.length, twice.length, 'merging a pool with itself must add nothing');
});

test('interleaves sources so one pool cannot own the top of the list', () => {
  const a = ['vless://a1@1.1.1.1:443#a', 'vless://a2@1.1.1.2:443#a'].join('\n');
  const b = ['vless://b1@2.2.2.1:443#b', 'vless://b2@2.2.2.2:443#b'].join('\n');
  const merged = mergePools([a, b]);
  assert.ok(merged[0].includes('1.1.1.1'));
  assert.ok(merged[1].includes('2.2.2.1'), 'the second entry must come from the second source');
});

test('a dead source costs its own entries and nothing else', () => {
  const good = 'vless://a1@1.1.1.1:443#a\nvless://a2@1.1.1.2:443#a';
  assert.equal(mergePools(['', good, '']).length, 2);
});

test('keeps only the protocols asked for', () => {
  const merged = mergePools([REAL_POOL], { protocols: ['vless'] });
  assert.ok(merged.length > 0);
  for (const uri of merged) assert.ok(uri.toLowerCase().startsWith('vless://'));
});

test('honours the limit', () => {
  assert.equal(mergePools([REAL_POOL], { limit: 7 }).length, 7);
});

test('an empty fetch produces an empty list rather than throwing', () => {
  assert.equal(mergePools([]).length, 0);
  assert.equal(mergePools(['', '', '']).length, 0);
});

test('drops absurdly long lines that would only waste tunnel bandwidth', () => {
  const huge = 'vless://x@1.2.3.4:443#' + 'y'.repeat(4000);
  assert.equal(extractEndpoints(huge).length, 0);
});
