from django.contrib import admin
from .models import IntegrationService, IntegrationInstance, UserIntegrationIdentity

@admin.register(IntegrationService)
class IntegrationServiceAdmin(admin.ModelAdmin):
    list_display = ('name', 'provider_type', 'auth_method', 'base_url')
    list_filter = ('provider_type', 'auth_method')
    search_fields = ('name', 'base_url')

@admin.register(IntegrationInstance)
class IntegrationInstanceAdmin(admin.ModelAdmin):
    list_display = ('name', 'service', 'circle', 'created_at')
    list_filter = ('service__provider_type', 'created_at')
    search_fields = ('name', 'circle__name')
    autocomplete_fields = ['service', 'circle']

@admin.register(UserIntegrationIdentity)
class UserIntegrationIdentityAdmin(admin.ModelAdmin):
    list_display = ('user', 'service', 'external_user_id')
    list_filter = ('service',)
    search_fields = ('user__username', 'external_user_id')
    autocomplete_fields = ['user', 'service']
