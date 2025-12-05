from django.core.management.base import BaseCommand
from integrations.models import IntegrationService
from django.contrib.auth import get_user_model

class Command(BaseCommand):
    help = 'Creates demo data for testing'

    def handle(self, *args, **options):
        # 0. Superuser
        User = get_user_model()
        if not User.objects.filter(username='admin').exists():
            User.objects.create_superuser('admin', 'admin@example.com', 'admin')
            self.stdout.write(self.style.SUCCESS('Created superuser: admin / admin'))

        # 1. Infomaniak kDrive (Storage)
        service, created = IntegrationService.objects.get_or_create(
            name="Infomaniak kDrive",
            defaults={
                "provider_type": IntegrationService.ProviderType.STORAGE,
                "auth_method": IntegrationService.AuthMethod.OAUTH2,
                "credentials": {
                    "client_id": "DEMO_CLIENT_ID",
                    "client_secret": "DEMO_CLIENT_SECRET"
                },
                "required_scopes": ["kdrive", "user"]
            }
        )
        if created:
            self.stdout.write(self.style.SUCCESS(f'Created service: {service.name}'))
        else:
            self.stdout.write(f'Service already exists: {service.name}')

        # 2. Infomaniak kChat (Chat)
        chat, created = IntegrationService.objects.get_or_create(
            name="Infomaniak kChat",
            defaults={
                "provider_type": IntegrationService.ProviderType.CHAT,
                "auth_method": IntegrationService.AuthMethod.OAUTH2,
                "credentials": {
                    "client_id": "DEMO_CLIENT_ID",
                    "client_secret": "DEMO_CLIENT_SECRET"
                },
                "required_scopes": ["chat", "user"]
            }
        )
        if created:
            self.stdout.write(self.style.SUCCESS(f'Created service: {chat.name}'))
        
        # 3. Infomaniak kMeet (Meeting)
        meet, created = IntegrationService.objects.get_or_create(
            name="Infomaniak kMeet",
            defaults={
                "provider_type": IntegrationService.ProviderType.MEETING,
                "auth_method": IntegrationService.AuthMethod.OAUTH2,
                "credentials": {
                    "client_id": "DEMO_CLIENT_ID",
                    "client_secret": "DEMO_CLIENT_SECRET"
                },
                "required_scopes": ["meet", "user"]
            }
        )
        if created:
            self.stdout.write(self.style.SUCCESS(f'Created service: {meet.name}'))
