# Block Extreme Droite

Appli Android qui bloque automatiquement les comptes listés sur [onbloquelextremedroite.fun](https://onbloquelextremedroite.fun/), depuis les applis X, Instagram, Facebook, TikTok et YouTube.

Sur le site, on bloque les profils un par un à la main. L'appli fait la même chose à votre place : elle ouvre chaque profil dans l'appli du réseau choisi, ouvre son menu, appuie sur « Bloquer », puis confirme. Vous lancez, vous pouvez poser le téléphone.

## Fonctionnement

- **La liste** est celle du site : les comptes vérifiés par l'équipe et les propositions de la communauté. Elle est téléchargée au premier lancement, enregistrée sur le téléphone, et mise à jour quand vous appuyez sur « Mettre à jour ».
- **Les réseaux** sont ceux du site : X, Instagram, Facebook, TikTok et YouTube. Vous choisissez le réseau avant de lancer ; chaque réseau a sa propre progression. Un compte présent sur plusieurs réseaux est reconnu comme une seule personne, comme sur le site.
- **Le blocage** passe par un service d'accessibilité Android qui pilote l'appli officielle du réseau. Aucun mot de passe, aucune API : l'appli appuie sur les mêmes boutons que vous.
- **Avant de bloquer**, l'appli vérifie que le profil affiché est bien le bon (voir le tableau ci-dessous). Un compte déjà bloqué, suspendu ou supprimé est passé sans rien toucher.
- **Le rythme** est réglable : une pause entre deux blocages (de 0 à 60 s) et un maximum par session (de 5 à 200, ou sans limite). Bloquer trop vite peut faire limiter votre compte.
- **Le compteur** du site est mis à jour à chaque blocage, comme quand vous bloquez depuis le site. Chaque compte affiche aussi combien de personnes l'ont déjà bloqué.
- **La progression** est enregistrée : l'appli reprend là où elle s'est arrêtée et ne rouvre jamais un compte déjà traité.

- **Les mises à jour** de l'appli sont cherchées à chaque ouverture dans les [releases GitHub](https://github.com/CuteTenshii/BlockExtremeDroiteAndroid/releases). Quand une version plus récente existe, une carte propose de la télécharger puis ouvre l'installeur d'Android. La première fois, Android demande d'autoriser l'appli à installer des applis.

### Par réseau

| Réseau | Comment l'appli bloque | Comment elle vérifie le profil | Un compte déjà bloqué apparaît comme |
|---|---|---|---|
| X | « Plus d'options » → « Bloquer @handle » → « Bloquer » | le `@handle` affiché | déjà bloqué |
| Instagram | « Options » → « Bloquer » → « Bloquer » | le nom d'utilisateur en haut du profil | introuvable : Instagram masque les profils bloqués |
| TikTok | « Partager » → « Bloquer » → « Bloquer » | le `@nom` affiché | déjà bloqué |
| Facebook | « Autres paramètres du profil » → « Bloquer » → « BLOQUER » | le lien de la page, affiché dans le menu ; s'il diffère, rien n'est bloqué | introuvable : Facebook ouvre le fil d'actualité à la place |
| YouTube | « Plus » → « Masquer l'utilisateur sur ma chaîne » → « Masquer l'utilisateur » | aucune : la chaîne n'affiche pas son `@handle`, l'appli se fie au lien ouvert | déjà masqué |

YouTube n'a pas de blocage : « Masquer l'utilisateur sur ma chaîne » est l'action la plus proche, et retire ses commentaires de votre chaîne.

L'interface est en français, avec l'anglais si votre téléphone est en anglais. Les libellés des applis des réseaux sont reconnus en français et en anglais.

## Installation

Il vous faut Android 7.0 ou plus récent, et l'appli de chaque réseau à bloquer (X, Instagram, Facebook, TikTok ou YouTube) installée avec votre compte connecté.

1. Téléchargez l'APK depuis les [releases](https://github.com/CuteTenshii/BlockExtremeDroiteAndroid/releases) et installez-le.
2. Ouvrez l'appli et appuyez sur « Activer » pour ouvrir les réglages d'accessibilité, puis activez le service « Block Extreme Droite ».
3. Sur Android 13 et plus, l'interrupteur est grisé pour les applis installées hors Play Store. Allez dans Infos de l'appli, menu ⋮, « Autoriser les paramètres restreints », puis revenez activer le service.
4. Choisissez le réseau, les sources (comptes vérifiés par l'équipe, propositions de la communauté) et les catégories, réglez le rythme et appuyez sur « Lancer le blocage ».

Une barre s'affiche par-dessus l'appli du réseau pendant le blocage. Son bouton « Arrêter » coupe tout immédiatement.

## Données envoyées

L'appli contacte onbloquelextremedroite.fun :

- pour télécharger la liste et les compteurs ;
- pour déclarer chaque blocage au compteur collectif, avec le compte bloqué et un identifiant aléatoire propre à l'appli, comme le fait le site.

Elle contacte aussi GitHub, sans rien envoyer d'autre que sa version, pour savoir si une mise à jour existe et la télécharger.

L'écran des comptes affiche les photos choisies par le site, téléchargées depuis les sites qui les hébergent (assemblee-nationale.fr, Wikimedia Commons), qui voient donc passer ces requêtes. Pour les autres comptes, la photo de profil du réseau choisi passe par onbloquelextremedroite.fun. Les photos sont gardées une semaine en cache sur le téléphone.

Rien d'autre ne sort du téléphone. Le service d'accessibilité ne lit que l'écran de l'appli du réseau choisi, et seulement pendant un blocage que vous avez lancé.

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

Les applis des réseaux changent régulièrement leur interface. Si les blocages échouent tous sur un réseau, ses libellés reconnus sont regroupés dans le fichier de ce réseau : `XScreen.kt`, `InstagramScreen.kt`, `FacebookScreen.kt`, `TikTokScreen.kt` ou `YouTubeScreen.kt` (dans `app/src/main/java/moe/tenshii/blockextremedroite/screen/`). Ouvrez une issue en précisant le réseau, la version de son appli et la langue du téléphone.

## Licence

[MIT](LICENSE)
