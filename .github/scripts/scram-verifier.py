#!/usr/bin/env python3
"""SCRAM-SHA-256-Verifier für PostgreSQL aus einem Passwort auf stdin (Deploy-Workflow, Aufgabe 1.9).

Aufruf: scram-verifier.py [<name des Secrets>] < passwort; der Name erscheint nur in der Fehlermeldung.

Der Datenbank-Container bekommt nur den Verifier, nie das Passwort der Rolle (deploy/infomaniak/db/10-archiv-rollen.sh).
Format wie PostgreSQL: SCRAM-SHA-256$<iterationen>:<salt>$<StoredKey>:<ServerKey>, alles Base64.
Nur druckbares ASCII ohne Leerzeichen, damit SASLprep (RFC 4013) das Passwort nicht verändert.
Ausgabe nur auf stdout, nie das Passwort.
"""
import base64
import hashlib
import hmac
import os
import sys

ITERATIONEN = 4096


def verifier(passwort: str, salt: bytes, iterationen: int = ITERATIONEN) -> str:
    gesalzen = hashlib.pbkdf2_hmac("sha256", passwort.encode("ascii"), salt, iterationen)
    client_key = hmac.new(gesalzen, b"Client Key", hashlib.sha256).digest()
    stored_key = hashlib.sha256(client_key).digest()
    server_key = hmac.new(gesalzen, b"Server Key", hashlib.sha256).digest()
    b64 = lambda b: base64.b64encode(b).decode("ascii")
    return f"SCRAM-SHA-256${iterationen}:{b64(salt)}${b64(stored_key)}:{b64(server_key)}"


def main() -> int:
    rolle = sys.argv[1] if len(sys.argv) > 1 else "Passwort"
    passwort = sys.stdin.read().removesuffix("\n")
    if len(passwort) < 16 or not all(33 <= ord(z) <= 126 for z in passwort):
        print(f"{rolle}: fehlt, ist kürzer als 16 Zeichen oder enthält anderes als druckbares ASCII ohne Leerzeichen. "
              "GitHub-Secret neu setzen (vor dem ersten Deploy; danach nur zusammen mit ALTER ROLE).",
              file=sys.stderr)
        return 1
    print(verifier(passwort, os.urandom(16)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
