import os
import configparser
import json
import sys
import click
import requests
import time
from datetime import datetime # Import the datetime module

# --- Configuration ---
def get_api_key(api_key_param):
    """
    Get API key from param, env var, or config file.
    Order of precedence:
    1. --api-key parameter
    2. SETLISTFM_API_KEY environment variable
    3. .setlistfmcli config file
    """
    if api_key_param:
        return api_key_param

    env_key = os.environ.get('SETLISTFM_API_KEY')
    if env_key:
        return env_key

    config = configparser.ConfigParser()
    config.read(os.path.expanduser('~/.setlistfmcli'))
    if 'setlistfm' in config and 'api_key' in config['setlistfm']:
        return config['setlistfm']['api_key']

    return None

def get_accept_header(accept_header_param):
    """Get Accept header from param, env var, or config file."""
    if accept_header_param:
        return accept_header_param
    
    env_header = os.environ.get('SETLISTFM_ACCEPT_HEADER')
    if env_header:
        return env_header

    config = configparser.ConfigParser()
    config.read(os.path.expanduser('~/.setlistfmcli'))
    if 'setlistfm' in config and 'accept_header' in config['setlistfm']:
        return config['setlistfm']['accept_header']

    return 'application/json' # Default value

# --- Spotify Configuration & Auth ---
def get_spotify_setting(param_value, env_var, config_key, default=None):
    """Resolve a Spotify setting from param, env var, or config file.

    Order of precedence:
    1. CLI parameter
    2. Environment variable
    3. [spotify] section of the ~/.setlistfmcli config file
    4. Provided default
    """
    if param_value:
        return param_value

    env_value = os.environ.get(env_var)
    if env_value:
        return env_value

    config = configparser.ConfigParser()
    config.read(os.path.expanduser('~/.setlistfmcli'))
    if 'spotify' in config and config_key in config['spotify']:
        return config['spotify'][config_key]

    return default

def get_spotify_client(client_id, client_secret, redirect_uri, open_browser=True):
    """Create an authenticated Spotipy client using the Authorization Code flow.

    Creating playlists requires user authorization, so this uses SpotifyOAuth,
    which caches the token to ./.cache and refreshes it automatically. The
    import is performed lazily so the rest of the CLI works without spotipy
    installed.
    """
    # Imported lazily so commands that don't touch Spotify (and the test
    # suite) don't require spotipy to be importable.
    import spotipy
    from spotipy.oauth2 import SpotifyOAuth

    resolved_id = get_spotify_setting(client_id, 'SPOTIFY_CLIENT_ID', 'client_id')
    resolved_secret = get_spotify_setting(client_secret, 'SPOTIFY_CLIENT_SECRET', 'client_secret')
    resolved_redirect = get_spotify_setting(
        redirect_uri, 'SPOTIFY_REDIRECT_URI', 'redirect_uri',
        default='http://localhost:8888/callback'
    )

    if not resolved_id or not resolved_secret:
        return None

    # playlist-modify-public is needed for public playlists, the private scope
    # for private ones. Requesting both keeps the cached token valid for either.
    scope = 'playlist-modify-public playlist-modify-private'

    auth_manager = SpotifyOAuth(
        client_id=resolved_id,
        client_secret=resolved_secret,
        redirect_uri=resolved_redirect,
        scope=scope,
        open_browser=open_browser,
    )
    return spotipy.Spotify(auth_manager=auth_manager)

# --- Setlist song extraction ---
def extract_songs(setlist):
    """Return the list of songs played in a setlist.

    Each item is a dict with 'name' (the song title) and 'artist' (the artist
    to search Spotify for -- the original artist for covers, otherwise the
    performing artist). Empty song names (e.g. segues/spacers) are skipped.
    """
    performing_artist = setlist.get('artist', {}).get('name', '')
    songs = []
    sets = setlist.get('sets', {}).get('set', [])
    for set_section in sets:
        for song in set_section.get('song', []):
            name = (song.get('name') or '').strip()
            if not name:
                continue
            cover = song.get('cover') or {}
            search_artist = cover.get('name') or performing_artist
            songs.append({'name': name, 'artist': search_artist})
    return songs

