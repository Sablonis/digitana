from django.contrib import admin
from django.urls import path, include
from rest_framework.routers import DefaultRouter
from drf_spectacular.views import SpectacularAPIView, SpectacularRedocView, SpectacularSwaggerView

from core.views import UserViewSet, CircleViewSet, RoleViewSet, RoleAssignmentViewSet
from integrations.views import IntegrationServiceViewSet, IntegrationInstanceViewSet, UserIntegrationIdentityViewSet

router = DefaultRouter()
# Core
router.register(r'users', UserViewSet)
router.register(r'circles', CircleViewSet)
router.register(r'roles', RoleViewSet)
router.register(r'role-assignments', RoleAssignmentViewSet)
# Integrations
router.register(r'services', IntegrationServiceViewSet)
router.register(r'instances', IntegrationInstanceViewSet)
router.register(r'identities', UserIntegrationIdentityViewSet)

urlpatterns = [
    path('admin/', admin.site.urls),
    path('accounts/', include('django.contrib.auth.urls')),
    path('auth/', include('core.urls')),
    path('api/', include(router.urls)),
    path('integrations/', include('integrations.urls')),
    path('', include('frontend.urls')),
    # OpenAPI Schema
    path('api/schema/', SpectacularAPIView.as_view(), name='schema'),
    # Swagger UI
    path('api/schema/swagger-ui/', SpectacularSwaggerView.as_view(url_name='schema'), name='swagger-ui'),
    # Redoc UI
    path('api/schema/redoc/', SpectacularRedocView.as_view(url_name='schema'), name='redoc'),
]
