# DEVELOPMENT ONLY: the production media runner (deploy/production/mnema-media-runner.py) in a container that also holds the
# Docker client, for the local full stack, which has no host service and no systemd. Production runs the same script as a root
# host service and never mounts the Docker socket into a container. Build context: the repository root.
FROM docker:29.5.2-cli@sha256:9ba8e32bfc35a2c7ae2feb1e3241b2778ae21dee80f4dcd31d04e1cfdea86ea2 AS docker-cli

FROM ubuntu:24.04@sha256:008173c23f95b170204355c12626cb5a965d779a7e1283b09e9cffbb1bf33ca3
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY deploy/production/mnema-media-runner.py /usr/local/sbin/mnema-media-runner
ENTRYPOINT ["python3", "-I", "/usr/local/sbin/mnema-media-runner"]
