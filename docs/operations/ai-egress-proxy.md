# AI egress proxy

Status: current configuration; new production transport preparation for #346.
Owner target: `mnema` (Selectel, Russia, 135.106.175.30) and `keykomi`
(Finland, 2.27.23.184). Actual rollout evidence belongs to the infrastructure
issue; configuration in Git alone does not prove a running tunnel or provider.

## Transport and data boundary

```text
Learning → private CONNECT endpoint → SSH encrypted hop → loopback Squid → provider TLS
(Russia, provider keys)                                 (Finland, no provider keys)
```

SSH encryption of the VPS hop is mandatory. Basic authentication over public HTTP
is not acceptable, including with an IP allowlist. The templates bind Squid to
`127.0.0.1:3128` on keykomi and the supervised tunnel to `127.0.0.1:13128` on
mnema. No public proxy port or new firewall/routing rule is needed.

Provider TLS is end to end inside CONNECT; Squid does not terminate it. It sees
hostname, port, timing and byte counts, but cannot inspect prompts/responses/keys.
That does **not** remove the data flow to the AI provider, its retention policy,
regional terms or the legal requirements for cross-border processing. The provider
keys and primary databases remain on the Russian host. #280 and the approved
provider/recipient/disclosure inventory remain gates before real-user AI is enabled.
An HMAC user key and removal of email/phone/card numbers are not proof that arbitrary
learning content or voice has been anonymized.

The source implementation is `app.mnema.learning.ai.EgressClients`; installation
files are [deploy/ai-egress-proxy](../../deploy/ai-egress-proxy/README.md). A provider
opts in using `learning.ai.providers.<id>.egress=proxy`. A proxied provider with an
inactive transport has no adapter and reports `PROVIDER_NOT_CONFIGURED`.

## Protected neighboring workloads

keykomi is shared: Caddy, `keenetic-router-awg`, `amnezia-awg2` and
`keenetic-tv-control` are excluded from this change. Its 1-vCPU/2-GB capacity is
suitable only for a bounded initial CONNECT workload, not inference or unmeasured
scale. The proxy unit limits itself to 192 MB, 25% of one CPU and 32 tasks. Observe
traffic and latency before raising limits. A short HTTPS probe establishes TLS
reachability, not sustained bandwidth, provider account acceptance or AI streaming.
Never reinstall keykomi or change its global firewall, VPNs or routing for this proxy.

## Prepare identities and install (administrator only)

Use the existing pinned administrative `ssh mnema` / `ssh keykomi` access. First
capture `ss -lntup`, `ufw status numbered`, active units and `docker ps` on keykomi;
verify port 3128 and the `mnema-egress` identity are unused. Preserve any existing
files before replacing them. Commands below describe **new**, dedicated resources.
Do not overwrite a pre-existing user/key/config without checking ownership.

1. On mnema create a system user `mnema-egress`, without sudo/Docker group, and a
   private `/etc/mnema/egress` directory (`root:mnema-egress`, 0750). Generate a new
   Ed25519 tunnel key there (`ssh-keygen -t ed25519 -N ''`), mode 0600 and readable
   only by this system user. The private key never leaves mnema.
2. Through the already verified administrative SSH session read keykomi's
   `/etc/ssh/ssh_host_ed25519_key.pub`. Build `/etc/mnema/egress/known_hosts` for
   **2.27.23.184** from that public key; do not trust an unauthenticated key scan.
3. On keykomi create only a new system user `mnema-egress` without supplementary
   groups. Use a root-owned home/`.ssh` directory and root-owned `authorized_keys`.
   Install only the tunnel's public key with this prefix:

   ```text
   restrict,port-forwarding,permitopen="127.0.0.1:3128",command="/usr/bin/false" ssh-ed25519 …
   ```

4. Install [60-mnema-egress.conf](../../deploy/ai-egress-proxy/60-mnema-egress.conf)
   in `/etc/ssh/sshd_config.d/`. Validate `sshd -t` and effective settings with
   `sshd -T -C user=mnema-egress,host=keykomi,addr=135.106.175.30`, then reload SSH.
   `MaxSessions 0` permits forwarding while rejecting shell/subsystem sessions;
   `AllowTcpForwarding local` and `PermitOpen` restrict the destination. Verify a
   fresh administrative connection immediately after reload.
5. Before installing distribution packages `squid` and `apache2-utils`, verify
   Squid and its unit are absent/inactive, then `systemctl mask squid.service` to
   prevent package installation from starting the default public listener. An
   existing instance requires a separate ownership check; do not stop or mask it. Use the dedicated configuration/unit below,
   never an unattended replacement of a shared Squid configuration.
6. Generate a random proxy credential outside logs/history. Create
   `/etc/squid/mnema.htpasswd` with `htpasswd -i` reading stdin; owner `root:proxy`,
   mode 0640. Store the plaintext only on mnema in its private production secret
   directory; do not embed it in curl arguments, GitHub evidence or Git.
7. Install [squid.conf](../../deploy/ai-egress-proxy/squid.conf) as
   `/etc/squid/mnema.conf` and [proxy unit](../../deploy/ai-egress-proxy/mnema-egress-proxy.service)
   under `/etc/systemd/system/`. Install `mnema-egress-proxy.logrotate` into `/etc/logrotate.d/mnema-egress-proxy`; the unit creates its private log directory. Run
   `squid -k parse -f /etc/squid/mnema.conf`, `systemctl daemon-reload`, then enable
   and start **mnema-egress-proxy.service**. The distro `squid.service` remains
   masked for this new installation.
