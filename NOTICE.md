# Third-party notices

Panther is an independent Android client maintained by amirmahdavi2023.

## Fork notice

Panther is a fork of https://github.com/hamvex/AetherGUI (released as "Aethon"),
which is itself an independent client for the CluvexStudio/Aether networking core.

Panther is not affiliated with, endorsed by, or supported by the Aethon or Aether
projects. Do not report Panther issues to them.

Distributed under the same AGPL-3.0 license as the upstream project.
Complete corresponding source: https://github.com/amirmahdavi2023/Panther-VPN

## Aether

This application bundles the Aether core from https://github.com/CluvexStudio/Aether,
licensed under GNU AGPL v3.0.

The Android cores for ARMv7, ARM64 and x86_64 are downloaded from the official
**v1.8.0** release during the build and verified against the publisher-provided
SHA-256 files. The pinned version lives in `scripts/fetch-android-assets.ps1`, and
the release workflow fails the build if that pin and the version named here ever
disagree.

Aether and its marks are subject to the upstream project's TRADEMARK.md policy.
Panther is an independent frontend and is not endorsed by CluvexStudio.

## HEV Socks5 Tunnel

Android VPN routing uses HEV Socks5 Tunnel v2.16.0 from
https://github.com/heiher/hev-socks5-tunnel under the MIT license. The full text is
in `third-party/hev-socks5-tunnel-LICENSE.txt`.

## ics-openvpn

The relay engine in `android/vpnLib` is derived from ics-openvpn by Arne Schwabe,
licensed under GNU GPL v2.0. It ships in the package but is not reachable from the
UI in this release.

## Location lookup

The location card asks one of four public services which address the tunnel comes
out of: `speed.cloudflare.com`, `www.cloudflare.com/cdn-cgi/trace`, `ipwho.is` and
`ipapi.co`. Every request goes through the tunnel's own SOCKS proxy, so none of them
ever sees the device's real address. No account or API key is used and nothing is
stored.
