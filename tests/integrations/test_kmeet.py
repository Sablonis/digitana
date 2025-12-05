import pytest
from unittest.mock import MagicMock
from integrations.adapters.kmeet import KMeetProvider
from integrations.models import IntegrationService

class TestKMeetProvider:
    def test_create_meeting(self, mocker):
        mock_instance = MagicMock()
        
        # Mock DB call
        mock_identity = MagicMock()
        mock_identity.credentials = {'access_token': 'fake-token'}
        mocker.patch('integrations.adapters.kmeet.UserIntegrationIdentity.objects.filter', return_value=MagicMock(first=lambda: mock_identity))

        # Mock Client
        mock_client = MagicMock()
        mock_client.create_meeting.return_value = {'id': 'room1', 'url': 'https://kmeet.infomaniak.com/room1'}
        mocker.patch('integrations.adapters.kmeet.InfomaniakClient', return_value=mock_client)

        provider = KMeetProvider(mock_instance)
        resp = provider.create_meeting('Test Meeting')
        
        assert resp['id'] == 'room1'
        mock_client.create_meeting.assert_called_with(subject='Test Meeting')
