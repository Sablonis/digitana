from django.core.management.base import BaseCommand
from integrations.models import IntegrationService

class Command(BaseCommand):
    help = 'Seeds the Infomaniak Drive service'

    def handle(self, *args, **options):
        service, created = IntegrationService.objects.get_or_create(
            name='Infomaniak Drive',
            defaults={
                'provider_type': 'STORAGE', # Assuming STORAGE is valid
                'base_url': 'https://api.infomaniak.com',
                'auth_method': 'OAUTH2',
            }
        )
        if created:
            self.stdout.write(self.style.SUCCESS(f'Created service: {service.name}'))
        else:
            # Ensure auth method is correct if it already existed
            if service.auth_method != 'OAUTH2':
                service.auth_method = 'OAUTH2'
                service.save()
                self.stdout.write(self.style.SUCCESS(f'Updated service: {service.name} to OAUTH2'))
            else:
                self.stdout.write(self.style.SUCCESS(f'Service already exists: {service.name}'))
