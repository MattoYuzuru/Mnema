# AI egress proxy (Squid)

A stateless HTTP CONNECT forward proxy on the owner's VPS in Finland, used by the Mnema Learning `worker` to reach AI
providers that are unreachable from Russia. Runbook: [`docs/operations/ai-egress-proxy.md`](../../docs/operations/ai-egress-proxy.md).

Threat model:

1. TLS is end to end (CONNECT): the proxy sees destination host, port, timing and byte counts, never a request, a prompt or a provider key.
2. The proxy credentials travel as clear Basic between the Mnema server and the VPS, so the firewall rule that admits only the Mnema server IP is mandatory, not optional; WireGuard between the two hosts is the optional upgrade.
3. Rotate the htpasswd password on any doubt and after every owner-IP testing session; the Mnema side only needs the new `MNEMA_AI_EGRESS_PROXY_PASSWORD`.
4. Squid allows CONNECT to port 443 of the listed provider hosts only and refuses private, loopback, link-local and metadata destinations, so a leaked credential cannot be used as an open proxy or to probe the VPS network.
5. Nothing is cached or stored besides a rotated access log of `host:port` lines (7 files); `X-Forwarded-For` and `Via` are removed.

Files: `squid.conf` (install as `/etc/squid/squid.conf`; replace `<RU_SERVER_IP>`). No third-party images; Squid comes from the distribution package.
