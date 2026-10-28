# Block Extreme Droite

Appli Android qui bloque automatiquement, depuis l'appli X, les comptes listés sur [onbloquelextremedroite.fun](https://onbloquelextremedroite.fun/).

Sur le site, on bloque les profils un par un à la main. L'appli fait la même chose à votre place : elle ouvre chaque profil dans l'appli X, appuie sur « Plus d'options », puis « Bloquer », puis confirme. Vous lancez, vous pouvez poser le téléphone.

## Fonctionnement

- **La liste** est celle du site : les comptes vérifiés par l'équipe et les propositions de la communauté. Elle est téléchargée au premier lancement, enregistrée sur le téléphone, et mise à jour quand vous appuyez sur « Mettre à jour ».
- **Le blocage** passe par un service d'accessibilité Android qui pilote l'appli X officielle. Aucun mot de passe, aucune API X : l'appli appuie sur les mêmes boutons que vous.
- **Avant de bloquer**, l'appli vérifie que le profil affiché est bien le bon (`@handle`). Un compte déjà bloqué, suspendu ou supprimé est passé sans rien toucher.
- **Le rythme** est réglable : une pause entre deux blocages (de 0 à 60 s) et un maximum par session (de 5 à 200, ou sans limite). Bloquer trop vite peut faire limiter votre compte X.
- **Le compteur** du site est mis à jour à chaque blocage, comme quand vous bloquez depuis le site. Chaque compte affiche aussi combien de personnes l'ont déjà bloqué.
- **La progression** est enregistrée : l'appli reprend là où elle s'est arrêtée et ne rouvre jamais un compte déjà traité.

- **Les mises à jour** de l'appli sont cherchées à chaque ouverture dans les [releases GitHub](https://github.com/CuteTenshii/BlockExtremeDroiteAndroid/releases). Quand une version plus récente existe, une carte propose de la télécharger puis ouvre l'installeur d'Android. La première fois, Android demande d'autoriser l'appli à installer des applis.

L'interface est en français, avec l'anglais si votre téléphone est en anglais. Les libellés de l'appli X sont reconnus en français et en anglais.

## Installation

Il vous faut Android 7.0 ou plus récent, et l'appli X installée avec votre compte connecté.

1. Téléchargez l'APK depuis les [releases](https://github.com/CuteTenshii/BlockExtremeDroiteAndroid/releases) et installez-le.
2. Ouvrez l'appli et appuyez sur « Activer » pour ouvrir les réglages d'accessibilité, puis activez le service « Block Extreme Droite ».
3. Sur Android 13 et plus, l'interrupteur est grisé pour les applis installées hors Play Store. Allez dans Infos de l'appli, menu ⋮, « Autoriser les paramètres restreints », puis revenez activer le service.
4. Choisissez les sources (comptes vérifiés par l'équipe, propositions de la communauté) et les catégories, réglez le rythme et appuyez sur « Lancer le blocage ».

Une barre s'affiche par-dessus X pendant le blocage. Son bouton « Arrêter » coupe tout immédiatement.

## Données envoyées

L'appli contacte onbloquelextremedroite.fun :

- pour télécharger la liste et les compteurs ;
- pour déclarer chaque blocage au compteur collectif, avec le compte bloqué et un identifiant aléatoire propre à l'appli, comme le fait le site.

Elle contacte aussi GitHub, sans rien envoyer d'autre que sa version, pour savoir si une mise à jour existe et la télécharger.

Rien d'autre ne sort du téléphone. Le service d'accessibilité ne lit que l'écran de l'appli X, et seulement pendant un blocage que vous avez lancé.

## Compiler

```sh
./gradlew assembleDebug
```

Pour une version signée, copiez `keystore.properties.example` en `keystore.properties` et remplissez-le (ou utilisez les variables `BED_KEYSTORE_FILE`, `BED_KEYSTORE_PASSWORD`, `BED_KEY_ALIAS` et `BED_KEY_PASSWORD`), puis :

```sh
./gradlew assembleRelease
```

L'APK se trouve dans `app/build/outputs/apk/release/`.

Pour publier une version, augmentez `versionCode` et `versionName` dans `app/build.gradle.kts`, puis créez une release GitHub dont le tag vaut `v` + `versionName` (par exemple `v1.1`) et joignez-y l'APK signé avec la même clé. L'appli ne propose que les releases dont le numéro est plus grand que le sien.

## Si l'appli ne trouve plus les boutons

L'appli X change régulièrement son interface. Si les blocages échouent tous, les libellés reconnus sont regroupés dans `XLabels` (`app/src/main/java/moe/tenshii/blockextremedroite/XScreen.kt`). Ouvrez une issue en précisant la version de X et la langue du téléphone.

## Licence

[MIT](LICENSE)
