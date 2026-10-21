<div align="center">

# Sakura Clash

**A customized Android proxy client based on [Mishka](https://github.com/YuKongA/Mishka)**

Built with [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) and [mihomo](https://github.com/MetaCubeX/mihomo)

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![minSdk](https://img.shields.io/badge/minSdk-31-3DDC84?logo=android&logoColor=white)](#)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

[简体中文](README.md) | [繁體中文](README.zh-TW.md) | English

</div>

---

## About

An Android proxy client powered by the [mihomo](https://github.com/MetaCubeX/mihomo) (Clash.Meta) core, with the UI built on [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) + Jetpack Compose and a data layer of Room + Ktor + Koin. The core is integrated through a subprocess + dual-channel JNI bridge, supporting three tunnel modes: **VPN**, **ROOT TUN** and **ROOT TPROXY**

## About this fork

This is my personal customized build on top of Mishka, open-sourced under GPL-3.0 just like the original. Everything upstream has is still here (see Features below); on top of that, it adds:

**Custom features**

- A standalone Rules settings page: add, delete or clear rules, saved as a dedicated override that subscription updates won't wipe ✔️
- Custom subscription ordering: move entries up and down in the list, and the order is remembered ✔️
- Share-link parsing: paste share links or a whole subscription payload to import directly ✔️
- In-app language switching, no longer tied to the system language ✔️
- TLS fragmentation: splits the first TLS handshake flight into fragments to get around domain-based connection resets (Settings → Meta settings; fragment size and delay are adjustable) ✔️
- Geo data bundled into the APK, and updatable from within the app ✔️
- Liquid-glass cards + custom wallpaper: cards on the home screen and other pages use a liquid-glass effect, with blur radius and tint strength adjustable in Theme settings; pick any image as a full-screen static wallpaper ✔️

**Download**: get the APK from the [Releases](../../releases) page. This is a personal build tuned to my own needs, so it may not suit everyone.

---

## Features

**Proxy and core**

- Bundled [mihomo](https://github.com/MetaCubeX/mihomo) core (Mishka fork), unified into a single `libmihomo.so` that serves both as the runtime entry point and the JNI facade for subscription import
- Three tunnel modes, switchable at any time:
  - **VPN** — no root required, based on the system `VpnService`
  - **ROOT TUN** — root-managed TUN with `auto-route` + GSO aggregation for large packets
  - **ROOT TPROXY** — kernel-level transparent proxying (iptables + fwmark), with near-direct performance
- **Per-app proxy** — allow/block lists, handled per mode via VpnService / `include-package` / iptables uid-owner
- **Hotspot traffic handling** (ROOT) — hotspot traffic can either bypass the proxy or go through transparent proxying (TPROXY)

**Subscription management**

- Import subscriptions via URL / local file / QR code scan
- Three-stage sandboxed import pipeline (Pending → Processing → Imported), cancellable and rollback-safe
- **age-encrypted subscriptions** — per-profile keys, stored encrypted as-is and decrypted at runtime; built-in X25519 / post-quantum keypair generation
- **Per-profile User-Agent** override; subscription downloads can optionally go through the local proxy
- Scheduled auto-update (AlarmManager) + manual update-all

**Interface and experience**

- Smooth rounded corners + premium-material top/bottom bars (or a liquid-glass bottom bar)
- Light/dark mode + follow system + Monet dynamic colors
- Wide-screen layout (NavigationRail)
- Built-in YAML config editor ([scripta](https://github.com/YuKongA/scripta))
- Live traffic, connections, logs, DNS lookup and provider management
- Dynamic traffic notification (VPN mode) + a Quick Settings tile for one-tap start/stop
- **Wi-Fi auto-switch** — automatically stop the proxy or switch to Direct mode on specified SSIDs
- Auto-start on boot, hide from recents, and other handy toggles

## Building

### Requirements

- JDK 21
- Android SDK + NDK (with clang); SDK versions are in `build-logic/src/main/kotlin/ProjectConfig.kt`
- Go (version in `mihomo/go.mod`)
- Git (for submodules and version generation)

### Steps

```bash
# 1. Clone the repository
git clone https://github.com/YuKongA/Mishka.git
cd Mishka

# 2. Initialize submodules (mihomo fork + scripta editor)
git submodule update --init --recursive

# 3. Download GeoIP data before the first build (bundled into assets)
./gradlew :app:downloadGeoFiles

# 4. Build the APK (assemble triggers mihomo cgo cross-compilation + CMake automatically)
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# 5. When only Kotlin changed, skip the Go build for a seconds-fast check
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a
```

> `mihomo` is pulled in as a git submodule from the `Mishka` branch of [YuKongA/mihomo](https://github.com/YuKongA/mihomo) (with 5 patches for Android fd/TUN). Gradle drives the Go cross-compilation automatically; the output lands in `app/src/main/jniLibs/<ABI>/`

## Credits

- [mihomo](https://github.com/MetaCubeX/mihomo) — proxy core
- [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) — UI component library
- [sparkle](https://github.com/xishang0128/sparkle) / [Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid) — implementation references
- [scripta](https://github.com/YuKongA/scripta) — code editor

## License

This project is open-sourced under the [GPL-3.0](LICENSE) license, the same as the [mihomo](https://github.com/MetaCubeX/mihomo) core
