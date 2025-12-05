import pytest
from unittest.mock import MagicMock, patch
from django.urls import reverse
from integrations.models import IntegrationService, UserIntegrationIdentity

@pytest.mark.django_db
class TestOAuthFlow:
    @pytest.fixture
    def service(self):
        return IntegrationService.objects.create(
            name="Test Service",
            provider_type=IntegrationService.ProviderType.STORAGE,
            auth_method=IntegrationService.AuthMethod.OAUTH2,
            credentials={'client_id': 'test-id', 'client_secret': 'test-secret'},
            required_scopes=['kdrive']
        )

    def test_connect_service_redirect(self, client, user, service):
        client.force_login(user)
        url = reverse('integrations:connect', args=[service.id])
        response = client.get(url)
        
        assert response.status_code == 302
        assert "login.infomaniak.com/oauth2/authorize" in response.url
        assert "client_id=test-id" in response.url
        assert "scope=kdrive" in response.url
        assert client.session['oauth_service_id'] == str(service.id)

    def test_oauth_callback_success(self, client, user, service, mocker):
        client.force_login(user)
        
        # Set session
        session = client.session
        session['oauth_service_id'] = str(service.id)
        session.save()
        
        # Mock exchange_code
        mock_exchange = mocker.patch('integrations.views_auth.exchange_code')
        mock_exchange.return_value = {
            'access_token': 'new-token',
            'refresh_token': 'new-refresh',
            'user_id': 'ext-user-1'
        }
        
        url = reverse('integrations:callback')
        response = client.get(url, {'code': 'auth-code'})
        
        assert response.status_code == 302
        assert response.url == '/' # Redirects to dashboard (or root)
        
        # Verify Identity created
        identity = UserIntegrationIdentity.objects.get(user=user, service=service)
        assert identity.credentials['access_token'] == 'new-token'
        assert identity.external_user_id == 'ext-user-1'

    def test_oauth_callback_error(self, client, user):
        client.force_login(user)
        url = reverse('integrations:callback')
        response = client.get(url, {'error': 'access_denied'})
        
        assert response.status_code == 400
        assert b"OAuth Error: access_denied" in response.content
