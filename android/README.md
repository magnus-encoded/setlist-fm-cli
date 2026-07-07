# Setlist Companion (Android)

An Android client around the setlist-fm-cli logic: log in to both setlist.fm
and Spotify, browse the concerts you've attended, and **review each setlist
before adding it to your Spotify library** as a playlist.

## What it does

1. **Connect screen** — enter your setlist.fm API key + username and your
   Spotify app's client ID, then authorize Spotify in a browser tab.
2. **Attended concerts** — pages through your setlist.fm attended list
   (artist, venue, date, song count).
3. **Setlist review** — open a concert to see every song (covers are marked
   with the original artist), untick anything you don't want, rename the
   playlist, and choose public/private. Only then does *Add to Spotify
   library* match the songs on Spotify and create the playlist. Songs that
   can't be matched are listed so you know what's missing.

## Project layout

- `core/` — pure-JVM Kotlin module with the shared domain logic: setlist.fm /
  Spotify API models, song extraction (mirrors `extract_songs` in
  `setlistfm_cli.py`), playlist titles, search-query fallbacks, and PKCE
  helpers. Unit-tested; builds without the Android SDK.
- `app/` — the Android app (Jetpack Compose, Material 3). Retrofit +
  kotlinx.serialization for networking, `EncryptedSharedPreferences` for
  credentials and tokens.

## Authentication

- **setlist.fm** — a plain API key sent as the `x-api-key` header, entered on
  the Connect screen and stored encrypted on the device. Request one at
  <https://www.setlist.fm/settings/api>.
- **Spotify** — the OAuth **Authorization Code + PKCE** flow, the recommended
  flow for mobile apps: no client secret is ever stored in (or needed by) the
  app. Create an app at <https://developer.spotify.com/dashboard> and add
  `setlist-companion://callback` as a redirect URI. Authorization happens in
  a Custom Tab; the redirect returns to the app, which exchanges the code for
  tokens (scopes: `playlist-modify-public playlist-modify-private`) and
  refreshes them automatically.

## Building

Open `android/` in Android Studio (Ladybug or newer) and run the `app`
configuration, or from the command line with the Android SDK installed:

```bash
cd android
gradle :app:assembleDebug
```

Without the Android SDK, the settings script only includes `:core`, so the
shared logic still builds and tests anywhere with a JDK:

```bash
cd android
gradle :core:test
```
