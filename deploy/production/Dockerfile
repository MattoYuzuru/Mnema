FROM postgres:18.6-alpine3.24@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873

# The upstream gosu embeds a vulnerable Go standard library. The entrypoint uses
# only `gosu postgres command...`; Alpine su-exec preserves that direct-exec API.
RUN apk add --no-cache 'su-exec>=0.2-r3' \
    && test "$(grep -c 'exec gosu postgres' /usr/local/bin/docker-entrypoint.sh)" = 1 \
    && sed -i 's/exec gosu postgres/exec su-exec postgres/' /usr/local/bin/docker-entrypoint.sh \
    && rm /usr/local/bin/gosu \
    && su-exec postgres sh -c 'test "$(id -u)" -ne 0'
