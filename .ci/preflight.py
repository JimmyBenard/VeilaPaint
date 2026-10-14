#!/usr/bin/env python3
"""Offline source checks. Does not run Gradle or contact the network."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def verify_source(root=ROOT):
    manifest = json.loads((root / '.ci/source-manifest.json').read_text())
    for name, expected in manifest['unchanged_files'].items():
        path = root / name
        require(path.is_file(), f'Missing source file: {name}')
        require(hashlib.sha256(path.read_bytes()).hexdigest() == expected,
                f'Source differs from checkpoint 04: {name}')
    app = (root / 'app/build.gradle.kts').read_text()
    require(hashlib.sha256(app.encode()).hexdigest() == manifest['app_build_gradle_sha256'],
            'Unexpected app Gradle configuration change')
    for name in ('applicationId', 'versionName', 'versionCode', 'compileSdk', 'targetSdk'):
        value = manifest[name]
        literal = f'"{value}"' if isinstance(value, str) else str(value)
        require(re.search(rf'^\s*{name}\s*=\s*{re.escape(literal)}\s*$', app, re.M),
                f'Unexpected {name}')
    wrapper = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text()
    require('gradle-8.11.1-bin.zip' in wrapper, 'Gradle must remain 8.11.1')
    require((root / 'gradle/wrapper/gradle-wrapper.jar').is_file(), 'Wrapper JAR missing')
    print('Source 0.4.0 verified: original files unchanged; signing supplied by environment.')
    return manifest


if __name__ == '__main__':
    verify_source()
