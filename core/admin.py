from django.contrib import admin
from django.contrib.auth.admin import UserAdmin
from .models import User, Circle, Role, RoleAssignment

@admin.register(User)
class CustomUserAdmin(UserAdmin):
    list_display = ('username', 'email', 'first_name', 'last_name', 'status', 'is_guest', 'is_staff')
    list_filter = ('status', 'is_guest', 'is_staff', 'is_superuser', 'groups')
    fieldsets = UserAdmin.fieldsets + (
        ('Organizational Info', {'fields': ('status', 'is_guest')}),
    )

@admin.register(Circle)
class CircleAdmin(admin.ModelAdmin):
    list_display = ('name', 'parent', 'created_at')
    search_fields = ('name', 'purpose')
    list_filter = ('created_at',)

@admin.register(Role)
class RoleAdmin(admin.ModelAdmin):
    list_display = ('name', 'scope')
    list_filter = ('scope',)
    search_fields = ('name',)

@admin.register(RoleAssignment)
class RoleAssignmentAdmin(admin.ModelAdmin):
    list_display = ('user', 'role', 'circle', 'assigned_at', 'revoked_at')
    list_filter = ('assigned_at', 'revoked_at', 'role__scope')
    search_fields = ('user__username', 'role__name', 'circle__name')
    autocomplete_fields = ['user', 'role', 'circle']
