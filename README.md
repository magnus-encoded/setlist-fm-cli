# setlist-fm-cli

A small CLI for the [setlist.fm](https://www.setlist.fm/) API. It can search
artists, fetch a user's attended concerts as an HTML report, and turn those
attended setlists into **Spotify playlists**.

There is also an Android client in [`android/`](android/README.md) that wraps
the same logic: log in to both services on the phone, browse your attended
concerts, and review each setlist before adding it to your Spotify library.

## Install

```bash
pip install -r requirements.txt
```

## Authentication

The CLI talks to two services, each with its own credentials.

### setlist.fm

You need a setlist.fm API key (request one at
<https://www.setlist.fm/settings/api>). It is resolved in this order:

1. `--api-key` flag
2. `SETLISTFM_API_KEY` environment variable
3. `~/.setlistfmcli` config file

### Spotify

Creating playlists writes to your Spotify account, so it uses the OAuth
Authorization Code flow. Create an app at
<https://developer.spotify.com/dashboard>, then add a redirect URI to it
(e.g. `http://localhost:8888/callback`). Credentials are resolved in this
order:

1. `--spotify-client-id` / `--spotify-client-secret` / `--spotify-redirect-uri` flags
2. `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET` / `SPOTIFY_REDIRECT_URI` environment variables
3. `~/.setlistfmcli` config file

On first run a browser opens for you to authorize the app; the token is then
cached in `./.cache` and refreshed automatically. Use `--no-browser` on
headless machines to print the auth URL instead.

### Config file

Put everything in `~/.setlistfmcli` so you don't have to pass flags:

```ini
[setlistfm]
api_key = YOUR_SETLISTFM_API_KEY

[spotify]
client_id = YOUR_SPOTIFY_CLIENT_ID
client_secret = YOUR_SPOTIFY_CLIENT_SECRET
redirect_uri = http://localhost:8888/callback
```

## Usage

```bash
# Search for an artist
python setlistfm_cli.py search artists --artist-name "Metallica"

# Generate an HTML report of attended concerts
python setlistfm_cli.py user-attended <setlistfm-user-id> > concerts.html

# Create one Spotify playlist per attended concert
python setlistfm_cli.py create-playlists <setlistfm-user-id>

# Collect every attended song into a single playlist
python setlistfm_cli.py create-playlists <setlistfm-user-id> --combined \
  --playlist-name "Everything I've seen live"

# Preview what would be created without writing to Spotify
python setlistfm_cli.py create-playlists <setlistfm-user-id> --dry-run
```

`create-playlists` fetches every concert the user marked as attended, and for
each setlist that has song data it searches Spotify for each song and builds a
playlist named like `Artist @ Venue, City (DD-MM-YYYY)`. Songs that can't be
found on Spotify are reported to stderr and skipped. Playlists are private by
default; pass `--public` to make them public.

## Running tests

```bash
python -m unittest discover
```
