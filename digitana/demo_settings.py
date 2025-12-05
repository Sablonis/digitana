from .settings import *

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": BASE_DIR / "db.sqlite3",
    }
}

ALLOWED_HOSTS = ["localhost", "127.0.0.1", "[::1]"]
DEBUG = True

# Demo overrides
INFOMANIAK_AUTH = {
    "CLIENT_ID": "DEMO_CLIENT_ID",
    "CLIENT_SECRET": "DEMO_CLIENT_SECRET",
    "REDIRECT_URI": "http://localhost:8000/auth/infomaniak/callback/",
}
