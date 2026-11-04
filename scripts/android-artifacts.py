#!/usr/bin/env python3
"""Collect ABI APKs and sign trusted builds without exposing keys to Gradle."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ABIS = ("armeabi-v7a", "arm64-v8a", "x86_64")
KINDS = ("debug", "release")
SECRET_NAMES = (
    "HERDR_ANDROID_KEYSTORE_BASE64", "HERDR_ANDROID_KEYSTORE_PASSWORD",
    "HERDR_ANDROID_KEY_ALIAS", "HERDR_ANDROID_KEY_PASSWORD",
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def filename(version, kind, abi, signed=False):
    suffix = "" if signed else "-ci" if kind == "debug" else "-unsigned"
    return f"Herdr-Pocket-{version}-{kind}-{abi}{suffix}.apk"


def artifact(path, kind, abi, signing, certificate=None):
    value = {"file": path.name, "build_type": kind, "abi": abi,
             "signing": signing, "sha256": digest(path)}
    if certificate:
        value["certificate_sha256"] = certificate
    return value


def collect(source, output, android):
    artifacts = []
    for kind in KINDS:
        metadata = json.loads((source / kind / "output-metadata.json").read_text())
        require(metadata.get("applicationId") == "dev.herdr.companion", "Unexpected application ID")
        elements = metadata["elements"]
        require(len(elements) == len(ABIS), "Build with -PsplitApks=true: expected three ABI APKs")
        seen = set()
        for element in elements:
            filters = element.get("filters", [])
            require(len(filters) == 1 and filters[0].get("filterType") == "ABI", "Unexpected APK split")
            abi = filters[0].get("value")
            require(abi in ABIS and abi not in seen, "Missing, repeated or unsupported APK ABI")
            seen.add(abi)
            require(element["versionName"] == android["version"] and
                    element["versionCode"] == android["version_code"], "APK version differs from VERSION.json")
            name = element["outputFile"]
            require(Path(name).name == name and name.endswith(".apk"), "Unsafe APK output path")
            path = source / kind / name
            require(path.is_file() and not path.is_symlink(), "APK must be a regular file")
            target = output / filename(android["version"], kind, abi)
            shutil.copyfile(path, target)
            artifacts.append(artifact(target, kind, abi, "ci-debug" if kind == "debug" else "unsigned"))
    return artifacts


def signer_command(arguments, environment):
    result = subprocess.run(arguments, env=environment, capture_output=True, text=True, check=False)
    # Never include subprocess arguments or output in errors from a secret-bearing step.
    require(result.returncode == 0, "APK signer failed; no signed artifacts were published")
    return result.stdout


def sign(source, output, android, signer):
    require(all(os.environ.get(name) for name in SECRET_NAMES), "Android signing secrets are incomplete")
    certificate = android["signing_certificate_sha256"].lower()
    require(re.fullmatch(r"[0-9a-f]{64}", certificate), "Invalid expected signing certificate")
    info = json.loads((source / "BUILD-INFO.json").read_text())
    require(info["version"] == android["version"] and info["version_code"] == android["version_code"],
            "Input APK build version differs from VERSION.json")
    records = {entry["file"]: entry for entry in info["artifacts"]}
    expected = {filename(android["version"], kind, abi) for kind in KINDS for abi in ABIS}
    require(set(records) == expected and len(info["artifacts"]) == len(expected), "Incomplete APK input manifest")
    for name in expected:
        path = source / name
        require(path.is_file() and not path.is_symlink(), "APK must be a regular file")
        require(digest(path) == records[name]["sha256"], "Input APK checksum mismatch")
    child_env = os.environ.copy()
    for name in ("HERDR_ANDROID_KEYSTORE_BASE64", "HERDR_ANDROID_KEY_ALIAS"):
        child_env.pop(name, None)
    artifacts = []
    with tempfile.TemporaryDirectory(prefix="herdr-signing-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
        keystore = Path(temporary) / "signing.keystore"
        # The key never enters the checkout, build cache, Gradle, or uploaded directories.
        with keystore.open("xb") as handle:
            os.chmod(keystore, 0o600)
            handle.write(base64.b64decode(os.environ[SECRET_NAMES[0]], validate=True))
        for kind in KINDS:
            for abi in ABIS:
                source_apk = source / filename(android["version"], kind, abi)
                target = output / filename(android["version"], kind, abi, signed=True)
                signer_command([
                    signer, "sign", "--ks", str(keystore),
                    "--ks-key-alias", os.environ["HERDR_ANDROID_KEY_ALIAS"],
                    "--ks-pass", "env:HERDR_ANDROID_KEYSTORE_PASSWORD",
                    "--key-pass", "env:HERDR_ANDROID_KEY_PASSWORD",
                    "--v4-signing-enabled", "false", "--out", str(target), str(source_apk),
                ], child_env)
                report = signer_command([signer, "verify", "--verbose", "--print-certs", str(target)], child_env)
                digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", report, re.MULTILINE)
                require(digests == [certificate], "APK certificate differs from the existing installation")
                artifacts.append(artifact(target, kind, abi, "existing-installation", certificate))
    return artifacts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("collect", "sign"))
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--manifest", type=Path, default=Path(__file__).resolve().parent.parent / "VERSION.json")
    parser.add_argument("--apksigner", default=str(Path(os.environ.get("ANDROID_HOME", "")) / "build-tools/35.0.0/apksigner"))
    args = parser.parse_args()
    android = json.loads(args.manifest.read_text())["android"]
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?", android["version"]), "Invalid Android version")
    require(not args.output.exists(), "Refusing to overwrite existing APK artifacts")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".android-artifacts-", dir=args.output.parent) as temporary:
        staging = Path(temporary) / "apks"
        staging.mkdir()
        artifacts = (collect(args.source, staging, android) if args.mode == "collect" else
                     sign(args.source, staging, android, args.apksigner))
        artifacts.sort(key=lambda entry: entry["file"])
        info = {"schema": 1, "version": android["version"], "version_code": android["version_code"],
                "revision": os.environ.get("GITHUB_SHA", "local"), "artifacts": artifacts}
        manifest = staging / "BUILD-INFO.json"
        manifest.write_text(json.dumps(info, indent=2) + "\n")
        sums = [f'{entry["sha256"]}  {entry["file"]}\n' for entry in artifacts]
        sums.append(f"{digest(manifest)}  BUILD-INFO.json\n")
        (staging / "SHA256SUMS.txt").write_text("".join(sums))
        staging.rename(args.output)
    print(f"{args.mode}: verified {len(artifacts)} APKs; output: {args.output}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        raise SystemExit(f"Android artifacts: {error}") from None
