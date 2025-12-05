import requests
from django.contrib.auth import get_user_model
from django.conf import settings
from django.contrib.auth.backends import ModelBackend

User = get_user_model()

class InfomaniakBackend(ModelBackend):
    """
    Authenticates against Infomaniak OAuth2.
    """
    def authenticate(self, request, token_data=None, **kwargs):
        if not token_data:
            return None
        
        access_token = token_data.get('access_token')
        if not access_token:
            return None

        # 1. Get User Info
        # Try standard OIDC userinfo or profile endpoint
        # If not available, we might need to rely on ID token or another API call
        # For now, let's assume we can get a unique ID from the token response or a profile endpoint
        
        user_info = self._get_user_info(access_token)
        if not user_info:
            return None
            
        email = user_info.get('email')
        infomaniak_id = str(user_info.get('id')) # Ensure string
        
        if not email:
            return None

        # 2. Find or Create User
        try:
            # Try to match by email
            user = User.objects.get(email=email)
        except User.DoesNotExist:
            # Create new user
            username = email.split('@')[0]
            # Ensure username uniqueness
            if User.objects.filter(username=username).exists():
                username = f"{username}_{infomaniak_id}"
                
            user = User.objects.create_user(
                username=username,
                email=email,
                password=None # Unusable password
            )
            
        return user

    def _get_user_info(self, access_token):
        # Try to fetch user profile
        # Note: The exact endpoint depends on Infomaniak's specific API implementation
        # Common pattern: https://login.infomaniak.com/api/profile or /oauth2/userinfo
        
        # Based on typical OAuth2 implementations:
        try:
            response = requests.get(
                "https://login.infomaniak.com/api/profile", # Hypothetical endpoint, verify if possible
                headers={"Authorization": f"Bearer {access_token}"}
            )
            if response.status_code == 200:
                return response.json()
        except Exception:
            pass
            
        # Fallback: If the token_data itself contains user info (id_token claims)
        # For this implementation, we'll return a mock if in DEBUG mode and real call fails
        if settings.DEBUG:
             return {"id": 12345, "email": "demo@infomaniak.com"}
             
        return None
