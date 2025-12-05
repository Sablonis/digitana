from django.shortcuts import redirect
from django.conf import settings
from django.contrib.auth import login
from django.http import HttpResponseBadRequest
from django.urls import reverse
from urllib.parse import urlencode
import requests
from .authentication import InfomaniakBackend

def infomaniak_login(request):
    """
    Redirects to Infomaniak OAuth2 authorization page.
    """
    # Fetch config from DB
    from core.models import SystemConfig
    sys_config = SystemConfig.get_solo()
    
    client_id = sys_config.infomaniak_client_id or settings.INFOMANIAK_AUTH['CLIENT_ID']
    redirect_uri = settings.INFOMANIAK_AUTH['REDIRECT_URI']
    
    params = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": "user", # Request user scope for profile info
        "state": "random_state_string", # Should be randomized for security
    }
    auth_url = f"https://login.infomaniak.com/oauth2/authorize?{urlencode(params)}"
    return redirect(auth_url)

def infomaniak_callback(request):
    """
    Handles OAuth2 callback, exchanges code, and logs user in.
    """
    code = request.GET.get('code')
    error = request.GET.get('error')
    
    if error:
        return HttpResponseBadRequest(f"Login Failed: {error}")
        
    if not code:
        return HttpResponseBadRequest("No code provided")
        
    # Fetch config from DB
    from core.models import SystemConfig
    sys_config = SystemConfig.get_solo()
    
    client_id = sys_config.infomaniak_client_id or settings.INFOMANIAK_AUTH['CLIENT_ID']
    client_secret = sys_config.infomaniak_client_secret or settings.INFOMANIAK_AUTH['CLIENT_SECRET']
    redirect_uri = settings.INFOMANIAK_AUTH['REDIRECT_URI']
    
    # Exchange code for token
    token_url = "https://login.infomaniak.com/oauth2/token"
    data = {
        "client_id": client_id,
        "client_secret": client_secret,
        "redirect_uri": redirect_uri,
        "grant_type": "authorization_code",
        "code": code,
    }
    
    try:
        response = requests.post(token_url, data=data)
        response.raise_for_status()
        token_data = response.json()
        
        # Authenticate
        backend = InfomaniakBackend()
        user = backend.authenticate(request, token_data=token_data)
        
        if user:
            login(request, user)
            return redirect(settings.LOGIN_REDIRECT_URL)
        else:
            return HttpResponseBadRequest("Authentication failed: Could not identify user.")
            
    except Exception as e:
        if settings.DEBUG:
             # In Demo mode, if request fails (e.g. invalid client_id), mock login
             backend = InfomaniakBackend()
             user = backend.authenticate(request, token_data={'access_token': 'mock'})
             if user:
                 login(request, user)
                 return redirect(settings.LOGIN_REDIRECT_URL)
                 
        return HttpResponseBadRequest(f"Token Exchange Failed: {str(e)}")
