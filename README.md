# Panther VPN

An open-source Android VPN client. No account, no subscription, no config to paste in from somewhere. Install it, hit Connect, done.

[Releases](https://github.com/Amirmahdavi2022/Panther-VPN/releases) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md) · [Licences](NOTICE.md)

## What it actually does

Most of the free VPN apps you'll find on GitHub are config managers. They hand you a nice interface and then you go hunting for a working server to paste in. Panther brings the network with it.

| Engine | Where you come out | Speed | Needs your own server? |
|---|---|---|---|
| **Turbo** | Nearest Cloudflare datacenter | Fastest | No |
| **Global** | Another country | Slower, two hops | No |
| **Stealth** | Wherever the server is | Fast, single hop | No |

The three sit as cards above the connect button and you switch between them. Turbo is the default and none of them ask you for anything.

Turbo hides your IP and your ISP but doesn't move you, because the network behind it is built to preserve your rough location on purpose. Sites still see the country you're actually in. That's not a bug, it's the design.

Global is the one that moves you. Instead of dialling straight out, it runs inside the Turbo tunnel, which is what lets it come up on filtered networks at all. Pick an exit country or leave it automatic.

Stealth is the hardest one to block. It keeps a list of servers, tests them on your own phone, dials whichever actually works, and moves to another the moment one stops. Single hop, so it's fast. If a network blocks those servers outright it opens the connection through Turbo instead, so they stay reachable either way.

## Picking a country

Arm Global or Stealth and a card appears at the top of the screen for the exit country. Automatic is the default and usually the fastest, so pick manually only when you actually need to.

They get their countries from different places, which is why the two lists aren't the same. Global asks its own engine which regions it knows about. Stealth can only offer a country it has measured real servers in, so its list is shorter, and every country on it was counted before it went in. A country offering three servers is a country that fails right after you've chosen it.

On Stealth, changing country doesn't drop you. The new server is proved on a side port next to the live tunnel first, and only takes over once it has answered. If nothing in that country answers, you stay on the connection you had.

Small thing that makes the list useful: every time you connect, the app remembers which country the tunnel came out in. Next time you open the list that country says it connected before, and the rest say untried. After a few connections the list stops being a guess.

It doesn't claim anything beyond that. Whether some site or service works from a given exit is an answer that changes week to week and differs between two accounts on the same exit. A stale green tick is worse than none, so that part's on you.

## The location card

Once you're connected the home screen tells you where you actually came out. Country, city, public IP, and who owns that IP.

It works this out by asking a public service, and it asks through the tunnel, over the same local SOCKS the engine opened. So the service only ever sees the exit address, never your phone. No API keys anywhere, nothing stored. Tap the card to check again.

On Global there's a second answer that comes from the engine itself and names the country it connected through. When that disagrees with the IP lookup, the card shows both rather than declaring a winner. A location that's quietly wrong is worse than one that's visibly worth a second look.

Four services in the list, tried in order until one answers:

1. `speed.cloudflare.com/meta`
2. `www.cloudflare.com/cdn-cgi/trace`
3. `ipwho.is`
4. `ipapi.co`

Cloudflare goes first because the core exits through Cloudflare's network anyway, so that one can never be the thing holding us up. The next three are for when Cloudflare isn't reachable. If all four fall over it says location unavailable rather than making something up.

## Things worth knowing

- **Turbo doesn't change your country.** Not a bug, that's how the underlying network is built. Want a different country, use Global or Stealth.
- **Global is slower.** Two hops. That's the price of coming out somewhere else.
- **The first Global connection takes a while.** A minute or two for the carrier to come up and find a route. It's quicker after that.
- **Whoever runs the exit can see the traffic leaving it.** Same as any VPN. Use HTTPS.
- **This isn't anonymity.** It changes where your traffic appears to come from. It doesn't make you untraceable and it's not a substitute for Tor if you actually need Tor.
- **Android only.** The Windows client went away in 2.0.0.

## Building from source

You need JDK 17, the Android SDK, and NDK `27.2.12479018`.

```bash
git clone --recurse-submodules https://github.com/Amirmahdavi2022/Panther-VPN.git
cd Panther-VPN
npm run fetch:android
cd android && ./gradlew assembleRelease
```

Don't skip `--recurse-submodules`. The OpenVPN engine is a pinned submodule and the build falls over immediately without it.

`npm run fetch:android` pulls the native cores and checks their SHA-256. The Global engine comes from here too, shipped as an official prebuilt Android library. Nothing in this project compiles Go and there's no gomobile step. That library isn't signed by its publisher, so the build pins it by SHA-256 and stops there if the hash moves.

Every native core is taken from its own release and checked against a published SHA-256 before it goes anywhere near the APK. CI also fails the build if any core is missing from `jniLibs`, because a green build once shipped without one and that wasn't fun.

### Tests

```bash
cd android && ./gradlew testReleaseUnitTest
```

The parsing and the decision logic are deliberately plain Java with no Android types in them, which is what makes them testable for real. They're also exactly the parts that are miserable to debug from a screenshot when they go wrong.

## Staying current

- **Native cores** are watched by a weekly job that opens a PR whenever upstream ships a release. It doesn't merge, because native code that lands on everyone's phone should get built and looked at first.
- **The app itself** checks GitHub releases and can install an update on its own. It verifies the SHA-256 and checks the new APK is signed with the same certificate as the installed one.
- **The licence file** is compared in CI against the core version the build actually pins. It went stale once without anyone noticing, so now the build fails instead.

## Licence

Panther is AGPL-3.0 and is built on other people's work. Full credits and licences are in [NOTICE.md](NOTICE.md).

Panther isn't affiliated with or supported by any of those projects, so please don't send them Panther bugs.

## One small ask

If Panther worked for you, star the repo. It genuinely helps the project get seen by people who need it. Found a bug or have an idea, I'd like to hear it. Thanks for reading this far.
