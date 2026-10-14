#!/usr/bin/env python3
"""Verify real APK bytes with the Android SDK, then write build provenance."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(*args):
    result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True, timeout=60)
    require(result.returncode == 0, result.stderr.strip() or f'Tool failed: {args[0]}')
    return result.stdout


def verify():
    root = Path(__file__).resolve().parents[1]
    expected = json.loads((root / '.ci/source-manifest.json').read_text())
    apk = root / 'dist/VeilaPaint-0.4.0-debug.apk'
    require(apk.is_file() and apk.stat().st_size > 0, 'APK missing or empty')
    with zipfile.ZipFile(apk) as archive:
        require(archive.testzip() is None, 'APK ZIP integrity failure')
        require({'AndroidManifest.xml', 'classes.dex'} <= set(archive.namelist()),
                'APK must contain its Android manifest and compiled DEX code')
    sdk = Path(os.environ['ANDROID_HOME'])
    build_tools = sdk / 'build-tools/35.0.0'
    badging = command(build_tools / 'aapt2', 'dump', 'badging', apk)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    require(package is not None, 'Unable to read package metadata')
    package_id, version_code, version_name = package.groups()
    require((package_id, version_code, version_name) ==
            (expected['applicationId'], str(expected['versionCode']), expected['versionName']),
            'APK package ID or version mismatch')
    launcher = re.search(r"^launchable-activity: name='([^']+)'", badging, re.M)
    require(launcher is not None and launcher.group(1) == expected['mainActivity'],
            'Main activity mismatch')
    require(re.search(r'^application-debuggable(?:\s|$)', badging, re.M), 'APK is not debuggable')
    require("targetSdkVersion:'35'" in badging, 'APK targetSdk must equal 35')
    metadata = json.loads((root / 'app/build/outputs/apk/debug/output-metadata.json').read_text())
    require(metadata.get('variantName') == 'debug', 'Unexpected build variant')
    signature = command(build_tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
    digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', signature)
    require(len(digests) == 1 and digests[0].upper() == expected['expected_certificate_sha256'],
            'APK signer differs from historical certificate')
    command(build_tools / 'zipalign', '-c', '4', apk)
    commit = os.environ.get('GITHUB_SHA', '')
    require(re.fullmatch(r'[0-9a-f]{40}', commit), 'Missing GitHub build commit')
    source = root / 'dist/VeilaPaint-Android-0.4.0-source.zip'
    command('git', '-C', root, 'diff', '--exit-code', 'HEAD')
    command('git', '-C', root, 'archive', '--format=zip', '--prefix=VeilaPaint/',
            '--output=' + str(source), commit)
    report = {
        'file': apk.name, 'size_bytes': apk.stat().st_size,
        'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'applicationId': package_id, 'versionCode': int(version_code), 'versionName': version_name,
        'targetSdk': 35, 'mainActivity': launcher.group(1), 'variant': 'debug',
        'certificate_sha256': digests[0].upper(), 'apk_signature_verified': True,
        'zip_integrity_verified': True, 'zipalign_verified': True,
        'commit_sha': commit, 'repository': os.environ.get('GITHUB_REPOSITORY'),
        'run_id': os.environ.get('GITHUB_RUN_ID'),
        'source_file': source.name, 'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'checkpoint_sha256': expected['checkpoint_sha256'],
    }
    (root / 'dist/verification.json').write_text(json.dumps(report, indent=2) + '\n')
    (root / 'dist/SHA256SUMS').write_text(
        f"{report['sha256']}  {apk.name}\n{report['source_sha256']}  {source.name}\n")
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    verify()
