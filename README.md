# Panther VPN

Open source Android VPN client. No account, no subscription, nothing to paste in. Install it, hit
Connect, done.

[Releases](https://github.com/amirmahdavi2023/Panther-VPN/releases) · [Security policy](SECURITY.md) · [Contributing](CONTRIBUTING.md) · [Third-party notices](NOTICE.md)

---

## What it actually does

Most of the free VPN apps on GitHub are really just config managers. You get a nice UI and then
you still have to go hunt down a working server and paste it in yourself. Panther brings the
network with it.

| Mode | Where you come out | Speed | Need your own server? |
|---|---|---|---|
| **Turbo** | Nearest Cloudflare datacenter | Fastest | No |
| **Global** | Another country | Slower, two hops | No |
| **Custom** | Wherever you point it | Depends | Yes |

Turbo and Global sit as two cards above the connect button and you tap between
them. Turbo is the default. Neither needs anything from you.

Turbo runs on Cloudflare WARP through the Aether core. It hides your IP and your
ISP but it does not move you, because WARP is built to keep your rough location
rather than change it. Sites still see the country you're actually in.

Global is the one that moves you. It rides inside Turbo rather than dialling out
on its own, which is what makes it work on filtered networks. It picks the exit
country itself and then tells the app which one it landed on, so what you see on
the card is what the engine reports, not a guess.

---

## The location card

Once you're connected the home screen tells you where you actually came out. Country, city, the
public IP, and who owns it.

It figures that out by asking a public lookup service, and it asks **through the tunnel**, using
the SOCKS proxy the core is already running on. So the service only ever sees the exit address,
never your phone. There's no API key anywhere and nothing gets stored. Tap the card if you want
it to check again.

On Global there's a second answer too, straight from the engine, which names the country it
connected through. When that and the address lookup disagree, the card shows both rather than
picking a winner. A location that's quietly wrong is worse than one that visibly needs a second
look.

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

## About picking a country

Global chooses the exit itself right now. There's no country list in the UI yet.
The engine underneath does support asking for a specific country, so the list is
coming, but I'd rather ship the part that works than a dropdown full of entries
that half fail.

There used to be a picker up to 2.2.0, running on public relays from
[VPN Gate](https://www.vpngate.net/). It got pulled in 2.3.0 because the relays
mostly just didn't connect. They're public OpenVPN endpoints that everyone
hammers, and they refuse the handshake more often than they finish it. Worst of
all on exactly the networks where you'd want a VPN. A list where half the entries
fail is worse than no list, because every failure looks like the app is broken.

## Stuff you should know

- **Turbo does not change your country.** That's WARP working as designed, not a bug. If you
  want a different country, use Global.
- **Global is slower.** It's two hops. That's the cost of getting out.
- **Global's first connect is slow.** A minute or two while it brings up the carrier and finds a
  route. After that it's quicker.
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

`npm run fetch:android` also pulls the Global engine, which ships as an official prebuilt
Android library. Nothing here builds Go and there's no gomobile step. That library isn't signed by
its publisher, so the build pins it by SHA-256 and stops dead if the hash moves.

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
- [Psiphon-Labs/psiphon-tunnel-core](https://github.com/Psiphon-Labs/psiphon-tunnel-core) for the
  Global engine (GPL-3.0). Panther isn't affiliated with them, so please don't send them Panther
  bugs either.

Panther is a fork of [hamvex/AetherGUI](https://github.com/hamvex/AetherGUI) and isn't affiliated
with, endorsed by or supported by any project up there. Please don't send Panther bugs to them.
Full attribution is in [NOTICE.md](NOTICE.md).
