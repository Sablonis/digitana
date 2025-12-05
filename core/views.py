from rest_framework import viewsets
from .models import User, Circle, Role, RoleAssignment
from .serializers import UserSerializer, CircleSerializer, RoleSerializer, RoleAssignmentSerializer

class UserViewSet(viewsets.ReadOnlyModelViewSet):
    queryset = User.objects.all()
    serializer_class = UserSerializer

class CircleViewSet(viewsets.ModelViewSet):
    queryset = Circle.objects.all()
    serializer_class = CircleSerializer

class RoleViewSet(viewsets.ModelViewSet):
    queryset = Role.objects.all()
    serializer_class = RoleSerializer

class RoleAssignmentViewSet(viewsets.ModelViewSet):
    queryset = RoleAssignment.objects.all()
    serializer_class = RoleAssignmentSerializer
