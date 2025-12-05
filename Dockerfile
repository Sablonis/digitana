FROM python:3.11-slim as builder

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE 1
ENV PYTHONUNBUFFERED 1

# Install system dependencies for building python packages
RUN apt-get update && apt-get install -y \
    build-essential \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

RUN pip install poetry

COPY pyproject.toml poetry.lock ./
RUN poetry config virtualenvs.in-project true && \
    poetry install --no-root --no-interaction --no-ansi && \
    mv .venv /opt/venv

FROM python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE 1
ENV PYTHONUNBUFFERED 1
ENV PATH="/opt/venv/bin:$PATH"

# Install runtime dependencies
RUN apt-get update && apt-get install -y \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

# Create a non-root user with UID 1000 to match host user
RUN groupadd -g 1000 django && \
    useradd -u 1000 -g django -s /bin/bash -m django

# Copy virtualenv from builder
COPY --from=builder --chown=django:django /opt/venv /opt/venv

# Copy application code
COPY --chown=django:django . .

USER django

CMD ["gunicorn", "--bind", "0.0.0.0:8000", "digitana.wsgi:application"]
