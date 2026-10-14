# Recette manuelle tablette / stylet — 0.4.0

Consigner pour chaque essai : modèle de tablette, version Android, RAM, stylet, version de l’APK, format de toile, qualité vidéo et résultat observé. Commencer en 720p/30 FPS, pression désactivée, écran déverrouillé. Garder une copie externe des images d’essai.

## Validation prioritaire 0.4.0

| Essai | Procédure | Résultat obligatoire |
| --- | --- | --- |
| C1 | Créer huit calques. | Noms par défaut Page blanche, Croquis, Croquis détaillé, trois Colorisations, Calque 7 et Calque 8. |
| C2 | Renommer et réordonner deux calques. | Le nom suit son UUID ; aucun renommage automatique. |
| C3 | Choisir « Dimensions d’un calque ». | Le plan adopte les dimensions intrinsèques ; la source n’est ni étirée ni remplacée. |
| C4 | Dévoiler A, passer à B, dévoiler B, revenir à A. | Les deux masques et pourcentages restent strictement indépendants. |
| C5 | Sauvegarder, forcer l’arrêt, rouvrir puis réordonner. | Calque actif, ordre, masques, taux, caméra et temps restaurés. |
| G1 | Dessiner à un doigt puis ajouter un second doigt. | Trait interrompu immédiatement ; navigation sans pixel parasite ; le doigt restant ne reprend pas le trait. |
| G2 | Pinch, pan et rotation à deux doigts. | Caméra fluide ; aucun masque ni taux ne change. |
| G3 | Maintenir deux doigts plus de 280 ms. | Aucun overlay rouge. |
| G4 | Tap bref à deux doigts, puis second tap. | Zones incomplètes rouges, puis overlay absent ; aucun pixel source/masque modifié. |
| G5 | Tap trois puis quatre doigts après un trait. | Undo puis Redo exacts sur le calque concerné. |
| G6 | Tap cinq doigts deux fois. | Pourcentage du calque actif visible puis caché ; geste absent de la légende inférieure. |
| P1 | Ouvrir Pinceau dans un nouveau projet. | 10 px, Intensité 50 %, piste blanc → gris → noir. |
| P2 | Manipuler teinte, saturation et luminosité. | Aperçu de palette change ; image, masque, taux et intensité restent identiques. |
| P3 | Utiliser Reset puis Undo. | Calque actif à 0,00 %, puis masque exact restauré. |
| V1 | REC actif puis tap deux doigts. | Pause appliquée avant le premier frame rouge. Masquer l’overlay reprend seulement si le pourcentage est caché. |
| V2 | REC actif puis tap cinq doigts. | Pause appliquée avant l’affichage du taux. La reprise attend deux frames après sa disparition. |
| V3 | Activer rouge et taux, puis n’en masquer qu’un. | REC reste en pause jusqu’à disparition des deux outils. |

Répéter G1–G6 avec stylet natif + contacts de paume, en portrait puis paysage. Filmer extérieurement l’écran pour vérifier la latence et l’absence de frame d’overlay dans le MP4.

## Jeux d’essai

- Deux PNG opaques de même dimension et de couleurs différentes ; toile 64 × 64 personnalisée pour couvrir tout le masque d’un seul contact avec un pinceau de 200 px.
- Trois PNG distincts pour observer une transition puis un nouveau masque.
- Vingt images 3 840 × 2 160, dont une série avec transparence ; préparer aussi une sélection de 21 fichiers.
- JPEG portrait avec orientation EXIF, image panoramique, PNG corrompu contenant du texte et format non pris en charge par l’appareil.
- Série artistique de 6 étapes, parfaitement alignées, avec un petit repère dans chaque coin.

## Parcours fonctionnels

