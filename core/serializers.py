from rest_framework import serializers
from .models import User, Circle, Role, RoleAssignment

class UserSerializer(serializers.ModelSerializer):
    class Meta:
        model = User
        fields = ['id', 'username', 'email', 'first_name', 'last_name', 'status', 'is_guest']

class CircleSerializer(serializers.ModelSerializer):
    class Meta:
        model = Circle
        fields = ['id', 'name', 'purpose', 'domain_description', 'parent', 'created_at']

class RoleSerializer(serializers.ModelSerializer):
    class Meta:
        model = Role
        fields = ['id', 'name', 'scope', 'default_permissions']

class RoleAssignmentSerializer(serializers.ModelSerializer):
    class Meta:
        model = RoleAssignment
        fields = ['id', 'user', 'role', 'circle', 'decision_reference', 'assigned_at', 'revoked_at']
