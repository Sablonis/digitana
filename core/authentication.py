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
        """
        Retrieves user information from Infomaniak OIDC endpoint.
        """
        try:
            # Standard OIDC userinfo endpoint
            response = requests.get(
                "https://login.infomaniak.com/oauth2/userinfo",
                headers={"Authorization": f"Bearer {access_token}"}
            )
            if response.status_code == 200:
                data = response.json()
                # OIDC standard claims: sub is unique ID, email is email
                return {
                    "id": data.get("sub"),
                    "email": data.get("email"),
                    "first_name": data.get("given_name", ""),
                    "last_name": data.get("family_name", "")
                }
            else:
                # Log error in production
                pass
        except Exception:
            pass
            
        return None
