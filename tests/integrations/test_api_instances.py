import pytest
from rest_framework.test import APIClient
from django.urls import reverse
from integrations.models import IntegrationService, IntegrationInstance
from core.models import Circle

@pytest.mark.django_db
class TestIntegrationInstanceAPI:
    def test_create_instance_with_api_key(self, user):
        client = APIClient()
        client.force_authenticate(user=user)
        
        # Setup Service and Circle
        service = IntegrationService.objects.create(
            name="Infomaniak (API Key)",
            provider_type="STORAGE",
            base_url="https://api.infomaniak.com",
            auth_method="API_KEY"
        )
        circle = Circle.objects.create(name="Test Circle")
        
        url = reverse('integrationinstance-list') # Standard Router name
        data = {
            "service_id": str(service.id),
            "name": "My API Key Drive",
            "circle": str(circle.id),
            "configuration": {
                "api_key": "secret-123"
            }
        }
        
        response = client.post(url, data, format='json')
        
        assert response.status_code == 201
        instance = IntegrationInstance.objects.get(id=response.data['id'])
        assert instance.name == "My API Key Drive"
        assert instance.configuration['api_key'] == "secret-123"
        assert instance.service == service
