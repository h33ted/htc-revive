<p align="center"><img src="tools/ic_launcher_512.png" width="128" alt="HTC Revive icon"></p>

# HTC Revive

Brings the dead online features of **HTC Sense 5** phones back to life, entirely on the
phone. No PC, proxy server or API key needed.

> **Tested only on an HTC One M7 (Sense 5, Android 4.2.2).** Other Sense 5 devices (One mini,
> One max, Butterfly s, ...) ship the same Weather, BlinkFeed and Stocks apps and should work,
> but haven't been tried. Sense 5 on Android 4.3, whose Weather app switched to HTTPS, is
> supported from v1.0.4 but not yet tested on a device. Reports welcome.

| Feature | Original backend (dead) | HTC Revive serves it from |
|---|---|---|
| Weather app, clock widget, lockscreen | `htc2.accu-weather.com` | [Open-Meteo](https://open-meteo.com/) |
| BlinkFeed news | `geo-prism.htcsense.com`, `img-prism.htcsense.com` | RSS/Atom feeds you choose |
| Stocks app and widget | `api.htc.go.yahoo.com` | Yahoo Finance public endpoints |

The stock HTC apps stay untouched. HTC Revive runs small local servers that speak each
app's original protocol, and redirects the dead hostnames to them.

### Older phones (Sense 1/2, Android 2.2+)

**`HtcReviveLegacy.apk`** in each release does the same for early Sense phones. It's tested on
an **HTC Wildfire (Android 2.2.1)**, where the Weather app and widget and the Stocks app and
widget work again. Their dead backends are `htc.accuweather.com` (an older AccuWeather API) and
the same `api.htc.go.yahoo.com` as Sense 5. Like the main app it needs root; it doesn't need a
local CA, since these apps only use plain HTTP. It uses the phone's own theme; press **Menu** for
the request log. A "My location" weather widget still needs the phone to have a location fix;
widgets set to a city work right away. Other Sense 1/2 phones (Desire, Legend, Hero on 2.2, ...)
probably use the same APIs but are untested. News Reader isn't covered yet.

## Requirements

- An HTC phone running Sense 5 on Android 4.2 or 4.3 (`minSdkVersion` 17). Sense 5.5 and 6
  are untested.
- Root (`su`). S-ON is fine: changes to `/system` are re-applied at every boot.
- An ARMv7 CPU (every Sense 5 phone has one).

## Install and use

1. Download `HtcRevive.apk` from the latest [release](../../releases/latest), install it and
   open **HTC Revive**.
2. Tick **Revive HTC services** and grant root.
3. Weather, Stocks and BlinkFeed refresh as usual. For BlinkFeed, pick your topics in
   BlinkFeed's own settings.

In the app:
- **+** adds a BlinkFeed source (any RSS or Atom URL). **Double-tap** a source to remove it.
- **⋮ → Request log** shows what the HTC apps asked for and what was served.
- **⋮ → Ports** changes the local ports (default `28080`/`28443`) if something else uses them.

## How it works

- **Redirects**: adds the dead hostnames to `/system/etc/hosts` (pointing at `127.0.0.1`) and
  adds `iptables` rules redirecting loopback `:80`/`:443` to the local servers.
- **Local HTTPS**: BlinkFeed (and Weather on 4.3) talks HTTPS, so the app installs its own local CA into the
  user trust store. The CA is **name-constrained** to `htcsense.com` and `accu-weather.com`,
  so it can't be used to impersonate any other site. Each build generates its own CA
  (`tools/gen-certs.sh`). Private keys are never committed.
- **Modern TLS on Android 4.2**: the phone's own TLS stack stops at TLS 1.0, which today's
  servers reject. HTC Revive's upstream fetches use a bundled
  [wolfSSL](https://github.com/wolfSSL/wolfssl) client (TLS 1.2/1.3) that verifies
  certificates and hostnames against Mozilla's root store.
- **Coexists with [Wolfius](https://github.com/gohoski/Wolfius)**: Wolfius intercepts all
  outbound `:443` traffic, so HTC Revive exempts its own uid from that redirect.
- **UI** uses the ROM's own Sense framework (`HtcDeviceDefault` theme and `com.htc.widget`
  action bar and dialogs), compiled against stubs in `tools/htcstubs`.

## Building

No Gradle or Android SDK install is needed. You need:
- JDK 11+ and `openssl` on `PATH`
- a tools directory with `android-17.jar`, `aapt2` (or `aapt2.exe`), `r8.jar` and `apksig.jar`
  (on Windows, use R8 8.5 or later: the D8 in build-tools 34 crashes there on some classes)
- Android NDK r23c, for the native TLS library

```bash
cd jni && $NDK/ndk-build && cd ..
```

```bash
TOOLS=/path/to/tools ./build.sh
```

The first build generates your own local CA, server certificate and signing key under
`certs/` (all ignored by git).

The legacy APK also needs `android-8.jar` (SDK platform 8) in the tools directory and NDK r13b
for the ARMv6 TLS library:

```bash
cd legacy/jni && $NDK_R13B/ndk-build && cd ../..
```

```bash
TOOLS=/path/to/tools legacy/build.sh
```

## Credits and license

GPL-3.0-or-later. Built on [wolfSSL](https://github.com/wolfSSL/wolfssl) and
[Wolfius](https://github.com/gohoski/Wolfius) by gohoski. See [CREDITS.md](CREDITS.md) for
details and data sources.

Not affiliated with HTC, AccuWeather or Yahoo.
