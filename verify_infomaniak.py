import os
import django
from unittest.mock import MagicMock, patch

# Setup Django
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'digitana.settings')
django.setup()

from integrations.models import IntegrationService, IntegrationInstance, UserIntegrationIdentity
from core.models import User, Circle
from integrations.adapters.factory import get_storage_adapter

def run_verification():
    print("Setting up mocks...")
    
    # Mock Models
    user = MagicMock(username="admin")
    circle = MagicMock()
    
    service = MagicMock()
    service.name = "Infomaniak Drive"
    service.provider_type = "STORAGE" # String or Enum
    service.base_url = "https://api.infomaniak.com"
    service.auth_method = "OAUTH2" # String or Enum
    
    instance = MagicMock()
    instance.service = service
    instance.name = "Infomaniak Team Folder"
    instance.circle = circle
    instance.configuration = {} # Empty config to test discovery
    
    # Mock Identity Lookup
    # The adapter uses UserIntegrationIdentity.objects.filter(...).first()
    # We need to mock this chain.
    
    # However, we can't easily mock the internal ORM calls inside the adapter 
    # unless we patch the class where it's used.
    # The adapter is in `integrations.adapters.infomaniak`.
    
    with patch('integrations.adapters.infomaniak.UserIntegrationIdentity') as MockIdentityModel:
        # Setup the mock identity return
        mock_identity = MagicMock()
        mock_identity.credentials = {'access_token': 'fake_token_123'}
        
        # Mock objects.filter().first()
        MockIdentityModel.objects.filter.return_value.first.return_value = mock_identity
        
        print(f"Testing with Mock Instance: {instance.name}")
        
        # Instantiate Adapter directly to avoid factory DB checks if any
        from integrations.adapters.infomaniak import InfomaniakStorageProvider
        adapter = InfomaniakStorageProvider(instance)
        print(f"Adapter Class: {adapter.__class__.__name__}")

        # 2. Mock Requests
        with patch('integrations.adapters.infomaniak.requests') as mock_requests:
            # Mock Drive Discovery
            mock_drive_resp = MagicMock()
            mock_drive_resp.json.return_value = {
                'result': 'success',
                'data': [{'id': 999, 'label': 'My kDrive'}]
            }
            mock_drive_resp.status_code = 200
            
            # Mock List Files
            mock_list_resp = MagicMock()
            mock_list_resp.json.return_value = {
                'result': 'success',
                'data': [
                    {'id': 101, 'name': 'Test.txt', 'mime_type': 'text/plain', 'size': 123, 'updated_at': 1678888888}
                ]
            }
            mock_list_resp.status_code = 200

            # Mock Upload
            mock_upload_resp = MagicMock()
            mock_upload_resp.json.return_value = {
                'result': 'success',
                'data': {'id': 102, 'name': 'New.txt', 'mime_type': 'text/plain', 'size': 456}
            }
            mock_upload_resp.status_code = 200

            # Mock Delete
            mock_delete_resp = MagicMock()
            mock_delete_resp.status_code = 204

            def side_effect(*args, **kwargs):
                url = args[0]
                if '/2/drive' in url and 'files' not in url:
                    return mock_drive_resp
                if '/files' in url and 'upload' not in url and kwargs.get('method') != 'DELETE': # List
                    return mock_list_resp
                if '/upload' in url:
                    return mock_upload_resp
                if kwargs.get('method') == 'DELETE' or (len(args) > 0 and args[0].endswith('/files/101')): # Delete
                     return mock_delete_resp
                return MagicMock(status_code=404)

            mock_requests.get.side_effect = side_effect
            mock_requests.post.side_effect = side_effect
            mock_requests.delete.side_effect = side_effect

            # 3. Test List Files (Triggers Discovery)
            print("\n--- Testing List Files ---")
            files = adapter.list_files()
            print(f"Found {len(files)} files.")
            if len(files) > 0:
                print(f"First file: {files[0].name} (ID: {files[0].id})")
                assert files[0].name == 'Test.txt'
                assert files[0].id == '101'

            # 4. Test Upload
            print("\n--- Testing Upload ---")
            new_file = adapter.upload_file("New.txt", b"content")
            print(f"Uploaded file: {new_file.name} (ID: {new_file.id})")
            assert new_file.name == 'New.txt'

            # 5. Test Delete
            print("\n--- Testing Delete ---")
            success = adapter.delete_file("101")
            print(f"Delete success: {success}")
            assert success is True

if __name__ == "__main__":
    run_verification()
