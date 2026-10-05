# AI egress proxy (Squid over SSH)

The existing stateless CONNECT implementation uses distribution Squid. Its
production VPS hop **must be encrypted**. The proxy binds only keykomi loopback;
a dedicated SSH identity may forward only to that listener. IP allowlisting
alone does not protect Basic credentials. Provider TLS remains end to end.

Installation, secret handling, verification, protected neighboring workloads and
rollback: [canonical runbook](../../docs/operations/ai-egress-proxy.md).

| File | Owner/installation |
| --- | --- |
| `squid.conf` | `/etc/squid/mnema.conf`, root-owned; loopback, auth, destination/SSRF denies, bounded timeouts |
| `mnema-egress-proxy.logrotate` | Dedicated daily rotation, seven files, signal to the dedicated unit |
| `mnema-egress-proxy.service` | keykomi dedicated systemd unit, proxy user, CPU/RAM/task limits |
| `60-mnema-egress.conf` | keykomi SSH Match for only the new tunnel identity; no sessions/arbitrary forwarding |
| `mnema-egress-tunnel.service` | mnema supervised tunnel, dedicated key, strict pinned host key, loopback listener |

Provider keys and databases remain in Russia. The proxy records bounded destination
metadata and does not decrypt/cache request bodies. The foreign AI provider still
processes the submitted data; statelessness is not legal compliance. #280 must be
satisfied for affected real-user capabilities. The host tunnel also requires a
separately reviewed private integration with the containerized worker (#349).
