import os
import django
import sys

# Setup Django
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'digitana.settings')
django.setup()

from integrations.models import IntegrationService, IntegrationInstance, UserIntegrationIdentity
from core.models import User, Circle
from integrations.adapters.factory import get_storage_adapter

def run_real_test():
    print("--- Infomaniak Real API Test ---")
    
    # 1. Get Access Token
    token = input("Enter your Infomaniak Access Token: ").strip()
    if not token:
        print("Token is required.")
        return

    # 2. Setup Data
    print("Setting up database objects...")
    try:
        user = User.objects.filter(is_superuser=True).first()
        if not user:
            user = User.objects.create_superuser('admin_test', 'admin@example.com', 'password')
            print("Created temporary admin user.")
        
        circle = Circle.objects.first()
        if not circle:
            circle = Circle.objects.create(name="Test Circle")
            print("Created temporary circle.")

        # Create/Update Service
        service, _ = IntegrationService.objects.update_or_create(
            name="Infomaniak Drive Real Test",
            defaults={
                'provider_type': IntegrationService.ProviderType.STORAGE,
                'base_url': "https://api.infomaniak.com",
                'auth_method': IntegrationService.AuthMethod.OAUTH2
            }
        )

        # Create/Update Identity
        UserIntegrationIdentity.objects.update_or_create(
            user=user,
            service=service,
            defaults={
                'external_user_id': 'real_user',
                'credentials': {'access_token': token}
            }
        )

        # Create Instance (No Drive ID initially to test discovery)
        instance, _ = IntegrationInstance.objects.update_or_create(
            service=service,
            name="Infomaniak Real Instance",
            circle=circle,
            defaults={'configuration': {}}
        )

        # 3. Test Adapter
        print(f"\nInitializing Adapter for instance: {instance.name}")
        adapter = get_storage_adapter(instance)
        
        print("\n--- Testing List Files (and Drive Discovery) ---")
        try:
            files = adapter.list_files()
            print(f"Success! Found {len(files)} files/folders in root.")
            for f in files[:5]:
                print(f" - {f.name} ({f.mime_type}) ID: {f.id}")
            if len(files) > 5:
                print("   ... and more.")
                
            # Check if Drive ID was discovered and saved (optional, adapter doesn't save back to DB automatically usually)
            # But we can check if the adapter instance has it in memory if we inspected it, 
            # but `list_files` calls `_get_drive_id` internally.
            
        except Exception as e:
            print(f"FAILED to list files: {e}")
            import traceback
            traceback.print_exc()

    except Exception as e:
        print(f"Setup failed: {e}")

if __name__ == "__main__":
    run_real_test()
