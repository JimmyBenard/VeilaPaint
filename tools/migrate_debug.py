#!/usr/bin/env python3
"""Offline ADB backup and restore for Veila Paint debug APKs. No automatic uninstall."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tarfile

PACKAGE = "studio.nuvora.veila"


def validate_archive(path):
    """Read every file and build a content manifest before any installation or restoration."""
    manifest = {}
    with tarfile.open(path, "r:") as archive:
        for entry in archive:
            relative = PurePosixPath(entry.name)
            if relative.is_absolute() or ".." in relative.parts or not relative.parts or relative.parts[0] != "files":
                raise ValueError("Chemin inattendu dans la sauvegarde.")
            if entry.isdir():
                continue
            if not entry.isfile():
                raise ValueError("Lien ou fichier spécial refusé.")
            if entry.name in manifest:
                raise ValueError("Fichier dupliqué dans la sauvegarde.")
            source = archive.extractfile(entry)
            digest = hashlib.sha256()
            count = 0
            while chunk := source.read(1024 * 1024):
                count += len(chunk)
                digest.update(chunk)
            if count != entry.size:
                raise ValueError("Sauvegarde tronquée.")
            manifest[entry.name] = {"bytes": count, "sha256": digest.hexdigest()}
    if not manifest:
        raise ValueError("Sauvegarde vide. Aucune opération sur la tablette.")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["backup", "restore", "verify"])
    parser.add_argument("archive", type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", help="Numéro de série ADB si plusieurs appareils sont connectés")
    args = parser.parse_args()
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])

    def run(*command, **kwargs):
        return subprocess.run(adb + list(command), check=True, **kwargs)

    if args.action == "backup":
        if args.archive.exists():
            raise FileExistsError("Choisissez un nouveau nom : une sauvegarde existante ne sera pas écrasée.")
        # Leave Atelier normally first so the stroke, clock and MP4 are committed.
        run("shell", "am", "force-stop", PACKAGE)
        pending = args.archive.with_suffix(args.archive.suffix + ".pending")
        with pending.open("xb") as output:
            run("exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "files", stdout=output)
            output.flush()
            os.fsync(output.fileno())
        manifest = validate_archive(pending)
        pending.rename(args.archive)
        args.archive.with_suffix(args.archive.suffix + ".sha256.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Sauvegarde vérifiée : {len(manifest)} fichiers, {args.archive.resolve()}")
        print("Conservez cette archive et son manifeste avant de remplacer l’application.")
    elif args.action == "verify":
        manifest = validate_archive(args.archive)
        sidecar = args.archive.with_suffix(args.archive.suffix + ".sha256.json")
        if sidecar.exists():
            if manifest != json.loads(sidecar.read_text(encoding="utf-8")):
                raise ValueError("Les empreintes diffèrent du manifeste. Ne remplacez pas l’application.")
        print(f"Archive valide : {len(manifest)} fichiers.")
    else:
        expected = validate_archive(args.archive)
        sidecar = args.archive.with_suffix(args.archive.suffix + ".sha256.json")
        if not sidecar.is_file() or expected != json.loads(sidecar.read_text(encoding="utf-8")):
            raise ValueError("Manifeste SHA-256 absent ou différent. Restauration refusée.")
        run("shell", "am", "force-stop", PACKAGE)
        # Do not overwrite projects created after installation: use a fresh installation.
        existing = subprocess.run(
            adb + ["exec-out", "run-as", PACKAGE, "ls", "files/projects"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if existing.returncode == 0 and existing.stdout.strip():
            raise RuntimeError("La nouvelle installation contient déjà des projets. Sauvegardez-les avant de restaurer.")
        with args.archive.open("rb") as source:
            run("shell", "-T", "run-as", PACKAGE, "tar", "-xf", "-", stdin=source)
        # Roundtrip readback confirms all bytes before advising the user to reopen Atelier.
        check_path = args.archive.with_suffix(args.archive.suffix + ".restored-check")
        try:
            with check_path.open("xb") as output:
                run("exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "files", stdout=output)
            actual = validate_archive(check_path)
            if any(actual.get(name) != value for name, value in expected.items()):
                raise RuntimeError("La vérification après restauration a échoué. Gardez la sauvegarde et ne dessinez pas encore.")
        finally:
            check_path.unlink(missing_ok=True)
        print("Restauration vérifiée. Vous pouvez ouvrir Veila Paint. Gardez la sauvegarde.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError, tarfile.TarError) as error:
        raise SystemExit(f"Arrêt : {error}")
