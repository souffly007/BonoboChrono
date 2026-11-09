# Bonobo Chrono
Chrono à répétition : chrono 1 → son + vibration → chrono 2 → son + vibration → on recommence N fois.
Licence GPL v3. Aucune dépendance (SDK Android uniquement).

- Service au premier plan + notification (bouton Arrêter) + wake lock partiel : continue écran éteint / app fermée de l'écran.
- Bas de page : décompte (h:m:s) ou chrono classique, avec h=vert, m=cyan, s=orange (ClassicChrono.kt, indépendant du haut).
- Écran gardé allumé quand l'app est visible.
- Android 13+ : autoriser les notifications. Si le constructeur tue le service, désactiver l'optimisation batterie pour l'app.
- Ouvrir le dossier dans Android Studio (Gradle sync), puis Run.
- Couleurs : vert CyanogenMod #A4C639, cyan #00E5FF, orange #FF9800 (haut de Ui.kt).
