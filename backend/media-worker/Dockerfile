# Mnema media worker image: the one-shot FFmpeg CLI that the trusted host runner starts in a throw-away container per
# job (deploy/production/mnema-media-runner.py). The container has no network, a read-only root, no capability, and
# only two mounts (/job/in read-only, /job/out); it lives for exactly one job.
# It contains Ubuntu's GPL-configured FFmpeg (libx264, libx265): keep its registry package PRIVATE and read
# README.md ("Licensing") before any redistribution. Production and CI build linux/amd64; arm64 serves local development.
ARG UBUNTU_BASE=ubuntu:24.04@sha256:008173c23f95b170204355c12626cb5a965d779a7e1283b09e9cffbb1bf33ca3
FROM ${UBUNTU_BASE}

# Links the GHCR package to this repository, so the repository's Actions (and only they, with the owner's default
# "Actions access: read") can pull it with the short-lived job token. This label never makes the package public.
LABEL org.opencontainers.image.source="https://github.com/MattoYuzuru/Mnema" \
      org.opencontainers.image.description="Mnema media worker: one-shot FFmpeg CLI, one container per job (GPL build, keep private)"

# Every package that decides what runs is pinned exactly, so a rebuild cannot change it: FFmpeg and its libraries
# together, the Python interpreter and its standard library, and the security floor below. A pinned version leaves the
# archive when it is superseded: a failing build means "bump the pins together" and rerun the codec matrix.
ARG FFMPEG_VERSION=7:6.1.1-3ubuntu5
ARG PYTHON_VERSION=3.12.3-0ubuntu2.1
ARG PYTHON312_VERSION=3.12.3-1ubuntu0.17
# Security floor found by the release scanner (HIGH in the pinned base's libssl3t64); drop it once the base digest includes it.
ARG LIBSSL_VERSION=3.0.13-0ubuntu3.16
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       ffmpeg=${FFMPEG_VERSION} libavcodec60=${FFMPEG_VERSION} libavdevice60=${FFMPEG_VERSION} \
       libavfilter9=${FFMPEG_VERSION} libavformat60=${FFMPEG_VERSION} libavutil58=${FFMPEG_VERSION} \
       libpostproc57=${FFMPEG_VERSION} libswresample4=${FFMPEG_VERSION} libswscale7=${FFMPEG_VERSION} \
       python3=${PYTHON_VERSION} python3-minimal=${PYTHON_VERSION} libpython3-stdlib=${PYTHON_VERSION} \
       python3.12=${PYTHON312_VERSION} python3.12-minimal=${PYTHON312_VERSION} \
       libpython3.12-minimal=${PYTHON312_VERSION} libpython3.12-stdlib=${PYTHON312_VERSION} \
       libssl3t64=${LIBSSL_VERSION} \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/mnema-media-worker
COPY mnema_media_worker ./mnema_media_worker
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=/opt/mnema-media-worker
# The runner starts the container as 10002:10002 with --user; the image default matches. Tests are not part of the image.
USER 10002:10002
ENTRYPOINT ["python3", "-m", "mnema_media_worker"]
