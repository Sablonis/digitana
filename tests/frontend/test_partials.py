import pytest
from django.urls import reverse

@pytest.mark.django_db
class TestDashboardPartials:
    def test_stats_partial(self, client, user):
        client.force_login(user)
        url = reverse('dashboard-stats')
        response = client.get(url)
        assert response.status_code == 200
        assert b"Total Circles" in response.content

    def test_circles_partial(self, client, user):
        client.force_login(user)
        url = reverse('dashboard-circles')
        response = client.get(url)
        assert response.status_code == 200
        assert b"General Circle" in response.content

    def test_services_partial(self, client, user):
        client.force_login(user)
        url = reverse('dashboard-services')
        response = client.get(url)
        assert response.status_code == 200
        assert b"No services connected" in response.content

    def test_files_partial(self, client, user):
        client.force_login(user)
        url = reverse('dashboard-files')
        response = client.get(url)
        assert response.status_code == 200
        assert b"No recent files" in response.content
