# Panther VPN

Open source Android VPN client. No account, no subscription, nothing to paste in. Install it, hit
Connect, done.

[Releases](https://github.com/amirmahdavi2023/Panther-VPN/releases) · [Security policy](SECURITY.md) · [Contributing](CONTRIBUTING.md) · [Third-party notices](NOTICE.md)

---

## What it actually does

Most of the free VPN apps on GitHub are really just config managers. You get a nice UI and then
you still have to go hunt down a working server and paste it in yourself. Panther brings the
network with it.

| Mode | Network | Where you come out | Need your own server? |
|---|---|---|---|
| **Automatic** | Cloudflare WARP, through the Aether core | Nearest Cloudflare datacenter | No |
| **Custom** | Whatever endpoint you give it | Wherever you point it | Yes |

Automatic is the default and needs nothing from you.

---

## The location card

Once you're connected the home screen tells you where you actually came out. Country, city, the
public IP, and who owns it.

It figures that out by asking a public lookup service, and it asks **through the tunnel**, using
the SOCKS proxy the core is already running on. So the service only ever sees the exit address,
never your phone. There's no API key anywhere and nothing gets stored. Tap the card if you want
it to check again.

Four services are in the list and it walks down them until one answers:

1. `speed.cloudflare.com/meta`
2. `www.cloudflare.com/cdn-cgi/trace`
3. `ipwho.is`
4. `ipapi.co`

Cloudflare's first because the core exits through Cloudflare's own network anyway, so that one is
never going to rate limit us or block the request. The other three are there for when Cloudflare
itself can't be reached. If every single one of them is down you just get "Location unavailable"
instead of a wrong answer.

---

## Why there's no country picker

There used to be one. Up to 2.2.0 you could pick an exit country off a second network, relays from
[VPN Gate](https://www.vpngate.net/).

It got pulled in 2.3.0 and the honest reason is that the relays mostly just didn't connect. They're
public OpenVPN endpoints that everyone hammers, and they refuse the handshake way more often than
they finish it. Worst of all on exactly the networks where you'd want a VPN in the first place,
since OpenVPN is easy to fingerprint and gets filtered. A country list where half the entries fail
is worse than having no list at all, because every failure just looks like the app is broken.

Upgrading quietly resets any country you'd saved back to Automatic so nobody's left pointing at a
dead relay.

The relay code is still sitting in the tree, so if a relay source ever turns up that actually
works, the picker can come back.

---

## Stuff you should know

- **You can't pick your exit country.** Automatic runs on WARP, which is anycast, so where you
  come out follows your network's routing. No free network is going to hand you a country of your
  choice or a fixed IP. That needs a server you pay for, which is what Custom mode is there for.
- **Whoever runs the exit can see traffic leaving it.** Same as any VPN. Use HTTPS.
- **This isn't anonymity.** It changes where your traffic looks like it's coming from. It doesn't
  make you untraceable and it's no replacement for Tor if Tor is what you actually need.
- **Android only.** The Windows client got dropped back in 2.0.0.

---

## Building

You'll need JDK 17, the Android SDK, and NDK `27.2.12479018`.

```bash
git clone --recurse-submodules https://github.com/amirmahdavi2023/Panther-VPN.git
cd Panther-VPN
npm run fetch:android      # grabs the native cores and checks their SHA-256
cd android && ./gradlew assembleRelease
```

Don't skip `--recurse-submodules`. The OpenVPN engine is a pinned submodule and the build just
fails without it.

Every native core gets pulled from its upstream release and checked against a published SHA-256
before it goes in the APK. CI also fails the build if any core is missing from `jniLibs`, because
a green build shipped without one once and that's not something you want to repeat.

### Tests

```bash
cd android && ./gradlew testReleaseUnitTest
```

The parsing bits are plain Java with no Android types in them, so they're covered by real unit
tests. Those are the parts that break in ways that are miserable to debug off a screenshot.

---

## Keeping up to date

- **Native cores** get watched by a weekly job that opens a PR when upstream ships a release. It
  opens a PR instead of merging, because native code that lands on every user's phone should get
  a build and a look first.
- **The app** checks GitHub Releases and can install updates on its own. It verifies the SHA-256
  and checks the new APK is signed with the same certificate as the one already installed.
- **The notices file** is checked in CI against the core version the build actually pins. It went
  stale once and nobody noticed, so now the build fails instead.

---

## Credits and licence

Panther is AGPL-3.0 and it's built on other people's work:

- [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) for the WARP core
- [hoang-rio/vpnLib](https://github.com/hoang-rio/vpnLib) for the Android OpenVPN engine (GPL-3.0)
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) for the TUN bridge

Panther is a fork of [hamvex/AetherGUI](https://github.com/hamvex/AetherGUI) and isn't affiliated
with, endorsed by or supported by any project up there. Please don't send Panther bugs to them.
Full attribution is in [NOTICE.md](NOTICE.md).
