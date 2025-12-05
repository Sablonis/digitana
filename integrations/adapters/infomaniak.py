import structlog
import requests
from typing import List, Optional, Dict, Any
from datetime import datetime
from .base import BaseStorageProvider, RemoteFile
from integrations.models import UserIntegrationIdentity, IntegrationService

logger = structlog.get_logger(__name__)

class InfomaniakProviderError(Exception):
    """Base exception for Infomaniak provider errors."""
    pass

class InfomaniakAuthError(InfomaniakProviderError):
    """Authentication failed."""
    pass

class InfomaniakAPIError(InfomaniakProviderError):
    """API returned an error."""
    pass

class InfomaniakStorageProvider(BaseStorageProvider):
    """
    Adapter for Infomaniak kDrive.
    """
    def __init__(self, instance):
        self.instance = instance
        self.log = logger.bind(
            service="infomaniak",
            instance_id=str(instance.id),
            circle_id=str(instance.circle.id) if instance.circle else None
        )

    def _get_access_token(self) -> str:
        """
        Retrieve access token based on authentication method.
        """
        # 1. Check for API Key (Service Account / Simple Mode)
        if self.instance.service.auth_method == IntegrationService.AuthMethod.API_KEY:
            token = self.instance.service.credentials.get('api_key')
            if not token:
                 # Fallback to instance config if not in service creds
                token = self.instance.configuration.get('api_key')
            
            if not token:
                self.log.error("api_key_missing")
                raise InfomaniakAuthError("Service configured for API Key but no key found in credentials.")
            return token

        # 2. OAuth2 Flow (User Identity)
        # MVP: Get the first identity for this service. 
        # In production, we should pass the 'request.user' to the factory/adapter.
        identity = UserIntegrationIdentity.objects.filter(service=self.instance.service).first()
        if not identity:
            self.log.error("no_user_identity_found")
            raise InfomaniakAuthError("No connected user found for this service.")
        
        return identity.credentials.get('access_token')

    def _get_drive_id(self, token: str) -> str:
        """
        Get the Drive ID from configuration or discover it from the API.
        """
        drive_id = self.instance.configuration.get('drive_id')
        if drive_id:
            return drive_id
        
        # Discovery: Get the first available drive
        # Endpoint: GET /2/drive
        headers = {"Authorization": f"Bearer {token}"}
        try:
            resp = requests.get("https://api.infomaniak.com/2/drive", headers=headers)
            resp.raise_for_status()
            data = resp.json()
            
            if data.get('result') == 'success' and data.get('data'):
                # Pick the first one
                first_drive = data['data'][0]
                found_id = str(first_drive['id'])
                self.log.info("drive_discovered", drive_id=found_id)
                return found_id
            else:
                self.log.error("drive_discovery_failed", response=data)
                
        except Exception as e:
            self.log.exception("drive_discovery_error")
            raise InfomaniakAPIError(f"Error discovering drive: {e}") from e
        
        raise InfomaniakAPIError("Drive ID not configured and could not be discovered.")

    def list_files(self, folder_id: Optional[str] = None) -> List[RemoteFile]:
        try:
            token = self._get_access_token()
            drive_id = self._get_drive_id(token)
            
            if not folder_id:
                folder_id = '1' # Root folder usually has ID 1 in kDrive

            headers = {"Authorization": f"Bearer {token}"}
            # Endpoint: GET /3/drive/{drive_id}/files/{folder_id}/files
            url = f"https://api.infomaniak.com/3/drive/{drive_id}/files/{folder_id}/files"
            
            self.log.debug("listing_files", folder_id=folder_id)
            
            resp = requests.get(url, headers=headers)
            resp.raise_for_status()
            data = resp.json()
            
            files = []
            if data.get('result') == 'success':
                for item in data.get('data', []):
                    files.append(RemoteFile(
                        id=str(item.get('id')),
                        name=item.get('name'),
                        mime_type=item.get('mime_type', 'application/octet-stream'),
                        size=item.get('size', 0),
                        modified_at=datetime.fromtimestamp(item.get('updated_at', 0)),
                        webview_link=item.get('temporary_url')
                    ))
                self.log.info("files_listed", count=len(files))
                return files
            else:
                self.log.error("list_files_failed", response=data)
                raise InfomaniakAPIError(f"Infomaniak API error: {data.get('error')}")

        except Exception as e:
            self.log.exception("list_files_error")
            raise InfomaniakAPIError(f"Error listing files: {e}") from e

    def upload_file(self, name: str, content: bytes, folder_id: Optional[str] = None) -> RemoteFile:
        try:
            token = self._get_access_token()
            drive_id = self._get_drive_id(token)
            
            if not folder_id:
                folder_id = '1' # Default to root

            headers = {"Authorization": f"Bearer {token}"}
            # Endpoint: POST /3/drive/{drive_id}/upload
            url = f"https://api.infomaniak.com/3/drive/{drive_id}/upload"
            
            # Prepare multipart upload
            files = {
                'file': (name, content)
            }
            data = {
                'directory_id': folder_id,
                'total_size': len(content)
            }

            self.log.info("uploading_file", name=name, size=len(content))

            resp = requests.post(url, headers=headers, files=files, data=data)
            resp.raise_for_status()
            res_data = resp.json()
            
            if res_data.get('result') == 'success':
                item = res_data.get('data')
                return RemoteFile(
                    id=str(item.get('id')),
                    name=item.get('name'),
                    mime_type=item.get('mime_type'),
                    size=item.get('size'),
                    modified_at=datetime.now(), 
                    webview_link=None
                )
            else:
                self.log.error("upload_failed", response=res_data)
                raise InfomaniakAPIError(f"Upload failed: {res_data}")

        except Exception as e:
            self.log.exception("upload_error")
            raise InfomaniakAPIError(f"Error uploading file: {e}") from e

    def delete_file(self, file_id: str) -> bool:
        try:
            token = self._get_access_token()
            drive_id = self._get_drive_id(token)
            
            headers = {"Authorization": f"Bearer {token}"}
            # Endpoint: DELETE /2/drive/{drive_id}/files/{file_id}
            url = f"https://api.infomaniak.com/2/drive/{drive_id}/files/{file_id}"
            
            self.log.info("deleting_file", file_id=file_id)
            
            resp = requests.delete(url, headers=headers)
            
            if resp.status_code in [200, 204]:
                return True
            
            try:
                data = resp.json()
                if data.get('result') == 'success':
                    return True
            except:
                pass
                
            self.log.error("delete_failed", status_code=resp.status_code, response=resp.text)
            return False

        except Exception as e:
            self.log.exception("delete_error")
            return False
