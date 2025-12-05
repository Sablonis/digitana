from django.urls import path
from . import views_auth

urlpatterns = [
    path('infomaniak/login/', views_auth.infomaniak_login, name='infomaniak_login'),
    path('infomaniak/callback/', views_auth.infomaniak_callback, name='infomaniak_callback'),
]
