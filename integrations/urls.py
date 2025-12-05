from django.urls import path
from . import views_auth

app_name = 'integrations'

urlpatterns = [
    path('connect/<uuid:service_id>/', views_auth.connect_service, name='connect'),
    path('callback/', views_auth.oauth_callback, name='callback'),
]
