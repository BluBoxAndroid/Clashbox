# Clashbox

Clashbox is an Android client that combines the **original FlClash UI** with the
**official sing-box core**. Subscriptions are consumed in their native
sing-box JSON form — there is no Mihomo/Clash YAML conversion anywhere in the
pipeline.

- Package name: `io.clashbox`
- UI base: FlClash v0.9.3 (commit `be84736605527034689c95bf27828ff27d912c5e`), used unmodified in structure: its pages, Riverpod state management and Android project layout are kept as-is. Only the core implementation behind its existing `CoreInterface`/`CoreController` seam was replaced.
- Core: official sing-box (SagerNet), integrated through a self-built stock `libbox` AAR and the official Android service layer from sing-box-for-android (SFA).

## Architecture

Clashbox is a direct two-party design: FlClash on one side, official sing-box
on the other, and nothing in between.

```
FlClash UI (Flutter/Dart, unmodified call sites)
        |
        |  MethodChannel  io.clashbox/service   (lifecycle: init/syncState/start/stop)
        |  MethodChannel  io.clashbox/core      (JSON method calls, CoreMethod wire protocol)
        v
Kotlin bridge (io.clashbox.core.SingBoxCore, io.clashbox.ServiceState)
        |
        |  libbox CommandClient (status / groups / logs / connections)
        |  io.nekohasekai.sfa.bg.BoxService + VPNService (official SFA service layer)
        v
libbox (stock build of SagerNet/sing-box) running the merged sing-box JSON config
```

- The Dart side keeps FlClash's original core interface. Calls such as
  `proxy.list`, `proxy.select`, `proxy.testDelay`, `traffic.current`,
  `connection.list` and `log.start` are answered from the libbox command
  client and translated into FlClash's existing models.
- Start/stop is driven through the official SFA service layer
  (`BoxService`/`VPNService` with `PlatformInterfaceWrapper`), including the
  Android VPN permission flow.
- Startup is defensive: the first frame renders unconditionally. Core calls
  are asynchronous and time out into a degraded state instead of blocking or
  blanking the UI (covered by `test/features/startup_smoke_test.dart`).

### What happens to a profile at setup time

1. The profile file already contains sing-box JSON (see *Subscriptions*).
2. The Kotlin side parses it, merges the app-level settings that have a
   sing-box equivalent (the TUN inbound derived from the VPN options, log
   level, DNS hijack rule when enabled, the user's group selections), and
   leaves the user's own `tun` inbound untouched when the profile defines one.
3. The merged document is validated with `Libbox.checkConfig`. An invalid
   document is rejected with the core's error message; nothing is
   half-applied.
4. The merged document is handed to the official service layer, which starts
   or reloads the core with it.

Features that have no sing-box counterpart are hidden in the UI rather than
faked: global mode switching, MITM, external Clash providers, rule-set /
GeoData management, and JavaScript profile overrides.

## Subscriptions

Clashbox consumes **native sing-box subscriptions only**:

- A subscription is a sing-box JSON configuration document (the same document
  you would hand to `sing-box run -c config.json`).
- Import works from URL, file, or clipboard, exactly as in FlClash — but the
  content is stored and enabled **as-is**. It is never converted to Clash
  YAML, never round-tripped through a converter, and no fields or outbounds
  are dropped by a translation layer.
- The profile model in the UI is display/management only (name, update time,
  selection state). The bytes that reach the core are the subscription's own
  JSON plus the documented settings merge above.
- Mihomo/Clash YAML subscriptions are **not** supported and are rejected at
  validation time.

## Building

### 1. Build the stock libbox AAR

The core library is built from the official sing-box sources — no fork and
no patches:

```sh
git clone --branch v1.15.0-alpha.13 https://github.com/SagerNet/sing-box.git
cd sing-box
make lib_install   # installs sagernet/gomobile (per the upstream Makefile)
go run ./cmd/internal/build_libbox -target android -platform android/arm64
```

This produces `libbox.aar` for `android/arm64` with `-androidapi 24`,
`-javapkg=io.nekohasekai`, `-libname=box`, the upstream `sharedTags` set, and
the stripped `-s -w` linker flags, using JDK 17 and Go. The resulting AAR
contains exactly the official 155 top-level `io.nekohasekai.libbox` classes.
Copy it to `android/app/libs/libbox.aar`.

### 2. Build the app

Requirements: Flutter 3.47.x, JDK 17, Android SDK with platform 36/37,
build-tools 36/37 and NDK 28, plus a Rust toolchain (for the upstream
`rust_api` plugin) with the Android targets installed
(`aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`)
and `ANDROID_NDK_HOME` pointing at the NDK.

```sh
flutter pub get
flutter build apk --debug --target-platform android-arm64
```

### 3. Tests

```sh
flutter test test/features/startup_smoke_test.dart
```

The smoke tests boot the real home page twice: once with a core channel that
answers normally, and once with a core channel that never answers, asserting
that the first frame renders in both cases.

## Relationship to B-box

B-box served as an implementation reference only — a study of how a FlClash
interface can be wired to a libbox core. **No B-box code is used in this
project**: the source base is the original FlClash tree, the service layer is
the official SFA one, and the bridge code in `io.clashbox.core` /
`io.clashbox.ServiceState` was written for Clashbox.

## Licensing and credits

- FlClash — GPL-3.0. Clashbox is a derivative of FlClash v0.9.3 and is
  distributed under the same license; see `LICENSE`.
- sing-box and sing-box-for-android (SFA) — GPL-3.0, by SagerNet. The libbox
  AAR is an unmodified build of the official sources.
- The launcher icon artwork was provided by the project owner. The
  notification and quick-settings tile icon is the official SFA `ic_menu`
  artwork.
