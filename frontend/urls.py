from django.urls import path
from . import views, api_views, views_partials

urlpatterns = [
    path('', views.DashboardView.as_view(), name='dashboard'),
    path('setup/', views.SetupWizardView.as_view(), name='setup_wizard'),
    path('connect/', views.ConnectView.as_view(), name='connect'),
    path('api/meet/create/', api_views.CreateMeetingView.as_view(), name='create-meeting'),
    
    # HTMX Partials
    path('partials/stats/', views_partials.dashboard_stats_partial, name='dashboard-stats'),
    path('partials/circles/', views_partials.dashboard_circles_partial, name='dashboard-circles'),
    path('partials/services/', views_partials.dashboard_services_partial, name='dashboard-services'),
    path('partials/files/', views_partials.dashboard_files_partial, name='dashboard-files'),
]
