import pytest
from unittest.mock import MagicMock
from integrations.adapters.kchat import KChatProvider
from integrations.models import IntegrationService

class TestKChatProvider:
    @pytest.fixture
    def mock_instance(self):
        instance = MagicMock()
        instance.service.auth_method = IntegrationService.AuthMethod.OAUTH2
        return instance

    @pytest.fixture
    def mock_identity(self, mock_instance):
        identity = MagicMock()
        identity.credentials = {'access_token': 'fake-token'}
        # Mock the UserIntegrationIdentity.objects.filter().first() chain
        # This is tricky with Django models in unit tests without DB.
        # We'll mock the _get_client method or the DB call in the test.
        return identity

    def test_get_teams(self, mock_instance, mocker):
        # Mock DB call
        mock_identity = MagicMock()
        mock_identity.credentials = {'access_token': 'fake-token'}
        mocker.patch('integrations.adapters.kchat.UserIntegrationIdentity.objects.filter', return_value=MagicMock(first=lambda: mock_identity))

        # Mock Client
        mock_client = MagicMock()
        mock_client.get_teams.return_value = [{'id': 'team1', 'name': 'Team 1'}]
        mocker.patch('integrations.adapters.kchat.InfomaniakClient', return_value=mock_client)

        provider = KChatProvider(mock_instance)
        teams = provider.get_teams()
        
        assert len(teams) == 1
        assert teams[0]['id'] == 'team1'
        mock_client.get_teams.assert_called_once()

    def test_post_message(self, mock_instance, mocker):
        # Mock DB call
        mock_identity = MagicMock()
        mock_identity.credentials = {'access_token': 'fake-token'}
        mocker.patch('integrations.adapters.kchat.UserIntegrationIdentity.objects.filter', return_value=MagicMock(first=lambda: mock_identity))

        # Mock Client
        mock_client = MagicMock()
        mock_client.post_message.return_value = {'id': 'msg1', 'message': 'Hello'}
        mocker.patch('integrations.adapters.kchat.InfomaniakClient', return_value=mock_client)

        provider = KChatProvider(mock_instance)
        resp = provider.post_message('chan1', 'Hello')
        
        assert resp['id'] == 'msg1'
        mock_client.post_message.assert_called_with('chan1', 'Hello')