| # | Action | Résultat attendu |
| --- | --- | --- |
| 1 | Créer un projet avec deux images via Fichiers, puis un autre via Galerie. | Atelier accessible, première image visible, masque à 0 %, seconde invisible. |
| 2 | Importer vingt images, ouvrir le projet, revenir à Projets. | Vingt étapes ordonnées, vignettes visibles, pas de fermeture pour manque de mémoire. |
| 3 | Ajouter une sélection faisant dépasser vingt ; essayer d’ouvrir avec une seule image. | Sélection excessive refusée en entier ; Atelier inaccessible avec moins de deux étapes. |
| 4 | Importer le fichier corrompu puis une image valide. | Erreur compréhensible, pas de projet cassé, import valide encore possible. |
| 5 | Réordonner, renommer, prévisualiser, remplacer et supprimer une étape. | Ordre exact à l’Atelier. Réinitialisation confirmée seulement si la structure d’un projet déjà commencé change. |
| 6 | Choisir 1:1, 4:5, 9:16, 16:9 et un format personnalisé ; importer l’image EXIF. | Toile correcte, orientation EXIF respectée, image centrée sans déformation ni recadrage. |
| 7 | Sur la toile 64 × 64, dureté 100 %, intensité 10 %, pression désactivée, toucher neuf fois au centre au stylet, en levant à chaque passage. | 10,0 → 20,0 → … → 90,0 %. Un contact immobile prolongé ne continue pas à remplir le masque. |
| 8 | Effectuer le dixième passage, avec trois images, en filmant extérieurement l’écran à cadence élevée. | Image suivante immédiatement actuelle, nouveau taux 0 %. Aucun fondu, flash, zoom, célébration, vibration ni écran de transition. Le dessin reprend lorsque l’image suivante est effectivement chargée. |
| 9 | Annuler la complétion de l’essai 8, puis rétablir. Refaire au stade final. | Retour exact à l’ancienne étape à 90 % ; Redo remet l’étape suivante à 0 %. L’image finale reste annulable. |
| 10 | Tracer et glisser avec un doigt, puis désactiver « Dessiner avec 1 doigt » et refaire. | Activé : dévoilement continu avec le même masque que le stylet. Désactivé : aucun pixel modifié par un doigt. Un tap à deux ou cinq doigts ne laisse ni trait ni raccourci. |
| 11 | À un taux quelconque, noter sa valeur, zoomer, déplacer et tourner la feuille avec deux doigts pendant 30 s. | Même taux strictement ; masque visuellement aligné. Le repère sous le foyer du pinch reste attaché à ce foyer. |
| 12 | Faire un tap bref à trois doigts, puis à quatre, sans les déplacer. | Une seule annulation puis un seul rétablissement. Pixels et taux restaurés, aucun trait. |
| 13 | Pincer, ajouter un troisième doigt, relâcher ; maintenir trois doigts plus de 280 ms ; bouger quatre doigts. | Aucun raccourci non sollicité. |
| 14 | Tracer à un doigt, ajouter un deuxième doigt, zoomer puis ne garder qu’un doigt et le déplacer. Refaire avec le stylet plus un doigt, puis deux doigts. | Au deuxième doigt le trait cesse; aucun trait ne reprend avec le doigt restant. Nouveau dessin seulement après relâchement complet. Le stylet est l’unique écrivain quand il coexiste avec un doigt; deux doigts suspendent son dessin pour naviguer. |
| 15 | Tracer au même repère après zoom, pan, rotation à deux doigts (45°, 90°, 180°), puis changement portrait/paysage ; refaire à zoom fort. | Alignement intrinsèque inchangé. Même masque et même taux à la rotation. |
| 16 | Activer pression → taille, puis pression → intensité, varier la force ; tester gomme et bouton latéral enfoncé avant contact, avec et sans option « Bouton latéral → gomme ». | Bouton ignoré par défaut ; effets de pression et de bouton seulement lorsque leurs options sont actives ; gomme matérielle retire du dévoilement et s’annule. Un stylet sans axe facultatif reste utilisable. |
| 17 | Rester 60 s dans l’Atelier, quitter 30 s, revenir ; passer 30 s en arrière-plan et revenir. | Temps cumulé augmenté seulement des périodes de travail. Pause vidéo sans effet sur ce temps. Aucun effet du temps sur le taux. |
| 18 | Dessiner, modifier le pinceau et la caméra, attendre « Enregistré », forcer l’arrêt Android puis rouvrir. | Étape, masque, taux, temps enregistré, réglages et historique restaurés. Pas de progression issue d’un compteur indépendant. |
| 19 | Interrompre l’application pendant une sauvegarde ; sur appareil de test à stockage presque plein, provoquer un échec puis libérer de la place et réessayer. | Dernière génération valide récupérable ; erreur visible et reprise après sauvegarde réussie. Ne pas utiliser un projet précieux pour cet essai. |
| 20 | Renommer, dupliquer puis supprimer la copie ; rouvrir l’original. | Originaux et historique indépendants, confirmation de suppression, premier projet conservé. |

## Diagnostic du stylet et rotation de la feuille

- Poser le stylet sur la toile, ouvrir Paramètres → Entrée : noter le nom du périphérique, le type numérique et la source hexadécimale. Refaire au survol, à la pointe et à la gomme si disponible. Photographier ce diagnostic en cas d’échec.
- Sur une tablette Samsung avec S Pen ou équivalent, tester Android 13/14+, paume posée avant/après la pointe. Vérifier qu’aucune fenêtre de saisie manuscrite n’intercepte le dessin. Tester le bouton latéral sans option gomme, puis avec l’option activée.
- Vérifier qu’un doigt seul et un stylet capacitif envoyé comme FINGER peignent quand l’option est activée (défaut), et ne peignent plus lorsqu’elle est désactivée. Le stylet natif continue dans les deux cas.
- Après rotation à deux doigts, tracer sur les repères des quatre coins. Franchir l’angle +180°/−180° : aucun saut de tour complet. Tourner, zoomer et déplacer simultanément : le point sous le centre des doigts reste stable.
- Toucher Recentrer : feuille droite, centre et zoom initiaux, même taux. Quitter normalement et rouvrir avec une rotation non nulle : angle restauré. Les projets issus de 0.1.0 démarrent à 0° sans perdre de masque.