# --- API Request Logic ---
def make_api_request(api_key, accept_header, endpoint, params=None):
    """Makes a request to the setlist.fm API and handles errors with retries."""
    base_url = "https://api.setlist.fm/rest/1.0"
    headers = {
        'x-api-key': api_key,
        'Accept': accept_header
    }
    
    if params:
        params = {k: v for k, v in params.items() if v is not None}
    else:
        params = {}
    
    max_retries = 3
    backoff_factor = 2
    delay = 1

    for attempt in range(max_retries):
        try:
            response = requests.get(f"{base_url}/{endpoint}", headers=headers, params=params)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.HTTPError as err:
            if err.response.status_code == 429 or err.response.status_code >= 500:
                if attempt < max_retries - 1:
                    click.echo(f"Warning: Received status {err.response.status_code}. Retrying in {delay}s...", err=True)
                    time.sleep(delay)
                    delay *= backoff_factor
                else:
                    click.echo(f"Error: Received status {err.response.status_code} after {max_retries} attempts.", err=True)
                    click.echo(err.response.text, err=True)
                    return None
            else:
                click.echo(f"Error: {err.response.status_code} {err.response.reason}", err=True)
                click.echo(err.response.text, err=True)
                return None
        except requests.exceptions.RequestException as e:
            click.echo(f"A network error occurred: {e}", err=True)
            return None
    return None

