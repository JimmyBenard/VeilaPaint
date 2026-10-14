# 0.4.0

- Plan de travail partagé avec calques de dévoilement indépendants.
- Masque et progression isolés par calque actif ; migration du format séquentiel 0.3.x.
- Tap 2 doigts : overlay rouge des pixels non entièrement dévoilés ; tap 5 doigts : taux consultable.
- Pinceau par défaut : 10 px et intensité 50 %.
- Reset annulable du calque actif, slider d’intensité blanc → noir et palette préparatoire sans effet moteur.
- Plan de travail dimensionnable depuis un calque ; réorganisation sans renommage ni perte de masque.
- Reprise vidéo protégée par disparition de tous les overlays et barrière de rendu UI/GL.

# 0.3.0

- Atelier : pinceau à un doigt activé par défaut, même moteur de masque que le stylet, priorité au stylet, interruption immédiate au deuxième doigt et blocage de tout trait parasite pendant la navigation multi-touch.
- Atelier : date/heure localisées, signature discrète « par Nuvora Studio » et pourcentage volontairement masqué pendant l’enregistrement vidéo.
- Projets : couverture automatique, couverture tirée d’une étape ou image indépendante importée, avec miniature optimisée et migration JSON non destructive de schéma 1 vers 2.
- Books : bibliothèque séparée, CRUD, pages importées/réordonnées/remplacées, couverture, lecture à une/deux pages, navigation horizontale à un doigt et reprise de lecture.
- Vidéos : bibliothèque CRUD persistante avec miniature, nom, projet associé, métadonnées, lecture/seek, édition et suppression confirmée; les MP4 existants sont adoptés sans être supprimés.
- Vérification : 92 tests JVM/Robolectric réussis, APK standard/essai/test compilés, Lint 0 erreur (6 avertissements non bloquants). Les essais matériels restent documentés.

# 0.2.0

- Stylet : diagnostic Android, gestion de paume, source STYLUS de secours, gomme/bouton latéral facultatifs.
- Caméra : rotation à deux doigts combinable au zoom et au déplacement.
- Vidéo : aperçu avant export, lecture/navigation et vitesses de ×1 à ×200.
