import unittest
import json
from unittest.mock import patch, Mock
from click.testing import CliRunner
import requests

# Import the cli function from your script
import setlistfm_cli

class SetlistFMCliTests(unittest.TestCase):

    def setUp(self):
        """Set up the test runner to capture stderr separately.

        click >= 8.2 always captures stderr separately and removed the
        ``mix_stderr`` argument, so fall back to the no-arg constructor there.
        """
        try:
            self.runner = CliRunner(mix_stderr=False)
        except TypeError:
            self.runner = CliRunner()

    @patch('setlistfm_cli.requests.get')
    def test_search_artists_success(self, mock_get):
        """Test the 'search artists' command with a successful API response."""
        # Mock the API response
        mock_response = Mock()
        expected_json = {
            "artists": {
                "artist": [
                    {
                        "mbid": "65f4f0c5-ef9e-490c-aee3-909e7ae6b2ab",
                        "name": "Metallica",
                        "sortName": "Metallica",
                        "url": "https://www.setlist.fm/setlists/metallica-3bd6a4b8.html"
                    }
                ], "total": 1, "page": 1, "itemsPerPage": 20
            }
        }
        mock_response.status_code = 200
        mock_response.json.return_value = expected_json
        mock_get.return_value = mock_response

        # Invoke the CLI command
        result = self.runner.invoke(
            setlistfm_cli.cli,
            ['--api-key', 'fakekey', 'search', 'artists', '--artist-name', 'Metallica']
        )
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(json.loads(result.output), expected_json)
        mock_get.assert_called_once_with(
            'https://api.setlist.fm/rest/1.0/search/artists',
            headers={'x-api-key': 'fakekey', 'Accept': 'application/json'},
            params={'artistName': 'Metallica'}
        )

    @patch('setlistfm_cli.requests.get')
    def test_get_artist_success(self, mock_get):
        """Test the 'artist' command with a successful API response."""
        mock_response = Mock()
        mbid = "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d"
        expected_json = {"mbid": mbid, "name": "The Beatles"}
        mock_response.json.return_value = expected_json
        mock_response.status_code = 200
        mock_get.return_value = mock_response
        result = self.runner.invoke(setlistfm_cli.cli, ['--api-key', 'fakekey', 'artist', mbid])
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(json.loads(result.output), expected_json)

    def test_no_api_key(self):
        """Test that the CLI fails gracefully if no API key is provided."""
        result = self.runner.invoke(setlistfm_cli.cli, ['search', 'artists'])
        self.assertNotEqual(result.exit_code, 0)
        self.assertIn("API key not found", result.stderr)

    @patch('setlistfm_cli.requests.get')
    def test_api_http_error(self, mock_get):
        """Test the CLI's handling of an HTTP error from the API."""
        mock_response = Mock()
        mock_response.status_code = 404
        mock_response.reason = "Not Found"
        mock_response.text = "The requested resource was not found."
        mock_response.raise_for_status.side_effect = requests.exceptions.HTTPError(response=mock_response)
        mock_get.return_value = mock_response
        result = self.runner.invoke(setlistfm_cli.cli, ['--api-key', 'fakekey', 'artist', 'invalid-mbid'])
        self.assertEqual(result.exit_code, 0)
        self.assertIn("Error: 404 Not Found", result.stderr)

    @patch('setlistfm_cli.requests.get')
    def test_user_attended_command(self, mock_get):
        """Test the 'user-attended' command generates HTML."""
        # Mock the API response for attended setlists
        mock_response = Mock()
        expected_json = {
            "setlist": [
                {
                    "artist": {"name": "Test Band"},
                    "venue": {"name": "The Test Venue", "city": {"name": "Testville", "country": {"name": "Testland"}}},
                    "eventDate": "21-09-2025"
                }
            ],
            "total": 1,
            "page": 1,
            "itemsPerPage": 20
        }
        mock_response.status_code = 200
        mock_response.json.return_value = expected_json
        mock_get.return_value = mock_response

        # Invoke the command
        result = self.runner.invoke(
            setlistfm_cli.cli,
            ['--api-key', 'fakekey', 'user-attended', 'testuser']
        )
        
        # Assertions
        self.assertEqual(result.exit_code, 0)
        self.assertIn("<!DOCTYPE html>", result.output)
        self.assertIn("Concert History for testuser", result.output)
        self.assertIn("Test Band", result.output)
        self.assertIn("21-09-2025", result.output)

    def test_extract_songs(self):
        """Songs are extracted across sets; covers use the original artist and blanks are skipped."""
        setlist = {
            "artist": {"name": "Test Band"},
            "sets": {"set": [
                {"song": [
                    {"name": "Opener"},
                    {"name": ""},  # spacer / segue -> skipped
                    {"name": "A Cover", "cover": {"name": "Original Artist"}},
                ]},
                {"encore": 1, "song": [
                    {"name": "Encore Song"},
                ]},
            ]},
        }
        songs = setlistfm_cli.extract_songs(setlist)
        self.assertEqual(
            songs,
            [
                {"name": "Opener", "artist": "Test Band"},
                {"name": "A Cover", "artist": "Original Artist"},
                {"name": "Encore Song", "artist": "Test Band"},
            ],
        )

    def test_extract_songs_empty(self):
        """A setlist with no sets yields no songs."""
        self.assertEqual(setlistfm_cli.extract_songs({"artist": {"name": "X"}}), [])

    def test_build_playlist_title(self):
        """Playlist titles combine artist, venue/city and date."""
        setlist = {
            "artist": {"name": "Test Band"},
            "venue": {"name": "The Venue", "city": {"name": "Testville"}},
            "eventDate": "21-09-2025",
        }
        self.assertEqual(
            setlistfm_cli.build_playlist_title(setlist),
            "Test Band @ The Venue, Testville (21-09-2025)",
        )

    @patch('setlistfm_cli.get_spotify_client')
    @patch('setlistfm_cli.requests.get')
    def test_create_playlists(self, mock_get, mock_get_spotify):
        """create-playlists builds one Spotify playlist per attended setlist with songs."""
        # setlist.fm returns one attended concert with two songs.
        api_json = {
            "setlist": [
                {
                    "artist": {"name": "Test Band"},
                    "venue": {"name": "The Venue", "city": {"name": "Testville"}},
                    "eventDate": "21-09-2025",
                    "url": "https://www.setlist.fm/setlist/test.html",
                    "sets": {"set": [
                        {"song": [{"name": "Song One"}, {"name": "Song Two"}]}
                    ]},
                }
            ],
            "total": 1, "page": 1, "itemsPerPage": 20,
        }
        api_response = Mock()
        api_response.status_code = 200
        api_response.json.return_value = api_json
        mock_get.return_value = api_response

        # Mocked Spotify client.
        sp = Mock()
        sp.current_user.return_value = {"id": "spotify_user"}
        sp.search.return_value = {"tracks": {"items": [{"uri": "spotify:track:abc"}]}}
        sp.user_playlist_create.return_value = {
            "id": "playlist123",
            "external_urls": {"spotify": "https://open.spotify.com/playlist/playlist123"},
        }
        mock_get_spotify.return_value = sp

        result = self.runner.invoke(
            setlistfm_cli.cli,
            ['--api-key', 'fakekey', 'create-playlists', 'testuser',
             '--spotify-client-id', 'id', '--spotify-client-secret', 'secret'],
        )

        self.assertEqual(result.exit_code, 0, msg=result.output + (result.stderr or ''))
        sp.user_playlist_create.assert_called_once()
        _, kwargs = sp.user_playlist_create.call_args
        args = sp.user_playlist_create.call_args[0]
        self.assertEqual(args[0], "spotify_user")
        self.assertEqual(args[1], "Test Band @ The Venue, Testville (21-09-2025)")
        sp.playlist_add_items.assert_called_once_with("playlist123", ["spotify:track:abc"])
        self.assertIn("https://open.spotify.com/playlist/playlist123", result.output)

    @patch('setlistfm_cli.requests.get')
    def test_create_playlists_dry_run(self, mock_get):
        """--dry-run resolves nothing on Spotify and creates no playlists."""
        api_json = {
            "setlist": [
                {
                    "artist": {"name": "Test Band"},
                    "venue": {"name": "The Venue", "city": {"name": "Testville"}},
                    "eventDate": "21-09-2025",
                    "sets": {"set": [{"song": [{"name": "Song One"}]}]},
                }
            ],
            "total": 1, "page": 1, "itemsPerPage": 20,
        }
        api_response = Mock()
        api_response.status_code = 200
        api_response.json.return_value = api_json
        mock_get.return_value = api_response

        result = self.runner.invoke(
            setlistfm_cli.cli,
            ['--api-key', 'fakekey', 'create-playlists', 'testuser', '--dry-run'],
        )
        self.assertEqual(result.exit_code, 0, msg=(result.stderr or ''))
        self.assertIn("dry-run", result.stderr)

if __name__ == '__main__':
    unittest.main()
