# Installer Veila Paint 0.4.0

## Mise à jour standard

L’APK standard garde `applicationId = studio.nuvora.veila`, `versionCode = 4` et la clé de développement conservée dans `signing/`.

```bash
adb install -r VeilaPaint-0.4.0-debug.apk
```

Android conserve alors les données si la version déjà installée porte la même signature.

## Migration des projets 0.3.x

La migration est automatique à l’ouverture et ne supprime aucun projet. L’ancien état séquentiel devient un ensemble de calques indépendants : calques déjà franchis à 100 %, calque courant avec son masque exact, calques futurs à 0 %. Images, temps, caméra, paramètres et couverture sont conservés. Le premier enregistrement réécrit le projet au format bundle 0.4 atomique.

Si le plan de travail est redimensionné, les masques sont rééchantillonnés. Les historiques de tuiles des calques redimensionnés sont les seules données non convertibles exactement et repartent vides ; l’état visuel du masque est conservé.

## Ancienne signature inconnue

Une installation signée avec une clé perdue ne peut pas être remplacée par Android. Avant toute désinstallation :

```bash
python3 tools/migrate_debug.py backup --adb adb --out veila-backup.tar
python3 tools/migrate_debug.py verify veila-backup.tar
```

Après seulement une vérification réussie, installer la 0.4.0 puis suivre l’aide de l’outil pour restaurer. Garder l’archive jusqu’à contrôle des images, noms, masques, progressions, temps et historiques.