# --- HTML Generation ---
def generate_html_report(setlists, user_id, limit_reached, max_requests):
    """Generates an HTML report from a list of setlists."""
    html = f"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Concert History for {user_id}</title>
        <style>
            body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; line-height: 1.6; background-color: #f4f4f4; color: #333; margin: 0; padding: 20px; }}
            .container {{ max-width: 800px; margin: auto; background: #fff; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }}
            h1 {{ color: #1DB954; }}
            .concert {{ border-bottom: 1px solid #eee; padding: 15px 0; }}
            .concert:last-child {{ border-bottom: none; }}
            .date {{ font-weight: bold; color: #555; }}
            .artist {{ font-size: 1.2em; font-weight: bold; }}
            .artist a {{ color: #1DB954; text-decoration: none; }}
            .artist a:hover {{ text-decoration: underline; }}
            .venue {{ font-style: italic; color: #777; }}
            .warning {{ background-color: #fff3cd; border: 1px solid #ffeeba; color: #856404; padding: 10px; border-radius: 5px; margin-top: 20px; }}
        </style>
    </head>
    <body>
        <div class="container">
            <h1>Concert History for {user_id}</h1>
    """

    if limit_reached:
        html += f"<p class='warning'><b>Note:</b> This report may be incomplete because the run was configured to stop after a maximum of {max_requests} requests.</p>"

    if not setlists:
        html += "<p>No attended concerts found.</p>"
    else:
        def sort_key(setlist):
            try:
                return datetime.strptime(setlist.get('eventDate'), '%d-%m-%Y')
            except (ValueError, TypeError):
                return datetime.min

        for setlist in sorted(setlists, key=sort_key, reverse=True):
            artist = setlist.get('artist', {}).get('name', 'N/A')
            venue = setlist.get('venue', {}).get('name', 'N/A')
            city = setlist.get('venue', {}).get('city', {}).get('name', 'N/A')
            country = setlist.get('venue', {}).get('city', {}).get('country', {}).get('name', 'N/A')
            event_date = setlist.get('eventDate', 'N/A')
            url = setlist.get('url', '#') # Get the URL for the setlist

            # --- THE FIX ---
            # The artist name is now a clickable link
            html += f"""
            <div class="concert">
                <div class="date">{event_date}</div>
                <div class="artist"><a href="{url}" target="_blank" rel="noopener noreferrer">{artist}</a></div>
                <div class="venue">{venue} in {city}, {country}</div>
            </div>
            """

    html += """
        </div>
    </body>
    </html>
    """
    return html

# --- CLI Structure ---
@click.group()
@click.option('--api-key', help='Your setlist.fm API key.')
@click.option('--accept-header', help='The Accept header for the request.')
@click.pass_context
def cli(ctx, api_key, accept_header):
    """A CLI tool for the setlist.fm API."""
    ctx.ensure_object(dict)
    
    resolved_api_key = get_api_key(api_key)
    if not resolved_api_key:
        click.echo("Error: API key not found. Please provide it via --api-key, SETLISTFM_API_KEY env var, or in ~/.setlistfmcli", err=True)
        sys.exit(1)

    ctx.obj['API_KEY'] = resolved_api_key
    ctx.obj['ACCEPT_HEADER'] = get_accept_header(accept_header)

@cli.group()
def search():
    """Search for artists, etc."""
    pass

@search.command(name='artists')
@click.option('--artist-name', help='The name of the artist.')
@click.option('--artist-mbid', help='The MusicBrainz ID of the artist.')
@click.option('-p', type=int, help='The page number.')
@click.pass_context
def search_artists(ctx, artist_name, artist_mbid, p):
    """Search for an artist."""
    params = {'artistName': artist_name, 'artistMbid': artist_mbid, 'p': p}
    result = make_api_request(ctx.obj['API_KEY'], ctx.obj['ACCEPT_HEADER'], 'search/artists', params)
    if result:
        click.echo(json.dumps(result, indent=2))

@cli.command()
@click.argument('mbid')
@click.pass_context
def artist(ctx, mbid):
    """Get a specific artist by their MusicBrainz ID."""
    result = make_api_request(ctx.obj['API_KEY'], ctx.obj['ACCEPT_HEADER'], f'artist/{mbid}')
    if result:
        click.echo(json.dumps(result, indent=2))

def fetch_attended_setlists(api_key, accept_header, user_id, rate_limit, max_requests):
    """Fetch all of a user's attended setlists, paging through the API.

    Returns a tuple of (setlists, limit_hit) where limit_hit indicates the
    fetch stopped early because max_requests was reached.
    """
    all_setlists = []
    page = 1
    limit_hit = False

    sleep_duration = 1.0 / rate_limit if rate_limit > 0 else 0

    click.echo(f"Fetching attended concerts for {user_id}...", err=True)
    click.echo(f"Rate limit: {rate_limit}/sec, Max requests: {max_requests}", err=True)

    while page <= max_requests:
        endpoint = f'user/{user_id}/attended'
        params = {'p': page}

        click.echo(f" - Fetching page {page}...", err=True)
        data = make_api_request(api_key, accept_header, endpoint, params)

        if not data:
            click.echo("Stopping due to API error.", err=True)
            break

        if 'setlist' not in data or not data['setlist']:
            break

        all_setlists.extend(data['setlist'])

        total = data.get('total', 0)
        items_per_page = data.get('itemsPerPage', 20)

        if (page * items_per_page) >= total:
            break

        page += 1

        if page > max_requests:
            limit_hit = True
            click.echo(f"Stopping: Reached max requests limit of {max_requests}.", err=True)
            break

        time.sleep(sleep_duration)

    click.echo(f"Finished fetching. Found {len(all_setlists)} concerts.", err=True)
    return all_setlists, limit_hit

@cli.command(name='user-attended')
@click.argument('user_id')
@click.option('--rate-limit', type=float, default=1.0, help='Requests per second limit.', show_default=True)
@click.option('--max-requests', type=int, default=1440, help='Maximum requests per run to avoid hitting daily API limits.', show_default=True)
@click.pass_context
def user_attended(ctx, user_id, rate_limit, max_requests):
    """Get a user's attended concerts and generate an HTML report."""
    all_setlists, limit_hit = fetch_attended_setlists(
        ctx.obj['API_KEY'], ctx.obj['ACCEPT_HEADER'], user_id, rate_limit, max_requests
    )
    html_output = generate_html_report(all_setlists, user_id, limit_hit, max_requests)
    click.echo(html_output)

def find_spotify_track_uri(sp, name, artist):
    """Search Spotify for a track, returning its URI or None.

    Tries a field-qualified query first, then progressively looser queries so
    songs that don't match the strict form still have a chance of resolving.
    """
    queries = []
    if artist:
        queries.append(f'track:{name} artist:{artist}')
        queries.append(f'{name} {artist}')
    queries.append(name)

    for query in queries:
        try:
            results = sp.search(q=query, type='track', limit=1)
        except Exception as e:  # noqa: BLE001 - surface API/network errors, keep going
            click.echo(f"   ! Spotify search error for '{name}': {e}", err=True)
            return None
        items = results.get('tracks', {}).get('items', [])
        if items:
            return items[0].get('uri')
    return None

def build_playlist_title(setlist):
    """Build a human-readable playlist title from a setlist."""
    artist = setlist.get('artist', {}).get('name', 'Unknown Artist')
    venue = setlist.get('venue', {}).get('name', '')
    city = setlist.get('venue', {}).get('city', {}).get('name', '')
    event_date = setlist.get('eventDate', '')

    location = ', '.join(part for part in (venue, city) if part)
    title = artist
    if location:
        title += f' @ {location}'
    if event_date:
        title += f' ({event_date})'
    return title

@cli.command(name='create-playlists')
@click.argument('user_id')
@click.option('--spotify-client-id', help='Spotify app client ID.')
@click.option('--spotify-client-secret', help='Spotify app client secret.')
@click.option('--spotify-redirect-uri', help='Spotify OAuth redirect URI. [default: http://localhost:8888/callback]')
@click.option('--public/--private', 'public', default=False, help='Create public playlists instead of private ones.', show_default=True)
@click.option('--combined', is_flag=True, help='Create a single combined playlist instead of one per concert.')
@click.option('--playlist-name', help='Name for the combined playlist (with --combined).')
@click.option('--no-browser', is_flag=True, help='Do not open a browser for Spotify auth; print the URL instead.')
@click.option('--dry-run', is_flag=True, help='Resolve tracks and show what would be created without writing to Spotify.')
@click.option('--rate-limit', type=float, default=1.0, help='setlist.fm requests per second limit.', show_default=True)
@click.option('--max-requests', type=int, default=1440, help='Maximum setlist.fm requests per run.', show_default=True)
@click.pass_context
def create_playlists(ctx, user_id, spotify_client_id, spotify_client_secret, spotify_redirect_uri,
                     public, combined, playlist_name, no_browser, dry_run, rate_limit, max_requests):
    """Create Spotify playlists from a user's attended setlists.

    Fetches every concert USER_ID has marked as attended on setlist.fm and, for
    each setlist that has songs, builds a Spotify playlist of those songs. Use
    --combined to collect everything into one playlist instead.
    """
    # Fetch the attended setlists first so we fail fast on setlist.fm errors
    # before prompting for Spotify auth.
    all_setlists, limit_hit = fetch_attended_setlists(
        ctx.obj['API_KEY'], ctx.obj['ACCEPT_HEADER'], user_id, rate_limit, max_requests
    )

    setlists_with_songs = [(s, songs) for s in all_setlists if (songs := extract_songs(s))]
    if not setlists_with_songs:
        click.echo("No setlists with songs found; nothing to create.", err=True)
        return

    click.echo(f"{len(setlists_with_songs)} of {len(all_setlists)} concerts have song data.", err=True)

    sp = None
    spotify_user_id = None
    if not dry_run:
        sp = get_spotify_client(
            spotify_client_id, spotify_client_secret, spotify_redirect_uri,
            open_browser=not no_browser
        )
        if sp is None:
            click.echo(
                "Error: Spotify credentials not found. Provide --spotify-client-id and "
                "--spotify-client-secret, set SPOTIFY_CLIENT_ID / SPOTIFY_CLIENT_SECRET, "
                "or add a [spotify] section to ~/.setlistfmcli",
                err=True,
            )
            sys.exit(1)
        spotify_user_id = sp.current_user()['id']

    def resolve_uris(songs):
        """Resolve a setlist's songs to a de-duplicated list of track URIs."""
        uris = []
        seen = set()
        missing = 0
        for song in songs:
            if dry_run:
                # Without auth we can't search; just report intended tracks.
                click.echo(f"     - {song['artist']} - {song['name']}", err=True)
                continue
            uri = find_spotify_track_uri(sp, song['name'], song['artist'])
            if uri and uri not in seen:
                seen.add(uri)
                uris.append(uri)
            elif not uri:
                missing += 1
                click.echo(f"     ! Not found on Spotify: {song['artist']} - {song['name']}", err=True)
        return uris, missing

    def add_tracks(playlist_id, uris):
        """Add URIs to a playlist, chunked to Spotify's 100-item limit."""
        for i in range(0, len(uris), 100):
            sp.playlist_add_items(playlist_id, uris[i:i + 100])

    if combined:
        name = playlist_name or f"Concerts attended by {user_id}"
        all_uris = []
        seen = set()
        for setlist, songs in setlists_with_songs:
            click.echo(f" - {build_playlist_title(setlist)}", err=True)
            uris, _ = resolve_uris(songs)
            for uri in uris:
                if uri not in seen:
                    seen.add(uri)
                    all_uris.append(uri)

        if dry_run:
            click.echo(f"[dry-run] Would create playlist '{name}' (resolution skipped without auth).", err=True)
            return

        click.echo(f"Creating combined playlist '{name}' with {len(all_uris)} tracks...", err=True)
        playlist = sp.user_playlist_create(
            spotify_user_id, name, public=public,
            description=f"All concerts attended by {user_id}, via setlist.fm."
        )
        add_tracks(playlist['id'], all_uris)
        click.echo(playlist['external_urls']['spotify'])
        if limit_hit:
            click.echo(
                f"Note: playlist may be incomplete; stopped at max-requests ({max_requests}).",
                err=True,
            )
        return

    created = 0
    for setlist, songs in setlists_with_songs:
        title = build_playlist_title(setlist)
        click.echo(f" - {title}", err=True)
        uris, missing = resolve_uris(songs)

        if dry_run:
            continue

        if not uris:
            click.echo("   (no songs resolved on Spotify; skipping)", err=True)
            continue

        playlist = sp.user_playlist_create(
            spotify_user_id, title, public=public,
            description=setlist.get('url', 'Created from a setlist.fm attended setlist.')
        )
        add_tracks(playlist['id'], uris)
        created += 1
        note = f" ({missing} not found)" if missing else ""
        click.echo(f"   Added {len(uris)} tracks{note}: {playlist['external_urls']['spotify']}")

    if dry_run:
        click.echo("[dry-run] No playlists were created.", err=True)
    else:
        click.echo(f"Done. Created {created} playlist(s).", err=True)
        if limit_hit:
            click.echo(
                f"Note: results may be incomplete; stopped at max-requests ({max_requests}).",
                err=True,
            )

if __name__ == '__main__':
    cli(obj={})
