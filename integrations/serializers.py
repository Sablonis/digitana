from rest_framework import serializers
from .models import IntegrationService, IntegrationInstance, UserIntegrationIdentity

class IntegrationServiceSerializer(serializers.ModelSerializer):
    class Meta:
        model = IntegrationService
        fields = ['id', 'name', 'provider_type', 'base_url', 'auth_method', 'required_scopes']

class IntegrationInstanceSerializer(serializers.ModelSerializer):
    service = IntegrationServiceSerializer(read_only=True)
    service_id = serializers.PrimaryKeyRelatedField(
        queryset=IntegrationService.objects.all(), source='service', write_only=True
    )

    class Meta:
        model = IntegrationInstance
        fields = ['id', 'service', 'service_id', 'name', 'configuration', 'circle', 'created_at']

class UserIntegrationIdentitySerializer(serializers.ModelSerializer):
    class Meta:
        model = UserIntegrationIdentity
        fields = ['id', 'user', 'service', 'external_user_id', 'created_at']
        read_only_fields = ['credentials'] # Security: Don't expose credentials via API
