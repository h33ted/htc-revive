# Credits

HTC Revive's outbound TLS support (the app's own HTTPS fetches) is built on two GPL-3.0 projects:

- **Wolfius** — https://github.com/gohoski/Wolfius (© gohoski), GPL-3.0.
  The wolfSSL build configuration (`jni/user_settings.h`) and the native-socket
  file-descriptor extraction technique are adapted from Wolfius. HTC Revive's
  `jni/tls_bridge.c` is an original, client-only TLS bridge that verifies upstream
  certificates and hostnames.
- **wolfSSL** — https://github.com/wolfSSL/wolfssl, GPL-3.0 (bundled under
  `third_party/wolfssl`, commit ac01707, v5.9.2). One local change: the `accept4`
  guard in `wolfcrypt/src/wc_port.c` also requires `__ANDROID_API__ >= 21`, since
  Android 4.2's libc has no `accept4`.

Because HTC Revive links wolfSSL and reuses Wolfius-derived build config under the GPL,
HTC Revive as a whole is distributed under GPL-3.0-or-later. See LICENSE.

Root certificate store (`assets/roots.pem`) is Mozilla's CA bundle as distributed by
the curl project (https://curl.se/ca/cacert.pem), public domain / MPL-2.0.

## Data sources

- **Weather**: [Open-Meteo](https://open-meteo.com/) forecast and geocoding APIs,
  data licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- **Stocks**: Yahoo Finance's public chart and search endpoints (unofficial, keyless;
  may change or rate-limit at any time).
- **BlinkFeed**: whatever RSS/Atom feeds you configure; articles belong to their publishers.

HTC, Sense, BlinkFeed and AccuWeather are trademarks of their respective owners. HTC Revive
is an independent project and is not affiliated with HTC, AccuWeather or Yahoo.
