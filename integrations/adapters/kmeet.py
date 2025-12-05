from typing import Dict, Any
import structlog
from integrations.adapters.base import BaseMeetingProvider
from integrations.clients.infomaniak import InfomaniakClient
from integrations.models import IntegrationInstance, UserIntegrationIdentity

logger = structlog.get_logger(__name__)

class KMeetProvider(BaseMeetingProvider):
    """
    Adapter for Infomaniak kMeet.
    """
    def __init__(self, instance: IntegrationInstance):
        self.instance = instance
        self.client = self._get_client()

    def _get_client(self) -> InfomaniakClient:
        # MVP: Use first available identity
        identity = UserIntegrationIdentity.objects.filter(service=self.instance.service).first()
        if not identity:
            raise Exception("No connected user found for kMeet.")
        
        token = identity.credentials.get('access_token')
        if not token:
             raise Exception("No access token found.")
             
        return InfomaniakClient(token)

    def create_meeting(self, subject: str) -> Dict[str, Any]:
        return self.client.create_meeting(subject=subject)
