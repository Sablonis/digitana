from rest_framework import viewsets, decorators
from rest_framework.response import Response
from .models import IntegrationService, IntegrationInstance, UserIntegrationIdentity
from .serializers import IntegrationServiceSerializer, IntegrationInstanceSerializer, UserIntegrationIdentitySerializer
from .adapters.factory import get_storage_adapter


class IntegrationServiceViewSet(viewsets.ModelViewSet):
    queryset = IntegrationService.objects.all()
    serializer_class = IntegrationServiceSerializer

class IntegrationInstanceViewSet(viewsets.ModelViewSet):
    queryset = IntegrationInstance.objects.all()
    serializer_class = IntegrationInstanceSerializer

    @decorators.action(detail=True, methods=['get'])
    def files(self, request, pk=None):
        """
        Fetch files from the connected service adapter.
        """
        instance = self.get_object()
        
        # Only support storage providers for now
        if instance.service.provider_type != IntegrationService.ProviderType.STORAGE:
            return Response({"error": "Service is not a storage provider"}, status=400)

        adapter = get_storage_adapter(instance)
        files = adapter.list_files()
        
        # Serialize the dataclass objects manually or use a serializer
        data = [
            {
                "id": f.id,
                "name": f.name,
                "mime_type": f.mime_type,
                "size": f.size,
                "modified_at": f.modified_at,
                "webview_link": f.webview_link
            }
            for f in files
        ]
        return Response(data)


class UserIntegrationIdentityViewSet(viewsets.ModelViewSet):
    queryset = UserIntegrationIdentity.objects.all()
    serializer_class = UserIntegrationIdentitySerializer
