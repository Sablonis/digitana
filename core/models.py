import uuid
from django.db import models
from django.contrib.auth.models import AbstractUser
from django.utils.translation import gettext_lazy as _

class User(AbstractUser):
    """
    Custom user model representing an organizational member or guest.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    
    class Status(models.TextChoices):
        ACTIVE = 'active', _('Active')
        INACTIVE = 'inactive', _('Inactive')
        INVITED = 'invited', _('Invited')

    status = models.CharField(
        max_length=20,
        choices=Status.choices,
        default=Status.ACTIVE,
    )
    is_guest = models.BooleanField(
        default=False,
        help_text=_("Designates whether this user is a guest without full member privileges.")
    )

    def __str__(self):
        return self.email or self.username


class Circle(models.Model):
    """
    Represents a structural unit in the organization (S3 Circle).
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=255)
    purpose = models.TextField(blank=True, help_text=_("The purpose of this circle."))
    domain_description = models.TextField(blank=True, help_text=_("Description of the circle's domain."))
    parent = models.ForeignKey(
        'self',
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name='children',
        help_text=_("The parent circle, if any.")
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return self.name


class Role(models.Model):
    """
    Definition of a role (e.g., 'Facilitator', 'Secretary').
    """
    class Scope(models.TextChoices):
        SYSTEM = 'system', _('System')
        CIRCLE = 'circle', _('Circle')

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=255)
    scope = models.CharField(
        max_length=20,
        choices=Scope.choices,
        default=Scope.CIRCLE,
    )
    default_permissions = models.JSONField(
        default=list,
        blank=True,
        help_text=_("List of default permission strings for this role.")
    )

    def __str__(self):
        return f"{self.name} ({self.get_scope_display()})"


class RoleAssignment(models.Model):
    """
    Links a User to a Role, optionally within a Circle.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='role_assignments')
    role = models.ForeignKey(Role, on_delete=models.CASCADE, related_name='assignments')
    circle = models.ForeignKey(
        Circle,
        on_delete=models.CASCADE,
        null=True,
        blank=True,
        related_name='role_assignments',
        help_text=_("The circle where this role applies. Null for system-wide roles.")
    )
    decision_reference = models.CharField(
        max_length=255,
        blank=True,
        help_text=_("Reference to the consent decision (e.g., meeting date or ID).")
    )
    assigned_at = models.DateTimeField(auto_now_add=True)
    revoked_at = models.DateTimeField(null=True, blank=True)

    def __str__(self):
        context = self.circle.name if self.circle else "System"
        return f"{self.user} as {self.role} in {context}"


class SystemConfig(models.Model):
    """
    Singleton model to store global system configuration, specifically for Infomaniak integration.
    """
    infomaniak_client_id = models.CharField(max_length=255, blank=True, help_text=_("Infomaniak OAuth2 Client ID"))
    infomaniak_client_secret = models.CharField(max_length=255, blank=True, help_text=_("Infomaniak OAuth2 Client Secret"))
    
    # Singleton pattern enforcement
    def save(self, *args, **kwargs):
        self.pk = 1
        super().save(*args, **kwargs)

    @classmethod
    def get_solo(cls):
        obj, created = cls.objects.get_or_create(pk=1)
        return obj

    def __str__(self):
        return "System Configuration"

