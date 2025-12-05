from django.shortcuts import render
from django.contrib.auth.decorators import login_required
from integrations.models import IntegrationService, IntegrationInstance

@login_required
def dashboard_stats_partial(request):
    # Mock stats for now, replace with real queries later
    context = {
        'total_circles': 3,
        'active_users': 12,
        'total_integrations': IntegrationInstance.objects.count(),
    }
    return render(request, 'frontend/partials/stats.html', context)

@login_required
def dashboard_circles_partial(request):
    # Mock circles
    circles = [
        {'name': 'General Circle', 'description': 'Main organizational circle', 'status': 'Active'},
        {'name': 'Dev Team', 'description': 'Developers and QA', 'status': 'Active'},
    ]
    return render(request, 'frontend/partials/circles.html', {'circles': circles})

@login_required
def dashboard_services_partial(request):
    instances = IntegrationInstance.objects.all()
    return render(request, 'frontend/partials/services.html', {'instances': instances})

@login_required
def dashboard_files_partial(request):
    # This would normally fetch from kDrive API
    # For now, return empty or mock
    files = [] 
    return render(request, 'frontend/partials/files.html', {'files': files})
