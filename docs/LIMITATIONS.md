# Limites connues — Veila Paint 0.4.0

- La pression, l’inclinaison, le bouton latéral, la gomme, le rejet de paume et la latence varient selon le pilote : validation obligatoire sur chaque famille de tablette ciblée.
- L’enregistrement 1080p/60 dépend du codec H.264. L’application retombe sur 30 FPS puis 720p/30 et réduit la cadence si l’encodage menace le dessin.
- Une texture ne peut dépasser `GL_MAX_TEXTURE_SIZE`. L’Atelier affiche une erreur explicite si le GPU ne peut pas contenir le plan de travail.
- Les calques non actifs sont recomposés lors d’un changement de calque. Une série de vingt images 4K doit être profilée sur appareil à faible mémoire ; le composite ne conserve pas les vingt bitmaps décompressés simultanément.
- Les masques complets sont conservés dans les snapshots de session. Leur bundle est compressé sur disque, mais un projet de vingt calques très complexes à la limite de résolution reste le pire cas mémoire de cette première architecture 0.4.
- Le redimensionnement du plan conserve les masques par rééchantillonnage, mais réinitialise l’historique Undo/Redo des calques redimensionnés car les différences de tuiles ne sont plus compatibles.
- La palette du panneau Pinceau est explicitement préparatoire en 0.4.0 : elle n’a aucun effet sur l’image, le masque, l’intensité ou la progression.
- Les tests JVM ne remplacent pas la recette instrumentée et visuelle sur tablette. L’environnement de livraison doit disposer du SDK Android/Gradle et d’un appareil pour certifier ces essais.

