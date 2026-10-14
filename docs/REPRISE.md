# État de reprise — Veila Paint 0.4.0

Au 10 septembre 2026, le source 0.4.0 comprend le modèle de calques indépendants, la migration 0.3, le plan de travail redimensionnable, la session par calque, le composite OpenGL, les gestes 2/3/4/5 doigts, les consultations vidéo sûres, le Reset, les nouveaux réglages Pinceau et les tests associés.

Corrections de la dernière reprise :

- décodage correct de l’enveloppe binaire des bundles de calques ;
- snapshot multi-calques capturé avec ses métadonnées dans la file de sauvegarde ;
- index UI issu du calque actif et non du moteur local à deux états ;
- conservation des UUID/masques au réordonnancement et rééchantillonnage au changement de plan ;
- blocage de la reprise vidéo jusqu’à disparition des deux consultations et barrière de rendu ;
- composite mémoire borné qui ne décompresse pas vingt images simultanément.

Validation locale obtenue : contrôle des délimiteurs Kotlin et 4 tests Python de sauvegarde/migration. La validation Gradle n’a pas pu être exécutée dans ce conteneur précis : Gradle et le SDK Android sont absents, et le réseau autorisé ne peut pas joindre la distribution du wrapper. Ne pas annoncer un APK 0.4.0 avant une exécution réussie de `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

