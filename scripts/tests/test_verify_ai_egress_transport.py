"""Protect the public/neighbor boundary of the production CONNECT transport."""

from pathlib import Path
import importlib.util
import unittest


ROOT = Path(__file__).resolve().parents[2]
DEPLOY = ROOT / "deploy/ai-egress-proxy"
SPEC = importlib.util.spec_from_file_location("verify_ai_egress", ROOT / "scripts/verify_ai_egress.py")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class EgressTransportBoundaryTest(unittest.TestCase):
    def test_network_failure_is_not_evidence_of_destination_denial(self):
        outage = {"connect": 0, "http": 0, "seconds": 5, "transport_exit": 7}
        for name in ("no-auth", "foreign-host", "loopback", "metadata", "foreign-port", "non-connect", "allowed-google"):
            self.assertFalse(VERIFY.accepted(name, outage), name)

    def test_successful_connect_is_not_a_successful_provider_tls_probe(self):
        self.assertFalse(VERIFY.accepted("allowed-google", {"connect": 200, "http": 0, "transport_exit": 60}))
        self.assertFalse(VERIFY.accepted("allowed-google", {"connect": 200, "http": 403, "transport_exit": 0}))
        self.assertTrue(VERIFY.accepted("allowed-google", {"connect": 200, "http": 404, "transport_exit": 0}))

    def test_private_credentials_cannot_inject_additional_curl_configuration(self):
        with self.assertRaises(ValueError):
            VERIFY.curl_quote("password\nurl = https://example.com")
        self.assertEqual(VERIFY.curl_quote('p"q\\r'), '"p\\"q\\\\r"')

    def test_proxy_is_loopback_only_and_denials_precede_the_allowlist(self):
        config = (DEPLOY / "squid.conf").read_text()
        self.assertIn("http_port 127.0.0.1:3128", config)
        self.assertNotIn("<RU_SERVER_IP>", config)
        allow = config.index("http_access allow CONNECT allowed_hosts tls_port")
        for denial in ("!mnema_clients", "!authenticated", "to_private", "ip_literal", "!CONNECT", "!tls_port"):
            self.assertLess(config.index("http_access deny " + denial), allow)
        self.assertIn("http_access deny all", config[allow:])
        self.assertIn("cache deny all", config)
        self.assertNotIn("ssl_bump", config)
        self.assertIn("%>rd:%>rP", config)
        self.assertNotIn(" %ru", config)
        private_rules = "\n".join(line for line in config.splitlines() if line.startswith("acl to_private dst "))
        self.assertNotIn("::ffff:0:0/96", private_rules)
        self.assertNotIn("0.0.0.0/0", private_rules)

    def test_tunnel_pins_identity_host_key_and_loopback_destination(self):
        config = (DEPLOY / "mnema-egress-tunnel.service").read_text()
        for option in ("StrictHostKeyChecking=yes", "IdentitiesOnly=yes", "ExitOnForwardFailure=yes",
                       "User=mnema-egress", "127.0.0.1:13128:127.0.0.1:3128"):
            self.assertIn(option, config)
        self.assertNotIn("StrictHostKeyChecking=no", config)
        self.assertNotIn("-L 0.0.0.0", config)

    def test_remote_identity_cannot_open_sessions_or_arbitrary_forwarding(self):
        config = (DEPLOY / "60-mnema-egress.conf").read_text()
        self.assertTrue(config.rstrip().endswith("Match all"))
        for restriction in ("Match User mnema-egress", "AllowTcpForwarding local", "AllowStreamLocalForwarding no",
                            "PermitTunnel no", "PermitOpen 127.0.0.1:3128",
                            "PermitListen none", "MaxSessions 0", "PermitTTY no", "ForceCommand /usr/bin/false"):
            self.assertIn(restriction, config)

    def test_proxy_limits_resources_on_the_shared_host(self):
        config = (DEPLOY / "mnema-egress-proxy.service").read_text()
        for limit in ("User=proxy", "MemoryMax=192M", "CPUQuota=25%", "TasksMax=32", "NoNewPrivileges=true"):
            self.assertIn(limit, config)
        self.assertNotIn("docker.sock", config)


if __name__ == "__main__":
    unittest.main()