## Vidéo réelle

| # | Action | Vérifications |
| --- | --- | --- |
| V1 | Toile seule, 720p/30, enregistrer 20 s avec traits, zoom, pan, rotation à deux doigts et une complétion. Arrêter et exporter. | MP4 lisible dans deux lecteurs ; caméra et dévoilement présents ; aucun panneau dans l’image ; pas d’animation de complétion. |
| V2 | Enregistrer 5 s, Pause 5 s, Reprendre 5 s puis Arrêter. | Environ 10 s de vidéo ; le temps projet inclut la pause ; aucun gel supplémentaire du masque. |
| V3 | Pendant l’enregistrement, quitter vers Projets puis fermer l’application. | Fichier finalisé, non vide, décodable et exportable. |
| V4 | Mettre l’application en arrière-plan, ouvrir une autre application, revenir. | Clip finalisé au départ ; aucune image de l’autre application ou de notification. |
| V5 | Tourner la tablette pendant le clip puis en démarrer un nouveau. | Premier clip valide, second au nouveau format ; masque et progression inchangés. |
| V6 | Activer Atelier avec interface, ouvrir chaque panneau et dessiner. | Toile bien présente (pas noire), panneaux correctement superposés, texte lisible ; absence d’interface système inutile. |
| V7 | Répéter en 1080p/30 et 60 FPS si compatible. | Résolution et cadence effective annoncées ; repli si nécessaire ; prioritisation du dessin. Mesurer la cadence réelle avec un outil vidéo. |
| V8 | Arrêter un clip immédiatement après Démarrer, puis effectuer un clip de 10 s. | Erreur explicite possible pour le clip trop court ; second clip fonctionne. |
| V9 | Sur appareil de test, forcer l’arrêt au milieu d’un clip. | Projet récupérable ; les clips précédemment finalisés restent lisibles. Le clip en cours peut être incomplet, limitation documentée. |

## Aperçu et export accéléré

| # | Action | Vérifications |
| --- | --- | --- |
| V10 | Ouvrir un clip finalisé dans Vidéos → Aperçu et vitesse d’export. | L’original à ×1 apparaît avec lecture/pause et navigation. Aucun lancement de partage/export direct avant cet écran. |
| V11 | Choisir ×2, Préparer l’aperçu, lire puis exporter. | Durée proche de l’original / 2, mêmes images/rotations, fichier lisible dans deux lecteurs. L’original reste lisible à sa durée initiale. |
| V12 | Enregistrer un clip d’une minute ; préparer successivement ×25, ×100 et ×200. | Environ 2,40 s, 0,60 s, 0,30 s. Aucun besoin pour le lecteur final de lire à plus de 30 FPS. Relever résolution, cadence et durée réelles. |
| V13 | Passer de l’aperçu ×100 au réglage ×200 sans préparer. | Export désactivé jusqu’à la préparation du nouvel aperçu ; aucun export silencieux à l’ancienne vitesse. |
| V14 | Préparer à ×200 un clip court de quelques secondes. | Au moins une frame décodable, durée minimale proche de 34 ms si nécessaire. Pas de fichier vide. |
| V15 | Annuler une préparation, la relancer, fermer l’éditeur pendant préparation puis le rouvrir. | Annulation sans fichier final partiel, nouvelle préparation possible, original intact. |
| V16 | Mettre l’application en arrière-plan pendant préparation ; changer l’orientation pendant aperçu. | Préparation interrompue proprement si mise en arrière-plan ; message et relance disponibles. Aperçu adapté à l’orientation ; aucune influence sur le masque ou le temps sauvegardé. |
| V17 | Partager l’aperçu, fermer l’éditeur, ouvrir le MP4 dans l’application destinataire ; exporter par Fichiers. | Même version accélérée, fichier disponible après fermeture, permission de lecture limitée au fichier choisi. |
| V18 | Saisir 0, 201 et un champ vide ; essayer chaque preset jusqu’à ×200. | Entrées hors plage signalées, aucun export incohérent ; curseur et saisie restent concordants. |
| V19 | Sur appareil de test, provoquer un manque d’espace ou une erreur de codec à la préparation. | Message d’échec, export désactivé, original conservé ; relance possible après correction. |

## Performance et accessibilité

