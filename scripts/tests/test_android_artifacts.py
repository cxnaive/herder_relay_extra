"""Offline tests use synthetic APKs and a fake signer, never a personal keystore."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "android-artifacts.py"
ABIS = ("armeabi-v7a", "arm64-v8a", "x86_64")
CERTIFICATE = "a" * 64


class AndroidArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="herdr-artifacts-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "build"
        self.collected = self.root / "ci"
        self.signed = self.root / "signed"
        self.manifest = self.root / "VERSION.json"
        self.manifest.write_text(json.dumps({"android": {
            "version": "0.2.22", "version_code": 25, "signing_certificate_sha256": CERTIFICATE,
        }}))
        for kind in ("debug", "release"):
            directory = self.source / kind
            directory.mkdir(parents=True)
            elements = []
            for abi in ABIS:
                name = f"app-{abi}-{kind}.apk"
                (directory / name).write_bytes(f"synthetic APK {kind} {abi}".encode())
                elements.append({"filters": [{"filterType": "ABI", "value": abi}],
                                 "outputFile": name, "versionName": "0.2.22", "versionCode": 25})
            (directory / "output-metadata.json").write_text(json.dumps({
                "applicationId": "dev.herdr.companion", "elements": elements,
            }))
        self.signer = self.root / "apksigner"
        self.signer.write_text('''#!/usr/bin/env python3
import os, pathlib, shutil, sys
args = sys.argv[1:]
assert 'HERDR_ANDROID_KEYSTORE_BASE64' not in os.environ
if os.environ.get('FAKE_SIGNER_FAIL'):
    print('SECRET-KEY-SENTINEL STORE-PASSWORD-SENTINEL', file=sys.stderr)
    sys.exit(1)
if args[0] == 'sign':
    key = pathlib.Path(args[args.index('--ks') + 1])
    assert key.read_bytes() == b'SECRET-KEY-SENTINEL'
    assert key.stat().st_mode & 0o777 == 0o600
    assert args[args.index('--ks-pass') + 1] == 'env:HERDR_ANDROID_KEYSTORE_PASSWORD'
    assert args[args.index('--key-pass') + 1] == 'env:HERDR_ANDROID_KEY_PASSWORD'
    assert args[args.index('--v4-signing-enabled') + 1] == 'false'
    shutil.copyfile(args[-1], args[args.index('--out') + 1])
else:
    assert args[:3] == ['verify', '--verbose', '--print-certs']
    print('Signer #1 certificate SHA-256 digest: ' + os.environ['FAKE_CERTIFICATE'])
''')
        self.signer.chmod(0o755)
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("HERDR_ANDROID_")}
        self.env.update({
            "RUNNER_TEMP": str(self.root), "FAKE_CERTIFICATE": CERTIFICATE,
            "HERDR_ANDROID_KEYSTORE_BASE64": base64.b64encode(b"SECRET-KEY-SENTINEL").decode(),
            "HERDR_ANDROID_KEYSTORE_PASSWORD": "STORE-PASSWORD-SENTINEL",
            "HERDR_ANDROID_KEY_ALIAS": "fixture", "HERDR_ANDROID_KEY_PASSWORD": "KEY-PASSWORD-SENTINEL",
        })

    def run_script(self, mode, *, success=True):
        source, output = (self.source, self.collected) if mode == "collect" else (self.collected, self.signed)
        result = subprocess.run([sys.executable, str(SCRIPT), mode, str(source), str(output),
                                 "--manifest", str(self.manifest), "--apksigner", str(self.signer)],
                                env=self.env, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        for sentinel in ("SECRET-KEY-SENTINEL", "STORE-PASSWORD-SENTINEL", "KEY-PASSWORD-SENTINEL",
                         self.env["HERDR_ANDROID_KEYSTORE_BASE64"]):
            self.assertNotIn(sentinel, result.stdout + result.stderr)
        self.assertEqual(list(self.root.glob("herdr-signing-*")), [])
        self.assertEqual(list(self.root.glob(".android-artifacts-*")), [])
        if not success:
            self.assertFalse(output.exists())
        return result

    def alter_metadata(self, mutate):
        path = self.source / "debug/output-metadata.json"
        value = json.loads(path.read_text())
        mutate(value)
        path.write_text(json.dumps(value))

    def test_collect_labels_unsigned_and_ci_debug_and_checksums(self):
        self.run_script("collect")
        records = json.loads((self.collected / "BUILD-INFO.json").read_text())["artifacts"]
        self.assertEqual(len(records), 6)
        for item in records:
            self.assertIn(item["signing"], ("ci-debug", "unsigned"))
            suffix = "-ci.apk" if item["build_type"] == "debug" else "-unsigned.apk"
            self.assertTrue(item["file"].endswith(suffix))
        for line in (self.collected / "SHA256SUMS.txt").read_text().splitlines():
            digest, name = line.split("  ")
            self.assertEqual(hashlib.sha256((self.collected / name).read_bytes()).hexdigest(), digest)

    def test_signs_all_apks_and_pins_the_existing_certificate(self):
        self.run_script("collect")
        self.run_script("sign")
        info = json.loads((self.signed / "BUILD-INFO.json").read_text())
        self.assertEqual(len(info["artifacts"]), 6)
        self.assertEqual(len(list(self.signed.iterdir())), 8)
        for item in info["artifacts"]:
            self.assertEqual(item["certificate_sha256"], CERTIFICATE)
            self.assertEqual(item["signing"], "existing-installation")
            self.assertFalse(item["file"].endswith(("-unsigned.apk", "-ci.apk")))

    def test_wrong_certificate_publishes_nothing(self):
        self.run_script("collect")
        self.env["FAKE_CERTIFICATE"] = "b" * 64
        self.assertIn("certificate differs", self.run_script("sign", success=False).stderr)

    def test_signer_errors_do_not_leak_secret_output(self):
        self.run_script("collect")
        self.env["FAKE_SIGNER_FAIL"] = "1"
        self.run_script("sign", success=False)

    def test_missing_secret_does_not_fall_back_to_a_debug_key(self):
        self.run_script("collect")
        del self.env["HERDR_ANDROID_KEY_ALIAS"]
        self.run_script("sign", success=False)

    def test_tampered_apk_is_not_signed(self):
        self.run_script("collect")
        next(self.collected.glob("*.apk")).write_bytes(b"changed after collection")
        self.assertIn("checksum mismatch", self.run_script("sign", success=False).stderr)

    def test_missing_abi_is_not_silently_omitted(self):
        self.alter_metadata(lambda value: value["elements"].pop())
        self.run_script("collect", success=False)

    def test_repeated_abi_is_rejected(self):
        self.alter_metadata(lambda value: value["elements"].__setitem__(1, value["elements"][0]))
        self.run_script("collect", success=False)

    def test_wrong_version_is_rejected(self):
        self.alter_metadata(lambda value: value["elements"][0].update(versionCode=24))
        self.run_script("collect", success=False)

    def test_path_escape_is_rejected(self):
        self.alter_metadata(lambda value: value["elements"][0].update(outputFile="../../private.apk"))
        self.run_script("collect", success=False)

    def test_symlink_is_rejected(self):
        apk = next((self.source / "debug").glob("*.apk"))
        apk.unlink()
        apk.symlink_to(self.manifest)
        self.run_script("collect", success=False)

    def test_existing_output_is_never_overwritten(self):
        self.run_script("collect")
        original = (self.collected / "BUILD-INFO.json").read_bytes()
        result = subprocess.run([sys.executable, str(SCRIPT), "collect", str(self.source), str(self.collected),
                                 "--manifest", str(self.manifest)], capture_output=True, text=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.collected / "BUILD-INFO.json").read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
