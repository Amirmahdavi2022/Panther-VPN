# Panther VPN

An open-source Android VPN client with two independent free networks behind one connect button.
No account, no subscription, and nothing to paste in — you install it and press Connect.

[Releases](https://github.com/amirmahdavi2023/Panther-VPN/releases) · [Security policy](SECURITY.md) · [Contributing](CONTRIBUTING.md) · [Third-party notices](NOTICE.md)

---

## What it actually does

Most "free VPN" clients on GitHub are config managers: they give you a UI, and you still have to
find a working server and paste it in. Panther ships the network too.

| Mode | Network | Where the exit is | Needs a server of yours |
|---|---|---|---|
| **Automatic** | Cloudflare WARP, via the Aether core | Nearest Cloudflare datacenter — fast, but not selectable | No |
| **Relay** | [VPN Gate](https://www.vpngate.net/), via OpenVPN | A country you pick, from dozens of volunteer relays | No |
| **Custom** | Your own endpoint | Wherever you point it | Yes |

**Automatic** is the default and the fastest path. WARP endpoints are anycast, so the exit country
follows your network's routing and cannot be chosen — that is a property of WARP, not a limitation
of this app.

**Relay** is where location selection lives. The relay list is fetched live from VPN Gate's public
directory every few hours, so new servers appear without an app update, and dead ones drop off.

---

## Choosing a location

The relay directory is public, volunteer-run, and changes constantly. Panther deals with that
rather than pretending otherwise:

- Relays are ranked by VPN Gate's own score, with throughput and latency breaking ties.
- Relays advertising no usable OpenVPN profile are dropped before you ever see them.
- Picking a country tries the best relay, then the next, up to three — volunteer machines go
  offline without warning, and one dead host should not read as "the country is broken".
- The last good directory is cached on disk. A failed refresh keeps the previous list instead of
  emptying it, so a bad network moment does not leave you with nothing to connect to.

---

## Honest limitations

- **Relay mode is not a fixed IP.** You choose a country; the address within it varies by relay
  and over time. A genuinely fixed IP requires a server you rent — no free network provides one.
- **Relay servers are run by volunteers.** Speed and uptime vary a lot, and the operator of an
  exit can see traffic leaving it, exactly as with any VPN. Use HTTPS.
- **Relay mode uses OpenVPN**, which is easy to fingerprint. In countries that filter by protocol
  it may not connect at all; Automatic mode is far more resilient there.
- **Android only.** The Windows client was removed in 2.0.0.

---

## Building

Requires JDK 17, the Android SDK, and NDK `27.2.12479018`.

```bash
git clone --recurse-submodules https://github.com/amirmahdavi2023/Panther-VPN.git
cd Panther-VPN
npm run fetch:android      # downloads and SHA-256 verifies the native cores
cd android && ./gradlew assembleRelease
```

`--recurse-submodules` matters: the OpenVPN engine is a pinned submodule, and the build fails
without it.

Every native core is fetched from its upstream release and checked against a published SHA-256
before it is packaged. CI additionally fails the build if any core is missing from `jniLibs` —
a green build once shipped without one, and that is not repeatable.

### Running the tests

```bash
cd android && ./gradlew testReleaseUnitTest
```

The directory parser and the profile adapter are plain Java and are covered by unit tests,
because those are the parts where a live volunteer feed can be malformed in ways that are
painful to debug from a user's screenshot.

---

## Staying current

- **Relay servers** need no updates. The directory is fetched at runtime.
- **Native cores** are watched by a weekly job that opens a pull request when upstream publishes
  a release. It opens a PR rather than merging: native code that ships to every user should get
  a build and a look first.
- **The app** checks GitHub Releases and can install updates itself, verifying both the SHA-256
  and that the new APK carries the same signing certificate as the installed one.

---

## Credits and licence

Panther is AGPL-3.0. It stands on work by others:

- [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) — the WARP networking core
- [VPN Gate](https://www.vpngate.net/) — the volunteer relay network and its public directory,
  an academic project of the University of Tsukuba, Japan
- [hoang-rio/vpnLib](https://github.com/hoang-rio/vpnLib) — the Android OpenVPN engine (GPL-3.0)
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — the TUN bridge

Panther is an independent fork of [hamvex/AetherGUI](https://github.com/hamvex/AetherGUI) and is
not affiliated with, endorsed by, or supported by any project listed above. Please do not report
Panther issues to them. Full attribution is in [NOTICE.md](NOTICE.md).
