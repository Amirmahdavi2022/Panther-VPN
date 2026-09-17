# Panther VPN

Open-source VPN for Android. No account, no subscription, no configs to go find and paste in. You install it and tap Connect.

[Releases](https://github.com/Amirmahdavi2022/Panther-VPN/releases) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md) · [Licences](NOTICE.md)

## What it does

A lot of the free VPN apps on GitHub are basically config managers. The UI looks good, then you spend half an hour looking for a server that still works. Panther ships with its own ways to get out.

| Engine | Where you come out | Speed | Your own server? |
|---|---|---|---|
| **Turbo** | Closest Cloudflare datacenter | Fastest | No |
| **Global** | Another country | Slower (two hops) | No |
| **Prowl** | Wherever the server it found is | Fast (one hop) | No |

They're the three cards above the connect button. Tap one to switch. Turbo is the default.

**Turbo** hides your IP and your ISP, but you stay in roughly the same place. The network behind it is built to do that, so sites still see your own country.

**Global** is the one that actually moves you. It runs inside the Turbo tunnel instead of dialling out on its own, which is why it can come up on filtered networks at all. You can pick an exit country or leave it on automatic.

**Prowl** is for days when the other two get blocked. It keeps a list of servers, tests them on your phone and uses whichever one really carries traffic. One hop, so it's quick once it connects.

## How Prowl finds a server

This part is different from most apps so it's worth a few lines.

It grabs a few public config lists, keeps the servers it can dial, and scores each one based on what happened on *your* phone: did it connect, how fast was it, when did it last work. Scores are saved, so the next connect starts from what it already learned. If the lists can't be reached, it uses the copy from last time. On a network that blocks stuff that's the whole point.

It also doesn't go one server at a time. It opens a batch on separate local ports and tests them all at once. 48 tries in a row take around five minutes, in one batch it's about eight seconds. So it actually gets through the list.

Every server gets tried three ways and the app remembers which one worked on your network:

- **Direct**: straight out from your connection. Fastest if your network allows it.
- **Shaped**: same, but the TLS handshake is sent in small random pieces so filters that look for one packet don't find it.
- **Through Turbo**: goes out from inside the Turbo tunnel. Slower, but works when the other two don't.

If you switch networks and the saved route stops working, **Settings → Reset Prowl route** clears it and it figures things out again.

There's no country picker for Prowl. It ends up wherever the server it reached happens to be, and that list changes all the time. The location card shows where you actually landed.

## Picking a country

That's for Global. Select it and a card appears at the top where you choose the exit country. Automatic is the default and usually the fastest, so only pick one if you need a specific place.

Each time you connect the app notes which country you came out in. Next time you open the list, those show as connected before and the rest as untried.

It doesn't claim more than that. Whether a given site works from a given exit changes from week to week, and an old green tick would just mislead you.

## The location card

When you're connected, the home screen shows where you came out: country, city, public IP and who owns the IP.

To get that it asks a public service, and the request goes *through the tunnel* using the local SOCKS port the engine opened. The service only ever sees the exit address, never your phone. No API keys and nothing is stored. Tap the card to refresh.

It tries these in order until one answers:

1. `www.cloudflare.com/cdn-cgi/trace`
2. `speed.cloudflare.com/meta`
3. `ipwho.is`
4. `ipapi.co`

`/cdn-cgi/trace` goes first because `/meta` returns 403 through lots of public exits. If none of them answer you get "location unavailable", the app won't guess.

On Global the engine also reports which country it connected through. If that doesn't match the IP lookup, the card shows both so you can see something's off.

## Fast Tunnel (beta)

New in 2.12. Every engine hands its traffic to a small packet bridge that sits between Android's VPN interface and the engine. Panther has always used a well-known C bridge for that. Now there's a second, newer one you can switch to in **Settings → Fast Tunnel (Beta)**.

- Off by default. Turn it on and reconnect.
- It was tested on a real TUN interface before shipping: TCP both ways, UDP, DNS for Global, and stopping and starting again.
- If it can't start on your phone, Panther quietly falls back to the old bridge and you still get connected. The connection log says which one is running.
- If a site or app acts weird with it on, turn it off and let me know.

The upstream benchmarks were run on a server, not a phone, so don't expect the same numbers on mobile. Whether it's faster for you is something only your phone can answer.

## Things worth knowing

- **Turbo doesn't change your country.** Want another country, use Global or Prowl.
- **Global is slower.** Two hops, that's the price of coming out somewhere else.
- **The first Global connect is slow.** Give it a minute or two while the carrier comes up. It's quicker after that.
- **Prowl lives on public servers.** They're free and they die all the time. The app scores them and skips the dead ones, but some days the lists are just bad.
- **The exit can see your traffic leave.** Same as with any VPN, so stick to HTTPS.
- **It's not anonymity.** It changes where your traffic seems to come from. If you really need Tor, use Tor.
- **Android only.** The Windows client was dropped in 2.0.0.

## Building from source

You need JDK 17, the Android SDK and NDK `27.2.12479018`.

```bash
git clone --recurse-submodules https://github.com/Amirmahdavi2022/Panther-VPN.git
cd Panther-VPN
npm run fetch:android
cd android && ./gradlew assembleRelease
```

Don't forget `--recurse-submodules`. The OpenVPN engine is a pinned submodule and the build fails right away without it.

`npm run fetch:android` downloads the native cores and checks each one against a SHA-256. Global comes in as an official prebuilt Android library, so there's no Go or gomobile step. That library isn't signed by its publisher, so the build pins its hash and stops if it changes. Same for the Fast Tunnel libraries.

CI also fails if any native library is missing from `jniLibs`. A green build once shipped without one, never again.

### Tests

```bash
cd android && ./gradlew testReleaseUnitTest
```

The parsing and decision logic is plain Java with no Android classes in it, so it can be tested for real. Those are also the parts that are the worst to debug from a screenshot.

## Staying current

- **Native cores**: a weekly job opens a PR when upstream ships a release. It doesn't merge by itself, native code that lands on everyone's phone gets built and checked first.
- **The app** checks GitHub releases and can update itself. It checks the SHA-256 and makes sure the new APK is signed with the same certificate.
- **NOTICE.md** is checked in CI against the core versions the build pins. It went out of date once, now the build just fails.

## Licence

Panther is AGPL-3.0 and it's built on other people's work. Credits and licences are in [NOTICE.md](NOTICE.md).

Panther isn't affiliated with or endorsed by any of those projects, so please don't send them Panther bugs.

## One small ask

If Panther works for you, a star on the repo really helps other people find it. Found a bug or got an idea? Open an issue, I'd like to hear it.
