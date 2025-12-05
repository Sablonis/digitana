import pytest
from django.urls import reverse
from django.conf import settings
from unittest.mock import patch, MagicMock

@pytest.mark.django_db
class TestInfomaniakSSO:
    def test_login_redirect(self, client):
        url = reverse('infomaniak_login')
        response = client.get(url)
        assert response.status_code == 302
        assert "login.infomaniak.com/oauth2/authorize" in response.url
        assert "client_id=DEMO_CLIENT_ID" in response.url

    @patch('core.views_auth.requests.post')
    @patch('core.authentication.InfomaniakBackend._get_user_info')
    def test_callback_success(self, mock_get_user_info, mock_post, client):
        # Mock Token Response
        mock_post.return_value.status_code = 200
        mock_post.return_value.json.return_value = {
            "access_token": "valid_token",
            "refresh_token": "refresh",
            "expires_in": 3600
        }
        
        # Mock User Info
        mock_get_user_info.return_value = {
            "id": 123,
            "email": "test@infomaniak.com"
        }
        
        url = reverse('infomaniak_callback')
        response = client.get(url, {'code': 'valid_code'})
        
        # Should redirect to LOGIN_REDIRECT_URL ('/')
        assert response.status_code == 302
        assert response.url == '/'
        
        # Check if user was created and logged in
        from django.contrib.auth import get_user_model
        User = get_user_model()
        user = User.objects.get(email="test@infomaniak.com")
        assert user.username == "test"
        
        # Verify session
        assert str(client.session['_auth_user_id']) == str(user.pk)

    def test_callback_error(self, client):
        url = reverse('infomaniak_callback')
        response = client.get(url, {'error': 'access_denied'})
        assert response.status_code == 400
        assert b"Login Failed" in response.content
