import requests
import structlog
from typing import Optional, List, Dict, Any

logger = structlog.get_logger(__name__)

class InfomaniakClientError(Exception):
    """Base exception for Infomaniak client errors."""
    pass

class InfomaniakAPIError(InfomaniakClientError):
    """API returned an error."""
    pass

class InfomaniakClient:
    """
    Client for interacting with Infomaniak APIs (kChat, kMeet, etc.).
    Based on the OpenAPI definition.
    """
    
    BASE_URL = "https://api.infomaniak.com"

    def __init__(self, token: str):
        self.token = token
        self.session = requests.Session()
        self.session.headers.update({
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json"
        })

    def _request(self, method: str, endpoint: str, **kwargs) -> Dict[str, Any]:
        url = f"{self.BASE_URL}{endpoint}"
        try:
            response = self.session.request(method, url, **kwargs)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.HTTPError as e:
            logger.error("Infomaniak API error", url=url, status=e.response.status_code, response=e.response.text)
            raise InfomaniakAPIError(f"API request failed: {e}")
        except Exception as e:
            logger.error("Infomaniak client error", url=url, error=str(e))
            raise InfomaniakClientError(f"Request failed: {e}")

    # --- kChat (Mattermost-compatible) ---

    def get_teams(self) -> List[Dict[str, Any]]:
        """Get all teams the user belongs to."""
        # Endpoint: /api/v4/teams
        return self._request("GET", "/api/v4/teams")

    def get_channels(self, team_id: str) -> List[Dict[str, Any]]:
        """Get public channels for a team."""
        # Endpoint: /api/v4/teams/{team_id}/channels
        return self._request("GET", f"/api/v4/teams/{team_id}/channels")

    def create_channel(self, team_id: str, name: str, display_name: str, type: str = "O") -> Dict[str, Any]:
        """Create a new channel."""
        # Endpoint: /api/v4/channels
        data = {
            "team_id": team_id,
            "name": name,
            "display_name": display_name,
            "type": type # 'O' for Open, 'P' for Private
        }
        return self._request("POST", "/api/v4/channels", json=data)

    def post_message(self, channel_id: str, message: str) -> Dict[str, Any]:
        """Post a message to a channel."""
        # Endpoint: /api/v4/posts
        data = {
            "channel_id": channel_id,
            "message": message
        }
        return self._request("POST", "/api/v4/posts", json=data)

    # --- kMeet ---

    def create_meeting(self, subject: str = "Meeting") -> Dict[str, Any]:
        """
        Create a kMeet room.
        Note: The OpenAPI spec showed /1/kmeet/rooms
        """
        # Endpoint: /1/kmeet/rooms
        # Based on spec: "If you just want to create a room you don't need any API calls... 
        # but this endpoint allows you to plan a conference."
        data = {
            "subject": subject,
            # Add other defaults if needed
        }
        return self._request("POST", "/1/kmeet/rooms", json=data)
