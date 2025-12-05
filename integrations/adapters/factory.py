from integrations.models import IntegrationInstance, IntegrationService
from .base import BaseStorageProvider
from .dummy import DummyStorageProvider
from .infomaniak import InfomaniakStorageProvider

def get_storage_adapter(instance: IntegrationInstance) -> BaseStorageProvider:
    """
    Returns the appropriate storage adapter for the given instance.
    """
    # In a real app, we would check instance.service.provider_type
    # and maybe a specific 'provider_code' field.
    
    # For now, we return the Dummy provider for everything, 
    # or we could check the name.
    
    if "Infomaniak" in instance.service.name:
        return InfomaniakStorageProvider(instance)
        
    # Default to Dummy for this phase
    return DummyStorageProvider(instance.configuration)
