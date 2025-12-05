import pytest
from unittest.mock import MagicMock
from django.urls import reverse
from frontend.api_views import CreateMeetingView
from integrations.models import IntegrationInstance, IntegrationService

@pytest.mark.django_db
class TestCreateMeetingView:
    def test_create_meeting_success(self, client, user, mocker):
        client.force_login(user)
        
        # Mock IntegrationInstance
        mock_instance = MagicMock()
        mocker.patch('integrations.models.IntegrationInstance.objects.filter', return_value=MagicMock(first=lambda: mock_instance))
        
        # Mock KMeetProvider
        mock_provider = MagicMock()
        mock_provider.create_meeting.return_value = {'id': 'room1', 'url': 'https://meet.infomaniak.com/room1'}
        mocker.patch('frontend.api_views.KMeetProvider', return_value=mock_provider)
        
        url = reverse('create-meeting')
        response = client.post(url)
        
        assert response.status_code == 200
        assert response.json()['url'] == 'https://meet.infomaniak.com/room1'
        mock_provider.create_meeting.assert_called_once()

    def test_create_meeting_no_instance(self, client, user, mocker):
        client.force_login(user)
        
        # Mock no instance found
        mocker.patch('integrations.models.IntegrationInstance.objects.filter', return_value=MagicMock(first=lambda: None))
        
        url = reverse('create-meeting')
        response = client.post(url)
        
        assert response.status_code == 400
        assert response.json()['error'] == 'No kMeet instance connected'
