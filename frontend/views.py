from django.views.generic import TemplateView
from django.contrib.auth.mixins import LoginRequiredMixin

from django.views.generic import TemplateView, View
from django.contrib.auth.mixins import LoginRequiredMixin, UserPassesTestMixin
from django.shortcuts import render, redirect
from django.contrib import messages
from django.conf import settings
from integrations.models import IntegrationService, UserIntegrationIdentity
from core.models import SystemConfig

class SetupWizardView(LoginRequiredMixin, UserPassesTestMixin, View):
    template_name = 'frontend/wizard/setup_infomaniak.html'

    def test_func(self):
        return self.request.user.is_superuser

    def get(self, request):
        config = SystemConfig.get_solo()
        # Determine redirect URI based on current host (simplified)
        redirect_uri = settings.INFOMANIAK_AUTH.get('REDIRECT_URI')
        
        return render(request, self.template_name, {
            'config': config,
            'redirect_uri': redirect_uri
        })

    def post(self, request):
        if 'seed_services' in request.POST:
            from integrations.services import seed_default_services
            results = seed_default_services()
            messages.success(request, f"Services initialized: {', '.join(results)}")
            return redirect('setup_wizard')

        client_id = request.POST.get('client_id')
        client_secret = request.POST.get('client_secret')
        
        if client_id and client_secret:
            config = SystemConfig.get_solo()
            config.infomaniak_client_id = client_id
            config.infomaniak_client_secret = client_secret
            config.save()
            messages.success(request, "Configuration saved successfully!")
            return redirect('dashboard')
            
        messages.error(request, "Please provide both Client ID and Secret.")
        return redirect('setup_wizard')

class DashboardView(LoginRequiredMixin, TemplateView):
    template_name = "frontend/dashboard.html"

    def get_context_data(self, **kwargs):
        context = super().get_context_data(**kwargs)
        # MVP: Fetch first available instances for demo
        from integrations.models import IntegrationInstance, IntegrationService
        
        # kChat
        kchat_instance = IntegrationInstance.objects.filter(
            service__provider_type=IntegrationService.ProviderType.CHAT
        ).first()
        context['kchat_instance'] = kchat_instance
        
        # kMeet
        kmeet_instance = IntegrationInstance.objects.filter(
            service__provider_type=IntegrationService.ProviderType.MEETING
        ).first()
        context['kmeet_instance'] = kmeet_instance
        
        return context

class ConnectView(LoginRequiredMixin, TemplateView):
    template_name = "frontend/connect.html"

    def get_context_data(self, **kwargs):
        context = super().get_context_data(**kwargs)
        from integrations.models import IntegrationService
        context['services'] = IntegrationService.objects.all()
        return context
