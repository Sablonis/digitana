from core.models import User, Circle
from integrations.models import IntegrationService, IntegrationInstance, UserIntegrationIdentity

# Get existing objects
u = User.objects.get(username="admin")
c = Circle.objects.first()

# Create Service
service = IntegrationService.objects.create(
    name="Test Storage",
    provider_type=IntegrationService.ProviderType.STORAGE,
    base_url="https://api.example.com",
    auth_method=IntegrationService.AuthMethod.OAUTH2
)
print(f"Created Service: {service}")

# Create Instance
instance = IntegrationInstance.objects.create(
    service=service,
    name="General Circle Storage",
    circle=c,
    configuration={"folder_id": "12345"}
)
print(f"Created Instance: {instance}")

# Create Identity
identity = UserIntegrationIdentity.objects.create(
    user=u,
    service=service,
    external_user_id="ext_user_123",
    credentials={"access_token": "secret"}
)
print(f"Created Identity: {identity}")

# Verify Relations
print(f"Circle Instances: {c.integration_instances.count()}")
print(f"User Identities: {u.integration_identities.count()}")
