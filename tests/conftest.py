import pytest
from unittest.mock import MagicMock

@pytest.fixture
def mock_requests(mocker):
    return mocker.patch('integrations.adapters.infomaniak.requests')

@pytest.fixture
def mock_instance():
    instance = MagicMock()
    instance.id = 'test-instance-id'
    instance.circle.id = 'test-circle-id'
    instance.service.auth_method = 'api_key'
    instance.service.credentials = {'api_key': 'test-api-key'}
    instance.configuration = {}
    return instance

@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(username='testuser', password='password')