Effectuer une session de quinze minutes sur un projet de vingt images 4K. Mesurer via Android Studio Profiler / Perfetto le RSS, le tas Java, les allocations, les pauses GC et les frames lentes. Comparer sans vidéo, Toile seule 720p/30, puis 1080p. Noter les pics lors de l’import, de la permutation d’étape, des gros traits et d’Undo. Filmer extérieurement le stylet et l’écran pour estimer la latence ; l’émulateur n’est pas une mesure valable. Une réduction de vidéo doit précéder toute acceptation d’une dégradation persistante du dessin.

Activer TalkBack et une taille de police à 130 % puis 180 %. Parcourir Projets, Préparation, Atelier, Books et Vidéos en portrait et paysage, vérifier les labels, défilements, contrastes et absence de contrôle inaccessible. Tester sans stylet puis avec une souris explicitement activée; vérifier le réglage de dessin à un doigt dans les deux états.

## Recette ciblée de la mise à jour

| Cas | Résultat attendu |
| --- | --- |
| Dessin continu à un doigt, puis Undo/Redo | Pixels et taux restaurés exactement. Plusieurs contacts ajoutent l’intensité choisie. |
| Dessin à un doigt → ajout du deuxième → pinch/pan/rotation → relâchement d’un seul doigt | Portion dessinée conservée, caméra seule ensuite; masque et taux strictement constants pendant la navigation. Aucun trait au retour de deux doigts à un. |
| À 90 %, sur une petite toile, commencer un tap à deux doigts avec pinceau à 10 % | Le premier contact peut promouvoir immédiatement l’étape, mais l’arrivée du deuxième annule ce contact provisoire et restaure les 90 %. Pas de progression conservée par le geste de navigation. |
| Tap trois doigts puis quatre doigts après un vrai trait | Une annulation puis un rétablissement; aucun point initial parasite, y compris près de la fin d’étape. |
| Date/heure Atelier et format Android 12 h/24 h | Date et heure locales visibles; minute actualisée sans toucher la toile, format conforme au système. |
| Signature Atelier | « par Nuvora Studio » reste visible et discrète, sans masquer la toile. |
| Démarrer, pause, reprendre, arrêter une vidéo | Pourcentage masqué dès l’enregistrement, également dans Étapes, restauré à l’arrêt. Dessin et taux réel continuent; Undo reste exact. |
| Créer un Book, nommer, décrire, ajouter plusieurs pages, couverture | Métadonnées et miniatures sauvegardées; Terminer puis rouvrir conserve les changements. |
| Book : remplacer, déplacer et supprimer une page | Ordre exact et proportions conservées. Miniature et aperçu correspondent au fichier remplacé. |
| Book : glisser gauche/droite ou boutons suivant/précédent | Navigation prévisible, aucune peinture possible. Portrait : une page; paysage large : deux pages. La dernière double page ne défile pas sur elle-même. |
| Book : feuilleter rapidement, quitter et rouvrir | Dernière page conservée, pas de retour à une sauvegarde antérieure. Rotation de l’appareil ne saute pas la page. |
| Supprimer un Book en gardant un autre | Confirmation claire; seul le Book sélectionné est supprimé. |
| Enregistrement Atelier puis Vidéos | Entrée ajoutée avec miniature, nom, projet, date, durée, dimensions et taille; MP4 réellement lisible. |
| Vidéo : lire/pause/timeline/aperçu ×1…×200 | Commandes fonctionnelles, temps courant/total visibles via le lecteur. Original conservé après aperçu accéléré. |
| Vidéo : renommer, description et lien projet | Changements conservés après réouverture, sans modifier le MP4 ni le projet. |
| Vidéo : suppression confirmée | MP4, miniature et entrée du catalogue supprimés ensemble; les autres vidéos restent disponibles. |
| Vidéo absente ou corrompue (copie de test uniquement) | État d’erreur visible, navigation encore utilisable; aucune simulation de lecture. |
| Couverture de projet : automatique, choix d’étape, import, remplacement, retour auto | Aperçu et carte cohérents après Enregistrer. Étape actuelle, ordre, masque, progression et temps inchangés. |
| Changer une couverture puis annuler la préparation | Ancienne couverture sauvegardée toujours disponible. |
| Régression finale | 2/20 images, refus >20, pression/gomme, gestes, 100 % immédiat, sauvegarde/restauration et chronomètre conformes. |

## Tests instrumentés automatisés

Avec l’APK de test installé, `./gradlew :app:connectedDebugAndroidTest` lance des tests natifs : événements finger/pen, Undo/Redo, deux modes MP4 avec pause/reprise/finalisation, lecture d’une frame et présence effective des couleurs de toile et d’interface, puis véritables exports Media3 à ×2 et ×200 avec vérification de durée et décodage. Six tests instrumentés sont fournis. Ces tests s’exécutent sur les vrais codecs Android ; ils peuvent révéler une incompatibilité matérielle et doivent alors être analysés, pas remplacés par une simulation.
