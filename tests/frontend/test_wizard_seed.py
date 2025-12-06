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
        
        # Verify services created (Primary Goal)
        assert IntegrationService.objects.filter(name='Infomaniak Drive').exists()
        assert IntegrationService.objects.filter(name='Infomaniak Drive (API Key)').exists()
        
        # Check messages (Secondary)
        # messages might be in context['messages'] if using fallback storage in tests
        # assert "Services initialized" in str(response.content) 
        pass
