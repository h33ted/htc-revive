<p align="center"><img src="tools/ic_launcher_512.png" width="128" alt="HTC Revive icon"></p>

# HTC Revive

Brings the dead online features of the **HTC One M7 (Sense 5, Android 4.2.2)** back to life,
entirely on the phone. No PC, proxy server or API key needed.

| Feature | Original backend (dead) | HTC Revive serves it from |
|---|---|---|
| Weather app, clock widget, lockscreen | `htc2.accu-weather.com` | [Open-Meteo](https://open-meteo.com/) |
| BlinkFeed news | `geo-prism.htcsense.com`, `img-prism.htcsense.com` | RSS/Atom feeds you choose |
| Stocks app and widget | `api.htc.go.yahoo.com` | Yahoo Finance public endpoints |

The stock HTC apps stay untouched. HTC Revive runs small local servers that speak each
app's original protocol, and redirects the dead hostnames to them.

## Requirements

- HTC One M7 on Sense 5 / Android 4.2.2. Other Sense 5 devices may work but are untested.
- Root (`su`). S-ON is fine: changes to `/system` are re-applied at every boot.

## Install and use

1. Install `HtcRevive.apk` and open **HTC Revive**.
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
- **Local HTTPS**: BlinkFeed talks HTTPS, so the app installs its own local CA into the
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
- Android NDK r23c, for the native TLS library

```bash
cd jni && $NDK/ndk-build && cd ..
```

```bash
TOOLS=/path/to/tools ./build.sh
```

The first build generates your own local CA, server certificate and signing key under
`certs/` (all ignored by git).

## Credits and license

GPL-3.0-or-later. Built on [wolfSSL](https://github.com/wolfSSL/wolfssl) and
[Wolfius](https://github.com/gohoski/Wolfius) by gohoski. See [CREDITS.md](CREDITS.md) for
details and data sources.

Not affiliated with HTC, AccuWeather or Yahoo.
