import uuid
from django.db import models
from django.conf import settings
from django.utils.translation import gettext_lazy as _
from core.models import Circle

class IntegrationService(models.Model):
    """
    Definition of an external service provider (e.g., 'Infomaniak Drive').
    """
    class ProviderType(models.TextChoices):
        STORAGE = 'storage', _('Storage')
        CALENDAR = 'calendar', _('Calendar')
        NEWSLETTER = 'newsletter', _('Newsletter')
        MEDIA = 'media', _('Media')
        CHAT = 'chat', _('Chat')
        MEETING = 'meeting', _('Meeting')
        OTHER = 'other', _('Other')

    class AuthMethod(models.TextChoices):
        OAUTH2 = 'oauth2', _('OAuth2')
        API_KEY = 'api_key', _('API Key')
        BASIC = 'basic', _('Basic Auth')

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=255)
    provider_type = models.CharField(
        max_length=20,
        choices=ProviderType.choices,
        default=ProviderType.OTHER,
    )
    base_url = models.URLField(help_text=_("Base URL of the service API."))
    auth_method = models.CharField(
        max_length=20,
        choices=AuthMethod.choices,
        default=AuthMethod.OAUTH2,
    )
    required_scopes = models.JSONField(
        default=list,
        blank=True,
        help_text=_("List of required scopes for this service.")
    )
    credentials = models.JSONField(
        default=dict,
        blank=True,
        help_text=_("Provider credentials (client_id, client_secret).")
    )
    created_at = models.DateTimeField(auto_now_add=True)

    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return self.name


class IntegrationInstance(models.Model):
    """
    A specific configuration of a service (e.g., 'Marketing Team Drive').
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    service = models.ForeignKey(IntegrationService, on_delete=models.CASCADE, related_name='instances')
    name = models.CharField(max_length=255)
    configuration = models.JSONField(
        default=dict,
        blank=True,
        help_text=_("Specific configuration (e.g., folder IDs, calendar IDs).")
    )
    circle = models.ForeignKey(
        Circle,
        on_delete=models.CASCADE,
        related_name='integration_instances',
        help_text=_("The circle that owns/uses this integration instance.")
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"{self.name} ({self.service.name})"


class UserIntegrationIdentity(models.Model):
    """
    Links a local User to an external account.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='integration_identities')
    service = models.ForeignKey(IntegrationService, on_delete=models.CASCADE, related_name='user_identities')
    external_user_id = models.CharField(max_length=255, help_text=_("ID of the user in the external system."))
    credentials = models.JSONField(
        default=dict,
        help_text=_("Encrypted credentials (tokens, refresh tokens). SECURITY WARNING: Ensure this is handled securely.")
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        unique_together = ('user', 'service')
        verbose_name_plural = "User integration identities"

    def __str__(self):
        return f"{self.user} on {self.service}"
