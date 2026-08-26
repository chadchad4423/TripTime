#!/usr/bin/env python3
"""Encrypt an OpenRouteService key for TripTime's remote config (DECISIONS.md D-020).

    python tools/encrypt-config-key.py

Reads CONFIG_DECRYPT_KEY from local.properties, prompts for the API key to wrap, and prints the
value to paste into docs/config.json as "apiKeyEncrypted".

Why encrypt at all: it stops the key being picked up by scrapers and secret-scanners that
pattern-match public files. It does NOT stop someone who decompiles the APK, because the
decryption key is inside it -- which is exactly the protection the embedded key already had. The
gain is the ability to rotate the key without shipping a release, at parity, not better secrecy.

Only put a key in config.json during an emergency, and make it a freshly-created one you are
prepared to lose. Clear the field again once a release with a new embedded key has gone out.
"""
import base64
import getpass
import os
import sys

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    sys.exit("Needs 'cryptography': python -m pip install cryptography")

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read_decrypt_key() -> bytes:
    path = os.path.join(HERE, "local.properties")
    if not os.path.exists(path):
        sys.exit("local.properties not found -- run this from the project root.")
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("CONFIG_DECRYPT_KEY="):
                return base64.b64decode(line.split("=", 1)[1].strip())
    sys.exit("CONFIG_DECRYPT_KEY not found in local.properties.")


def main() -> None:
    key = read_decrypt_key()
    if len(key) != 32:
        sys.exit(f"CONFIG_DECRYPT_KEY must decode to 32 bytes, got {len(key)}.")

    api_key = getpass.getpass("API key to encrypt (input hidden): ").strip()
    if not api_key:
        sys.exit("No key entered.")

    iv = os.urandom(12)
    blob = iv + AESGCM(key).encrypt(iv, api_key.encode("utf-8"), None)
    print('\n  "apiKeyEncrypted": "%s"\n' % base64.b64encode(blob).decode())
    print("Paste that line into docs/config.json, then push. Installed apps pick it up on their")
    print("next launch -- but only apply it if a request actually fails (the reserve design).")


if __name__ == "__main__":
    main()
