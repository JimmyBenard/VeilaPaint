#!/usr/bin/env python3
"""Restore and verify historical signing material on the private Actions runner."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / '.ci/source-manifest.json').read_text())
required = ('VEILA_KEYSTORE_BASE64', 'VEILA_KEY_ALIAS', 'VEILA_STORE_PASSWORD', 'VEILA_KEY_PASSWORD')
missing = [name for name in required if not os.environ.get(name)]
if missing:
    raise SystemExit('Missing GitHub Secrets: ' + ', '.join(missing))
try:
    data = base64.b64decode(os.environ['VEILA_KEYSTORE_BASE64'].strip(), validate=True)
except ValueError:
    raise SystemExit('VEILA_KEYSTORE_BASE64 is invalid') from None
if hashlib.sha256(data).hexdigest() != manifest['keystore_sha256']:
    raise SystemExit('The keystore does not match validated checkpoint 04')
path = root / 'signing/veila-development.jks'
path.parent.mkdir(exist_ok=True)
os.umask(0o077)
path.write_bytes(data)
path.chmod(0o600)
result = subprocess.run([
    'keytool', '-exportcert', '-keystore', str(path),
    '-alias', os.environ['VEILA_KEY_ALIAS'], '-storepass:env', 'VEILA_STORE_PASSWORD',
], capture_output=True, timeout=30)
if result.returncode:
    raise SystemExit('Cannot read historical certificate: check alias and store password')
fingerprint = hashlib.sha256(result.stdout).hexdigest().upper()
if fingerprint != manifest['expected_certificate_sha256']:
    raise SystemExit('Certificate mismatch: build stopped before compilation')
print('Historical certificate SHA-256: ' + fingerprint)
