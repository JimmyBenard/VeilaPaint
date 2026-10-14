# Architecture — Veila Paint 0.4.0

## Invariants produit

Le `Project` porte un plan de travail fixe (`width`, `height`, caméra) et une liste ordonnée de calques. Chaque calque possède une image normalisée sans recadrage et un `EngineSnapshot` indépendant. `currentStep` est conservé pour la compatibilité binaire mais désigne désormais l’index du **calque actif**.

Le pinceau ne modifie que le `MaskGrid` actif, en valeurs entières 0…1000. La progression est recalculée par la somme des pixels du masque. Les pixels des images sources ne sont jamais modifiés. Changer ou réordonner un calque conserve son masque par UUID.

## Flux Atelier

`AndroidInputMapper` copie les `MotionEvent`, y compris outil, source, pression, inclinaison, gomme et bouton. `GestureRouter` garantit un seul écrivain et bloque le pinceau jusqu’au relâchement complet après multi-touch. `StudioSession` cible le moteur actif, sérialise les changements et transmet des checkpoints immuables au fil d’écriture.

Le rendu OpenGL ES utilise une texture de composition bornée pour les autres calques, la texture du calque actif et une texture R16UI pour son masque. Le fragment shader applique la transparence progressive et, seulement à l’écran, l’overlay rouge. Zoom, pan et rotation ne modifient que la caméra ; l’inverse de la caméra convertit le point écran en pixel intrinsèque avant le pinceau.

## Historique et sauvegarde

Chaque snapshot conserve ses propres listes Undo/Redo sous forme de différences XOR compressées par tuiles 64 × 64. Le Reset est une action normale et annulable. Une sauvegarde contient JSON schéma 3 puis un bundle versionné de snapshots associés aux UUID des calques. `AtomicSlots` conserve deux générations avec CRC et remplacement atomique.

La lecture d’un état 0.3 reconnaît l’ancien snapshot séquentiel : les calques précédents deviennent révélés à 100 %, le calque courant reprend exactement le masque historique et les calques futurs restent à 0 %. Sur redimensionnement du plan, chaque masque est rééchantillonné au plus proche ; l’historique, dépendant du pavage, est réinitialisé, sans supprimer les images ni le temps.

## Vidéo et consultations

`StudioRecorder` encode uniquement la toile ou la fenêtre de l’application, sans MediaProjection. Avant l’affichage du rouge ou du pourcentage, `MediaRecorder.pause()` doit avoir réussi. La reprise automatique nécessite que les deux outils soient cachés, puis attend deux frames UI/GL afin d’éviter un frame résiduel.

Books, couvertures, vidéos et palette préparatoire restent séparés du moteur de masque.

