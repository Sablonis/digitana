from typing import List, Dict, Any
import structlog
from integrations.adapters.base import BaseChatProvider
from integrations.clients.infomaniak import InfomaniakClient
from integrations.models import IntegrationInstance, UserIntegrationIdentity, IntegrationService

logger = structlog.get_logger(__name__)

class KChatProvider(BaseChatProvider):
    """
    Adapter for Infomaniak kChat.
    """
    def __init__(self, instance: IntegrationInstance):
        self.instance = instance
        self.client = self._get_client()

    def _get_client(self) -> InfomaniakClient:
        # Get credentials (similar to StorageProvider)
        # MVP: Use first available identity
        identity = UserIntegrationIdentity.objects.filter(service=self.instance.service).first()
        if not identity:
            raise Exception("No connected user found for kChat.")
        
        token = identity.credentials.get('access_token')
        if not token:
             raise Exception("No access token found.")
             
        return InfomaniakClient(token)

    def get_teams(self) -> List[Dict[str, Any]]:
        return self.client.get_teams()

    def get_channels(self, team_id: str) -> List[Dict[str, Any]]:
        return self.client.get_channels(team_id)

    def post_message(self, channel_id: str, message: str) -> Dict[str, Any]:
        return self.client.post_message(channel_id, message)
