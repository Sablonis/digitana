from django.core.management.base import BaseCommand
from integrations.models import IntegrationService

class Command(BaseCommand):
    help = 'Seeds the Infomaniak Drive service'

    def handle(self, *args, **options):
        # 1. OAuth Service
        service_oauth, _ = IntegrationService.objects.get_or_create(
            name='Infomaniak Drive',
            defaults={
                'provider_type': 'STORAGE',
                'base_url': 'https://api.infomaniak.com',
                'auth_method': 'OAUTH2',
            }
        )
        if service_oauth.auth_method != 'OAUTH2':
             service_oauth.auth_method = 'OAUTH2'
             service_oauth.save()
        self.stdout.write(self.style.SUCCESS(f'Verified: {service_oauth.name} (OAuth)'))

        # 2. API Key Service
        service_key, created = IntegrationService.objects.get_or_create(
            name='Infomaniak Drive (API Key)',
            defaults={
                'provider_type': 'STORAGE',
                'base_url': 'https://api.infomaniak.com',
                'auth_method': 'API_KEY',
            }
        )
        if service_key.auth_method != 'API_KEY':
             service_key.auth_method = 'API_KEY'
             service_key.save()
        self.stdout.write(self.style.SUCCESS(f'Verified: {service_key.name} (API Key)'))
