# Panther VPN

An open-source Android VPN client with a free network behind one connect button. No account, no
subscription, and nothing to paste in — you install it and press Connect.

[Releases](https://github.com/amirmahdavi2023/Panther-VPN/releases) · [Security policy](SECURITY.md) · [Contributing](CONTRIBUTING.md) · [Third-party notices](NOTICE.md)

---

## What it actually does

Most "free VPN" clients on GitHub are config managers: they give you a UI, and you still have to
find a working server and paste it in. Panther ships the network too.

| Mode | Network | Where the exit is | Needs a server of yours |
|---|---|---|---|
| **Automatic** | Cloudflare WARP, via the Aether core | Nearest Cloudflare datacenter | No |
| **Custom** | Your own endpoint | Wherever you point it | Yes |

**Automatic** is the default and needs nothing from you. WARP endpoints are anycast, so the exit
country follows your network's routing and cannot be chosen — that is a property of WARP, not a
limitation of this app. If you need a specific country, you need **Custom** and a server you rent.

---

## Why there is no country picker

There used to be one. Versions up to 2.2.0 offered a second network — relays from
[VPN Gate](https://www.vpngate.net/), a public volunteer directory — and let you pick an exit
country from it.

It was removed in 2.3.0, and the reason is worth stating plainly: **the relays mostly did not
connect.** They are public, heavily abused OpenVPN endpoints, and they refuse the handshake far
more often than they complete it — especially from the networks where a VPN is most needed,
because OpenVPN is easy to fingerprint and gets filtered by protocol. A country list where most
entries fail is worse than offering no list, because every failure looks like the app is broken.

Upgrading resets any saved country back to Automatic, once and silently, so nobody is left
pointed at a dead relay.

The relay code is still in the tree and the decision is reversible. If a relay source turns up
that is actually reliable, the picker comes back.

---

## Honest limitations

- **You cannot choose your exit country.** Automatic uses WARP, which is anycast — the exit
  follows your network's routing. No free network gives you a chosen country or a fixed IP; that
  needs a server you rent, which is what Custom mode is for.
- **The exit operator can see traffic leaving it**, exactly as with any VPN. Use HTTPS.
- **This is not anonymity.** It moves where your traffic appears to come from. It does not make
  you untraceable, and it is not a substitute for Tor if that is what you actually need.
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

The parsing and adapter layers are plain Java and covered by unit tests, because those are the
parts that fail in ways that are painful to debug from a user's screenshot.

---

## Staying current

- **Native cores** are watched by a weekly job that opens a pull request when upstream publishes
  a release. It opens a PR rather than merging: native code that ships to every user should get
  a build and a look first.
- **The app** checks GitHub Releases and can install updates itself, verifying both the SHA-256
  and that the new APK carries the same signing certificate as the installed one.

---

## Credits and licence

Panther is AGPL-3.0. It stands on work by others:

- [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) — the WARP networking core
- [hoang-rio/vpnLib](https://github.com/hoang-rio/vpnLib) — the Android OpenVPN engine (GPL-3.0)
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — the TUN bridge

Panther is an independent fork of [hamvex/AetherGUI](https://github.com/hamvex/AetherGUI) and is
not affiliated with, endorsed by, or supported by any project listed above. Please do not report
Panther issues to them. Full attribution is in [NOTICE.md](NOTICE.md).
