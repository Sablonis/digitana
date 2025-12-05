import pytest
from django.urls import reverse
from core.models import SystemConfig

@pytest.mark.django_db
class TestOnboardingWizard:
    def test_wizard_access_superuser(self, client, admin_user):
        client.force_login(admin_user)
        url = reverse('setup_wizard')
        response = client.get(url)
        assert response.status_code == 200
        assert b"Connect your Infomaniak Ecosystem" in response.content

    def test_wizard_access_denied_regular_user(self, client, user):
        client.force_login(user)
        url = reverse('setup_wizard')
        response = client.get(url)
        assert response.status_code == 403 # PermissionDenied

    def test_save_configuration(self, client, admin_user):
        client.force_login(admin_user)
        url = reverse('setup_wizard')
        data = {
            'client_id': 'NEW_CLIENT_ID',
            'client_secret': 'NEW_SECRET'
        }
        response = client.post(url, data)
        assert response.status_code == 302 # Redirect to dashboard
        
        config = SystemConfig.get_solo()
        assert config.infomaniak_client_id == 'NEW_CLIENT_ID'
        assert config.infomaniak_client_secret == 'NEW_SECRET'
