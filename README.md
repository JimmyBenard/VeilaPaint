# Veila Paint 0.4.0

**Nuvora Studio — Redessiner → Dévoiler → Progresser.**

Application Android native tablette-first. Un plan de travail commun contient de 2 à 20 calques indépendants. Un doigt ou le stylet augmente le masque du calque actif ; les pixels sources ne sont jamais peints. Le masque réel reste l’unique source du pourcentage.

## Fonctions

- Préparation du plan de travail : formats 1:1, 4:5, 9:16, 16:9, 4K, dimensions personnalisées ou dimensions intrinsèques d’un calque.
- Calques renommables et réordonnables avec masque, progression et Undo/Redo propres.
- Atelier OpenGL ES 3 : composition du plan, masque R16UI, caméra zoom/pan/rotation et chargement à la demande des images.
- Gestes : 1 doigt ou stylet = dévoiler ; 2 doigts déplacés = caméra ; tap 2 doigts = zones non dévoilées rouges ; 3 = Undo ; 4 = Redo ; tap 5 = pourcentage temporaire.
- Pourcentage caché par défaut. Les deux outils de consultation mettent une vidéo en pause avant affichage et la reprise attend leur disparition ainsi qu’une barrière de rendu.
- Pinceau 10 px / 50 % par défaut, pression taille/intensité, dureté, gomme et Reset du calque actif.
- Palette de couleur préparatoire, volontairement sans connexion au moteur de dévoilement.
- Chronomètre cumulé, sauvegarde atomique à deux générations et migration non destructive des projets séquentiels 0.3.x.
- Enregistrement MP4 H.264, bibliothèque/aperçu, métadonnées et export accéléré de ×1 à ×200.
- Projets avec couverture, Books et bibliothèque vidéo conservés depuis la 0.3.0.

## Compiler

Prérequis : JDK 17 et Android SDK 35. Depuis la racine :

```bash
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

L’APK standard produit se trouve dans `app/build/outputs/apk/debug/app-debug.apk`. La livraison le renomme `VeilaPaint-0.4.0-debug.apk`.

La variante `preview` garde un identifiant d’application et un stockage distincts :

```bash
./gradlew --no-daemon :app:assemblePreview
```

## Installer

La clé `signing/veila-development.jks` est la clé de développement conservée depuis la 0.2.0. Pour une installation déjà signée avec cette clé :

```bash
adb install -r VeilaPaint-0.4.0-debug.apk
```

Ne désinstallez pas une ancienne version signée avec une autre clé avant d’avoir exécuté et vérifié la procédure de sauvegarde décrite dans `docs/MISE_A_JOUR.md`.

## Architecture et validation

- `core/` : masque, pinceau, historique compact par tuiles, caméra et machine à états des gestes.
- `app/.../data/` : projets/calques, migration, couvertures, Books et vidéos.
- `app/.../studio/` : session mono-écrivain, rendu OpenGL, entrées Android et enregistreur.
- `app/.../ui/` : interface Compose tablette-first.
- `docs/ARCHITECTURE.md` : invariants et format 0.4.
- `docs/TESTS_TABLETTE.md` : recette obligatoire sur matériel réel.
- `docs/LIMITATIONS.md` : limites connues et périmètre de validation.

Les fonctions de stylet, pression, rejet de paume, codecs 60 FPS et latence doivent être validées sur les tablettes physiques visées ; aucun émulateur ne peut certifier ces propriétés matérielles.

