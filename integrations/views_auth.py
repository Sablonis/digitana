from django.shortcuts import redirect, get_object_or_404
from django.http import HttpResponseBadRequest, HttpResponse
from django.contrib.auth.decorators import login_required
from django.urls import reverse
from django.conf import settings
from .models import IntegrationService, UserIntegrationIdentity
from .oauth import get_auth_url, exchange_code

@login_required
def connect_service(request, service_id):
    """
    Initiates the OAuth2 flow for a specific service.
    """
    service = get_object_or_404(IntegrationService, pk=service_id)
    
    # Get Client ID from service credentials
    client_id = service.credentials.get('client_id')
    if not client_id:
        return HttpResponseBadRequest("Service not configured with Client ID")

    # Build Redirect URI (must match what's registered in Infomaniak)
    # e.g. http://localhost:8000/integrations/callback/
    redirect_uri = request.build_absolute_uri(reverse('integrations:callback'))
    
    # Store service_id in session to retrieve it in callback
    request.session['oauth_service_id'] = str(service_id)
    
    # Generate Auth URL
    auth_url = get_auth_url(client_id, redirect_uri, state="random_state_string", scopes=service.required_scopes)
    
    return redirect(auth_url)

@login_required
def oauth_callback(request):
    """
    Handles the OAuth2 callback.
    """
    code = request.GET.get('code')
    error = request.GET.get('error')
    
    if error:
        return HttpResponseBadRequest(f"OAuth Error: {error}")
    
    if not code:
        return HttpResponseBadRequest("No code provided")
        
    service_id = request.session.get('oauth_service_id')
    if not service_id:
        return HttpResponseBadRequest("Session expired or invalid flow")
        
    service = get_object_or_404(IntegrationService, pk=service_id)
    client_id = service.credentials.get('client_id')
    client_secret = service.credentials.get('client_secret')
    
    if not client_id or not client_secret:
        return HttpResponseBadRequest("Service configuration missing credentials")

    redirect_uri = request.build_absolute_uri(reverse('integrations:callback'))

    try:
        # Exchange code for token
        token_data = exchange_code(client_id, client_secret, redirect_uri, code)
        
        # Save Identity
        UserIntegrationIdentity.objects.update_or_create(
            user=request.user,
            service=service,
            defaults={
                'external_user_id': token_data.get('user_id', 'unknown'), # Infomaniak might not return user_id in token response, might need separate call
                'credentials': token_data
            }
        )
        
        return redirect('/') # Redirect to dashboard
        
    except Exception as e:
        return HttpResponseBadRequest(f"Token Exchange Failed: {str(e)}")
