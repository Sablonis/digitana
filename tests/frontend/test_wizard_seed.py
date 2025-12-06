import pytest
from django.urls import reverse
from integrations.models import IntegrationService

@pytest.mark.django_db
class TestSetupWizardSeed:
    def test_seed_services_button(self, client, admin_user):
        # Setup admin user login (SetupWizard requires UserPassesTestMixin aka superuser)
        # Assuming admin_user fixture creates a superuser
        client.force_login(admin_user)
        
        url = reverse('setup_wizard')
        
        # Verify services don't exist yet
        assert IntegrationService.objects.count() == 0
        
        # Post the seed action
        response = client.post(url, {'seed_services': 'true'}, follow=True)
        
        assert response.status_code == 200
        # Check messages or content
        assert "Services initialized" in str(response.content) # Simple string check
        
        # Verify services created
        assert IntegrationService.objects.filter(name='Infomaniak Drive').exists()
        assert IntegrationService.objects.filter(name='Infomaniak Drive (API Key)').exists()