8. Install [tunnel unit](../../deploy/ai-egress-proxy/mnema-egress-tunnel.service)
   on mnema, daemon-reload, enable/start it. Verify strict host-key checking and
   both loopback listeners. No provider credential is sent to keykomi.

For a containerized Learning worker, host `127.0.0.1` is **not** the container's
loopback. #349 must supply a reviewed private transport integration (for example,
a tunnel sidecar sharing only the worker's network namespace). Do not publish
13128 on all interfaces or claim the host-only probe verifies Compose integration.

## Mnema client and capability configuration

| Private configuration name | Meaning |
| --- | --- |
| `MNEMA_AI_EGRESS_PROXY_URL` | Private reachable `http://host:port`, no userinfo/path/query |
| `MNEMA_AI_EGRESS_PROXY_USER` | Dedicated proxy username |
| `MNEMA_AI_EGRESS_PROXY_PASSWORD` | Dedicated random credential |
| `LEARNING_AI_EGRESS_ENABLED` | False disables proxied adapters on restart |

The JDK requires `-Djdk.http.auth.tunneling.disabledSchemes=` for Basic CONNECT.
Learning runtime images already set it. Host/local Java verification must pass it
explicitly. This does not disable encryption: the hop carrying Basic must be SSH,
and the provider connection must retain HTTPS certificate verification.

Configure egress only for providers whose live **capability**, terms and geography
require it. Direct DNS/TCP/TLS to Google from Russia can succeed while a paid API
rejects the account/region. DeepSeek, OpenRouter, Google TTS/STT, stock search and
web search must each have independent live evidence; absent keys/adapter/approval
mean unavailable. Do not change accepted #77 provider requirements to simplify
infrastructure. Never identify Stub/recorded fixtures as live provider evidence.

## Verification and stop conditions

Run `python3 scripts/verify_ai_egress.py --auth-file /etc/mnema/egress/proxy-auth.json`
on mnema with the private JSON credential file (`user`/`password`, 0600). It checks
positive CONNECT/TLS and exact 407/403 denials; an unreachable proxy fails the probe.
It ignores `~/.curlrc`, sends credentials through stdin and emits status/timing only.

Use bounded deadlines and a private curl config/stdin for proxy credentials, never
`http://user:password@…` or shell tracing. Record status/timing only. Run:

- Allowed Google hostname: authenticated CONNECT followed by verified provider TLS.
- Missing/wrong auth: 407. Non-allowlisted host, numeric/private/metadata target,
  non-CONNECT and non-443: denied. Check denial order using the committed test.
- Public keykomi:3128 and mnema:13128: closed from an external host.
- Dedicated SSH shell/PTY/SFTP, arbitrary local target, remote and Unix-socket forwarding:
  denied. Admin identities still work; Caddy/VPN/TV workloads unchanged.
- JDK client: correct auth challenge/CONNECT, bounded timeout, no certificate bypass.
- Stop the tunnel; proxied calls fail within configured timeouts. Restart and verify
  recovery. Disabling `LEARNING_AI_EGRESS_ENABLED` removes adapters; capability
  fallback/UI needs integrated application evidence separately.
- For configured provider keys, separately verify real streaming, cancellation and
  per-capability fallback within the approved cost/privacy envelope. No key means
  the check remains explicitly unverified.

Stop on an exposed listener, excessive resource use, neighbor degradation,
unauthorized destination, host-key mismatch or logs containing sensitive values.

## Kill switch and rollback

Fast off: stop `mnema-egress-tunnel.service` on mnema, or stop only
`mnema-egress-proxy.service` on keykomi. Set `LEARNING_AI_EGRESS_ENABLED=false` and
restart the Learning worker for a persistent fail-closed capability configuration.
Per-provider `enabled=false` removes only that adapter. Network transport failure
uses the existing timeout/circuit breaker; it must not become a direct-route privacy
bypass. Fallback follows its separately approved provider route.

Rollback removes/disables only these newly created units and the dedicated key's
access; preserve backups of config and all data. Validate SSH syntax before removing
its dedicated Match block, and verify admin access afterward. Do not copy the stock
Squid config over another service or reset the host's firewall. Credential rotation
updates the dedicated htpasswd and the private Russian runtime secret together.

Access logs contain `time client result bytes method host:port`, no username,
User-Agent, paths, bodies or provider keys. Squid does not decrypt TLS. The dedicated daily logrotate policy retains seven rotations; they are operational metadata and still need bounded retention.

Sources: [OpenSSH Match/PermitOpen/MaxSessions](https://man.openbsd.org/sshd_config.5),
[Squid listener configuration](https://www.squid-cache.org/Doc/config/http_port/),
[host/port-only log fields](https://www.squid-cache.org/Doc/config/logformat/),
[log rotation](https://www.squid-cache.org/Doc/config/logfile_rotate/),
[JDK HttpClient system properties](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/module-summary.html),
[Docker firewall boundary](https://docs.docker.com/engine/network/packet-filtering-firewalls/).
