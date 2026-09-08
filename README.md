# Panther VPN

An open-source Android VPN. No account, no subscription, nothing to paste in from somewhere else. Install it, hit Connect, done.

[Releases](https://github.com/Amirmahdavi2022/Panther-VPN/releases) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md) · [Licences](NOTICE.md)

## What it actually does

Most of the free VPN apps on GitHub are config managers. Nice interface, and then you go hunting for a working server to paste in. Panther brings the network with it.

| Engine | Where you come out | Speed | Needs your own server? |
|---|---|---|---|
| **Turbo** | Nearest Cloudflare datacenter | Fastest | No |
| **Global** | Another country | Slower, two hops | No |
| **Prowl** | Wherever the server it found sits | Fast, single hop | No |

The three sit as cards above the connect button and you tap to switch. Turbo is the default, and none of them ask you for anything.

**Turbo** hides your IP and your ISP but doesn't move you. The network behind it deliberately keeps you roughly where you are, so sites still see the country you're in. That's the design, not a fault.

**Global** is the one that moves you. Instead of dialling straight out it runs inside the Turbo tunnel, and that's the bit that lets it come up on filtered networks at all. Pick an exit country or leave it automatic.

**Prowl** is for when the other two are being blocked. It keeps a list of servers, tests them on your own phone, and dials whichever actually carries traffic. Single hop, so it's quick once it's up.

## How Prowl finds a server

Worth explaining, because it's the part that's actually different.

It pulls a few public config lists, parses out the servers it can dial, and scores each one from what it has seen on *your* phone — did it connect, how fast, how recently. That score is kept on disk, so the next connect starts from something it already knows instead of from nothing. If it can't reach the lists at all it uses what it saved last time, which is the whole point on a network that's blocking things.

It also doesn't try servers one at a time. It brings up a batch on separate local ports, probes them in parallel, and keeps whichever answered. 48 attempts one after another take about five minutes; the same 48 in one round is done in eight seconds. That's the difference between actually searching the list and sampling one percent of it.

Each server gets tried three ways, and it remembers which one worked on this network:

- **Direct** — straight out from your connection. Fastest when it's allowed.
- **Shaped** — same thing, but the TLS handshake is broken into small randomly sized pieces, so equipment that matches on a single packet has nothing to match.
- **Through Turbo** — dials out from inside the Turbo tunnel. Slower, works when the other two don't.

If your network changes and the remembered route stops making sense, **Settings → Reset Prowl route** forgets the lot and it works it out again.

Prowl has no country picker on purpose. It comes out wherever the server it managed to dial happens to be, so a country dropdown would be filtering a list that keeps changing underneath you. The location card tells you where you actually ended up.

## Picking a country

That's Global's. Arm it and a card shows up at the top for the exit country. Automatic is the default and usually the quickest, so only pick by hand when you need somewhere specific.

Every time you connect, the app remembers which country the tunnel came out in. Open the list next time and that one says it connected before, the rest say untried. After a few connections it stops being a guess.

It won't tell you more than that. Whether some site works from a given exit changes week to week and differs between two accounts on the same exit, and a stale green tick is worse than no tick.

## The location card

Once you're connected the home screen shows where you came out — country, city, public IP, and who owns that IP.

It works that out by asking a public service, and it asks *through the tunnel*, over the same local SOCKS the engine opened. So the service only ever sees the exit address, never your phone. No API keys, nothing stored. Tap the card to check again.

Four services, tried in order until one answers:

1. `www.cloudflare.com/cdn-cgi/trace`
2. `speed.cloudflare.com/meta`
3. `ipwho.is`
4. `ipapi.co`

`/cdn-cgi/trace` is first because `/meta` answers 403 through a lot of public exits, and burning the first attempt on something that reliably fails is silly. If all four fall over it says location unavailable rather than inventing something.

On Global there's a second answer that comes from the engine itself, naming the country it connected through. When that disagrees with the IP lookup the card shows both instead of picking a winner — a location that's quietly wrong is worse than one that's visibly worth a second look.

## Things worth knowing

- **Turbo doesn't change your country.** That's how the underlying network is built. Want a different country, use Global or Prowl.
- **Global is slower.** Two hops. That's what coming out somewhere else costs.
- **The first Global connection takes a while.** A minute or two while the carrier comes up and it finds a route. Quicker after that.
- **Prowl depends on public servers.** They're free and they die constantly. It's built around that — scores them, benches the dead ones, refreshes the list — but some days the list is just bad.
- **Whoever runs the exit can see traffic leaving it.** Same as any VPN. Use HTTPS.
- **This isn't anonymity.** It changes where your traffic looks like it's coming from. It doesn't make you untraceable and it's not a stand-in for Tor if you actually need Tor.
- **Android only.** The Windows client went away in 2.0.0.

## Building from source

JDK 17, the Android SDK, and NDK `27.2.12479018`.

```bash
git clone --recurse-submodules https://github.com/Amirmahdavi2022/Panther-VPN.git
cd Panther-VPN
npm run fetch:android
cd android && ./gradlew assembleRelease
```

Don't skip `--recurse-submodules`. The OpenVPN engine is a pinned submodule and the build falls over straight away without it.

`npm run fetch:android` pulls the native cores and checks their SHA-256. The Global engine comes from there too, as an official prebuilt Android library. Nothing here compiles Go and there's no gomobile step. That library isn't signed by its publisher, so the build pins it by hash and stops if the hash moves.

Every native core is taken from its own release and checked against a published SHA-256 before it goes near the APK. CI also fails the build if any core is missing from `jniLibs`, because a green build shipped without one once and that wasn't fun.

### Tests

```bash
cd android && ./gradlew testReleaseUnitTest
```

The parsing and the decision logic are deliberately plain Java with no Android types in them, which is what makes them testable for real. They're also exactly the bits that are miserable to debug from a screenshot when they go wrong.

## Staying current

- **Native cores** are watched by a weekly job that opens a PR whenever upstream ships a release. It doesn't merge — native code landing on everyone's phone should get built and looked at first.
- **The app** checks GitHub releases and can install an update itself. It verifies the SHA-256 and checks the new APK is signed with the same certificate as the one installed.
- **The licence file** is compared in CI against the core version the build actually pins. It went stale once without anyone noticing, so now the build fails instead.

## Licence

Panther is AGPL-3.0 and is built on other people's work. Full credits and licences are in [NOTICE.md](NOTICE.md).

Panther isn't affiliated with or endorsed by any of those projects, so please don't send them Panther bugs.

## One small ask

If Panther worked for you, star the repo — it genuinely helps it reach people who need it. Found a bug or have an idea, I'd like to hear it. Thanks for reading this far.
