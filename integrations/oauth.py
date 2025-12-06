import requests
from django.conf import settings
from urllib.parse import urlencode

# Infomaniak OAuth2 Endpoints
AUTH_URL = "https://login.infomaniak.com/oauth2/authorize"
TOKEN_URL = "https://login.infomaniak.com/oauth2/token"

def get_auth_url(client_id: str, redirect_uri: str, state: str, scopes: list = None) -> str:
    """
    Generates the Infomaniak OAuth2 authorization URL.
    """
    scope_str = " ".join(scopes) if scopes else "kdrive openid profile email"
    params = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": scope_str,
        "state": state,
    }
    return f"{AUTH_URL}?{urlencode(params)}"

def exchange_code(client_id: str, client_secret: str, redirect_uri: str, code: str) -> dict:
    """
    Exchanges the authorization code for an access token.
    """
    data = {
        "client_id": client_id,
        "client_secret": client_secret,
        "redirect_uri": redirect_uri,
        "grant_type": "authorization_code",
        "code": code,
    }
    response = requests.post(TOKEN_URL, data=data)
    response.raise_for_status()
    return response.json()
