import pytest
from unittest.mock import MagicMock
from integrations.adapters.infomaniak import InfomaniakStorageProvider, InfomaniakAPIError
from integrations.models import IntegrationService

class TestInfomaniakStorageProvider:
    def test_get_access_token_api_key(self, mock_instance):
        provider = InfomaniakStorageProvider(mock_instance)
        token = provider._get_access_token()
        assert token == 'test-api-key'

    def test_get_drive_id_discovery_success(self, mock_instance, mock_requests):
        provider = InfomaniakStorageProvider(mock_instance)
        
        mock_response = MagicMock()
        mock_response.json.return_value = {
            'result': 'success',
            'data': [{'id': 12345}]
        }
        mock_requests.get.return_value = mock_response
        
        drive_id = provider._get_drive_id('token')
        assert drive_id == '12345'

    def test_list_files_success(self, mock_instance, mock_requests):
        provider = InfomaniakStorageProvider(mock_instance)
        
        # Mock drive discovery
        mock_drive_resp = MagicMock()
        mock_drive_resp.json.return_value = {'result': 'success', 'data': [{'id': 12345}]}
        
        # Mock file listing
        mock_files_resp = MagicMock()
        mock_files_resp.json.return_value = {
            'result': 'success',
            'data': [
                {'id': 1, 'name': 'test.txt', 'mime_type': 'text/plain', 'size': 100, 'updated_at': 1600000000}
            ]
        }
        
        mock_requests.get.side_effect = [mock_drive_resp, mock_files_resp]
        
        files = provider.list_files()
        assert len(files) == 1
        assert files[0].name == 'test.txt'

    def test_upload_file_success(self, mock_instance, mock_requests):
        provider = InfomaniakStorageProvider(mock_instance)
        
        # Mock drive discovery
        mock_drive_resp = MagicMock()
        mock_drive_resp.json.return_value = {'result': 'success', 'data': [{'id': 12345}]}
        
        # Mock upload response
        mock_upload_resp = MagicMock()
        mock_upload_resp.json.return_value = {
            'result': 'success',
            'data': {'id': 99, 'name': 'new.txt', 'mime_type': 'text/plain', 'size': 50}
        }
        
        mock_requests.get.return_value = mock_drive_resp
        mock_requests.post.return_value = mock_upload_resp
        
        file = provider.upload_file('new.txt', b'content')
        assert file.name == 'new.txt'
