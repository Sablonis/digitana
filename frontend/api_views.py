from django.http import JsonResponse
from django.views import View
from django.contrib.auth.mixins import LoginRequiredMixin
from integrations.models import IntegrationInstance, IntegrationService
from integrations.adapters.kmeet import KMeetProvider

class CreateMeetingView(LoginRequiredMixin, View):
    def post(self, request, *args, **kwargs):
        # Find kMeet instance
        instance = IntegrationInstance.objects.filter(
            service__provider_type=IntegrationService.ProviderType.MEETING
        ).first()
        
        if not instance:
            return JsonResponse({'error': 'No kMeet instance connected'}, status=400)
            
        try:
            provider = KMeetProvider(instance)
            # Create meeting
            meeting = provider.create_meeting(subject=f"Meeting by {request.user.username}")
            return JsonResponse(meeting)
        except Exception as e:
            return JsonResponse({'error': str(e)}, status=500)
