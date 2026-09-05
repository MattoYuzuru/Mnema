from __future__ import annotations

import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch


PATH = Path(__file__).parents[1] / "bootstrap-staging-identity-signing.py"
SPEC = importlib.util.spec_from_file_location("identity_signing_bootstrap", PATH)
assert SPEC and SPEC.loader
bootstrap = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = bootstrap
SPEC.loader.exec_module(bootstrap)


def b64(value: str) -> str:
    return base64.b64encode(value.encode()).decode()


class FakeKubectl:
    def __init__(self, data: dict[str, str] | None = None) -> None:
        self.data = data or {"POSTGRES_USER": b64("mnema")}
        self.commands: list[list[str]] = []
        self.payloads: list[dict] = []

    def __call__(self, command, **kwargs):
        self.commands.append(command)
        if "get" in command:
            return subprocess.CompletedProcess(command, 0, json.dumps({
                "apiVersion": "v1", "kind": "Secret", "type": "Opaque",
                "metadata": {"name": bootstrap.SECRET, "namespace": bootstrap.NAMESPACE,
                             "uid": "secret-uid", "resourceVersion": "7",
                             "labels": {"owner": "mnema"},
                             "annotations": {"mnema.app/bootstrap-state": "initialized"}},
                "data": self.data,
            }), "")
        self.payloads.append(json.loads(kwargs["input"]))
        return subprocess.CompletedProcess(command, 0, "secret/mnema-secrets\n", "")


class IdentitySigningBootstrapTest(unittest.TestCase):
    def setUp(self):
        generated = subprocess.run([
            "node", "-e",
            "const {generateKeyPairSync}=require('node:crypto');"
            "const {privateKey}=generateKeyPairSync('rsa',{modulusLength:2048,publicExponent:0x10001});"
            "const key=privateKey.export({format:'jwk'});"
            "Object.assign(key,{kid:'fresh-kid',use:'sig',alg:'RS256'});"
            "process.stdout.write(JSON.stringify({keys:[key]}));",
        ], check=True, capture_output=True, text=True, timeout=10)
        self.jwk = generated.stdout
        self.environment = {bootstrap.JWK_KEY: self.jwk, bootstrap.KID_KEY: "fresh-kid"}

    def test_preview_is_server_dry_run_and_preserves_existing_keys(self):
        fake = FakeKubectl()
        with patch.dict(os.environ, self.environment, clear=True), patch.object(bootstrap.subprocess, "run", fake):
            self.assertEqual(0, bootstrap.run(True))
        self.assertIn("--dry-run=server", fake.commands[-1])
        self.assertEqual(b64("mnema"), fake.payloads[0]["data"]["POSTGRES_USER"])
        self.assertEqual("7", fake.payloads[0]["metadata"]["resourceVersion"])
        self.assertEqual("secret-uid", fake.payloads[0]["metadata"]["uid"])
        self.assertEqual({"owner": "mnema"}, fake.payloads[0]["metadata"]["labels"])
        self.assertEqual("initialized",
                         fake.payloads[0]["metadata"]["annotations"]["mnema.app/bootstrap-state"])

    def test_matching_existing_state_is_idempotent(self):
        with patch.dict(os.environ, self.environment, clear=True):
            expected = bootstrap.desired()
        fake = FakeKubectl({"POSTGRES_USER": b64("mnema"), **expected})
        with patch.dict(os.environ, self.environment, clear=True), patch.object(bootstrap.subprocess, "run", fake):
            self.assertEqual(0, bootstrap.run(False))
        self.assertEqual(1, len(fake.commands))

    def test_partial_or_different_existing_state_fails_without_mutation(self):
        for data in ({bootstrap.KID_KEY: b64("fresh-kid")},
                     {bootstrap.KID_KEY: b64("other"), bootstrap.JWK_KEY: b64(self.jwk)}):
            fake = FakeKubectl(data)
            with self.subTest(data=data), patch.dict(os.environ, self.environment, clear=True), \
                    patch.object(bootstrap.subprocess, "run", fake), self.assertRaises(bootstrap.BootstrapFailure):
                bootstrap.run(False)
            self.assertEqual(1, len(fake.commands))

    def test_invalid_or_missing_private_key_is_rejected_before_cluster_read(self):
        incoherent = json.loads(self.jwk)
        incoherent["keys"][0]["p"] = "AQ"
        for document in ("", "{}", json.dumps({"keys": [{"kty": "RSA", "kid": "fresh-kid", "n": "AA"}]}),
                         json.dumps(incoherent)):
            fake = FakeKubectl()
            environment = {bootstrap.JWK_KEY: document, bootstrap.KID_KEY: "fresh-kid"}
            with self.subTest(document=document), patch.dict(os.environ, environment, clear=True), \
                    patch.object(bootstrap.subprocess, "run", fake), self.assertRaises(bootstrap.BootstrapFailure):
                bootstrap.run(False)
            self.assertEqual([], fake.commands)


if __name__ == "__main__":
    unittest.main()
