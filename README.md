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
| **Relay** | A country you pick | Fast (one hop), varies a lot | No |

They're the four cards above the connect button. Tap one to switch. Turbo is the default.

**Turbo** hides your IP and your ISP, but you stay in roughly the same place. The network behind it is built to do that, so sites still see your own country.

**Global** is the one that actually moves you. It runs inside the Turbo tunnel instead of dialling out on its own, which is why it can come up on filtered networks at all. You can pick an exit country or leave it on automatic.

**Prowl** is for days when the other two get blocked. It keeps a list of servers, tests them on your phone and uses whichever one really carries traffic. One hop, so it's quick once it connects.

**Relay** is the one where you say which country. It runs OpenVPN against the public VPN Gate directory, so the list of countries is whatever has a volunteer server in it right now, not a list baked into the app.

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

## Relay, and why it's the fourth one

Relay is a different trade from the other three. The other engines run on infrastructure Panther knows how to reach; Relay runs on somebody's spare machine that was advertised in a public feed some hours ago and may well be gone. That's the deal: you get to name a country and come out there in one hop, and in exchange the server might not answer.

So a connect here is a sequence, not a single attempt. It ranks the relays, takes the best one, and if it refuses the handshake, fails auth, or just says nothing for 35 seconds, it stops it and takes the next. Five relays get tried before you're told nothing worked. The status line names each one as it goes, so a slow connect looks like progress rather than a frozen screen.

Set it to **Automatic** and it takes the best relay anywhere. Pick a country and it stays in that country or reports that the country has nothing behind it — it will never quietly put you somewhere else, because coming out in the wrong country is the one failure that matters here.

The directory is fetched at runtime and cached for three hours, and a failed refresh falls back to the last good copy. On a network where the feed itself is blocked, connect with Turbo once and Relay will have a list.

Your settings come with it. Split tunnelling, the kill switch, the MTU and DNS leak protection all apply to Relay exactly as they do to the other three — it builds its tunnel from an OpenVPN profile rather than through Panther's own bridge, so those are translated onto the profile rather than ignored. One difference worth stating: with the kill switch on, Relay holds the tunnel open and drops traffic while it's reconnecting, but a connect that fails on all five relays ends with no tunnel at all rather than in a blocked state. The other engines can sit there blocking; this one can't, because the interface belongs to its engine and goes with it.

Two things it can't do. **Proxy** and **Smart** modes don't apply: Relay is always a full system VPN and has no local proxy to hand you, so the mode row is greyed out while it's selected and says so. And **Fast Tunnel** is a bridge between Android's VPN interface and a Panther engine — Relay's engine owns its own interface, so the setting doesn't reach it either way.

Two more things to know. Relay does not report a ping, and the location card shows the relay's own country rather than an IP lookup — the engine opens no SOCKS port for Panther to ask through, and the relay's country is the honest answer anyway. And these are strangers' machines: same as with any VPN, the exit sees your traffic leave, so stick to HTTPS.

## Picking a country

That's for Global and Relay. Select either and a card appears at the top where you choose the exit country. Automatic is the default and usually the fastest, so only pick one if you need a specific place. The rest of this section is about Global's list; Relay's is built from the live directory and is covered above.

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

Relay is the exception: it opens no SOCKS port for Panther to ask through, so the card names the relay's own country and host instead of looking the address up, and there's nothing to refresh.

## Fast Tunnel (beta)

New in 2.12. Turbo, Global and Prowl hand their traffic to a small packet bridge that sits between Android's VPN interface and the engine. (Relay doesn't use one — its engine builds the interface itself.) Panther has always used a well-known C bridge for that. Now there's a second, newer one you can switch to in **Settings → Fast Tunnel (Beta)**.

- Off by default. Turn it on and reconnect.
- It was tested on a real TUN interface before shipping: TCP both ways, UDP, DNS for Global, and stopping and starting again.
- If it can't start on your phone, Panther quietly falls back to the old bridge and you still get connected. The connection log says which one is running.
- If a site or app acts weird with it on, turn it off and let me know.

The upstream benchmarks were run on a server, not a phone, so don't expect the same numbers on mobile. Whether it's faster for you is something only your phone can answer.

## Things worth knowing

- **Turbo doesn't change your country.** Want another country, use Global or Relay.
- **Global is slower.** Two hops, that's the price of coming out somewhere else.
- **The first Global connect is slow.** Give it a minute or two while the carrier comes up. It's quicker after that.
- **Prowl lives on public servers.** They're free and they die all the time. The app scores them and skips the dead ones, but some days the lists are just bad.
- **Relay does too, and more so.** Volunteer machines with no uptime promise. It tries five before giving up, and some days all five refuse. If Relay won't come up, that's not a bug in the app, that's the pool.
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
