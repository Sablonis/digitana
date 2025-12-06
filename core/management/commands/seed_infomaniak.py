from integrations.services import seed_default_services

class Command(BaseCommand):
    help = 'Seeds the Infomaniak Drive service'

    def handle(self, *args, **options):
        results = seed_default_services()
        for res in results:
            self.stdout.write(self.style.SUCCESS(res))
