import os
import django
from rest_framework.test import APIRequestFactory
from integrations.models import IntegrationInstance
from integrations.views import IntegrationInstanceViewSet

# Setup Django
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'digitana.settings')
django.setup()

# Get the instance
instance = IntegrationInstance.objects.first()
print(f"Testing with Instance: {instance}")

# 1. Test Adapter Logic Directly
from integrations.adapters.factory import get_storage_adapter
adapter = get_storage_adapter(instance)
files = adapter.list_files()
print(f"Adapter returned {len(files)} files.")
print(f"First file: {files[0].name}")

# 2. Test API Endpoint
from rest_framework.test import force_authenticate
from core.models import User

user = User.objects.get(username="admin")
factory = APIRequestFactory()
view = IntegrationInstanceViewSet.as_view({'get': 'files'})
request = factory.get(f'/api/instances/{instance.id}/files/')
force_authenticate(request, user=user)
response = view(request, pk=instance.id)

print(f"API Response Status: {response.status_code}")
print(f"API Response Data: {response.data}")
