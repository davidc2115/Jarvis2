# Jarvis2

Assistant Android façon Jarvis/Iron Man : IA 100% locale, contrôle du
téléphone, et un vault de notes compatible Obsidian avec vue graphe. Ce
dépôt contient les **fondations réellement fonctionnelles** du projet — pas
des maquettes — construites en une première session de travail. La suite
(priorités listées en bas de ce fichier) reste à faire.

## État du build

Ce projet compile réellement : `./gradlew :app:assembleDebug` produit un
`app-debug.apk` installable (~71 Mo, `minSdk 26`, `targetSdk 35`,
`compileSdk 35`, AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9). Vérifié en CI locale
au moment de la rédaction de ce README.

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

## CI : build automatique par GitHub Actions

`.github/workflows/android-ci.yml` fait tourner le build sur les machines
GitHub (réseau complet vers Google Maven/Maven Central, pas de limite de
taille de fichier) à chaque push/PR sur `main` :

- **Debug** : compile, lance les tests unitaires, publie le
  `app-debug.apk` en artefact téléchargeable depuis l'onglet *Actions* du
  dépôt → le run → *Artifacts*. C'est le moyen le plus simple de récupérer
  un APK sans avoir Android Studio installé.
- **Release signée** : uniquement déclenchée par un tag `v*` (ex.
  `git tag v0.1.0 && git push --tags`), et seulement si le repo a les
  secrets de signature configurés (sinon cette étape est proprement
  ignorée, le job debug continue de tourner). Une fois signée, l'APK est
  attaché automatiquement à une GitHub Release créée pour le tag.

### Signer une release

1. Génère un keystore si tu n'en as pas :
   `keytool -genkeypair -v -keystore release.keystore -alias jarvis2 -keyalg RSA -keysize 2048 -validity 10000`
2. Dans **Settings → Secrets and variables → Actions** du dépôt, ajoute :
   - `RELEASE_KEYSTORE_BASE64` : `base64 -w0 release.keystore` (le contenu)
   - `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
3. Pousse un tag `v*` — le job `release-build` construit et publie l'APK
   signé automatiquement. Sans ces secrets, seul le build debug tourne.

Ne commite jamais le fichier `.keystore` lui-même dans le dépôt — seul le
CI le voit, via le secret, jamais en clair dans l'historique git.

Ouvre simplement le dossier dans Android Studio (Hedgehog ou plus récent) —
il détectera le wrapper Gradle et proposera de synchroniser directement.

## Ce qui marche déjà (vrai code, pas des stubs)

- **Moteur IA local à deux niveaux** (`ai/`) : essaie d'abord AICore /
  Gemini Nano (`com.google.ai.edge.aicore`, système, zéro téléchargement
  géré par l'app) et bascule automatiquement sur un modèle embarqué via
  MediaPipe LLM Inference (`ai/mediapipe/MediaPipeLlmEngine.kt`) si AICore
  n'est pas disponible sur l'appareil — voir la section AICore ci-dessous.
- **Aucun appel réseau vers un LLM cloud, nulle part.** La seule sortie
  réseau du projet est la recherche web de secours (`ai/WebSearchTool.kt`),
  déclenchée uniquement quand le modèle local admet explicitement ne pas
  savoir, et seulement après confirmation dans le chat.
- **Routeur de commandes** (`ai/CommandRouter.kt`) : reconnaît en local,
  sans passer par le LLM, des phrases comme "allume la torche", "crée un
  rendez-vous", "génère un PDF"… et déclenche l'action réelle correspondante
  — rapide et fiable, pas de dépendance à un petit modèle pour du function
  calling parfait.
- **Mémoire locale** (`ai/MemoryStore.kt`) : recherche TF-IDF sur l'historique
  stocké en Room (`data/db/`), réinjectée comme contexte pertinent dans le
  prompt — la base pour "plus de rapidité/fluidité" demandée, sans le coût
  d'un modèle d'embeddings.
- **Intégrations téléphone réelles** (`integrations/`) : torche
  (CameraManager), Bluetooth (activation/désactivation via les panneaux
  système imposés par Android 12+, lecture des appareils appairés),
  Wi-Fi (lecture d'état + panneau système — voir note ci-dessous), GPS/
  position (LocationManager, sans dépendance Play Services), agenda
  (CalendarContract, création/lecture d'événements), contacts
  (ContactsContract, création/lecture), mail (intent ACTION_SEND vers
  l'appli mail de l'utilisateur), stockage (Storage Access Framework).
- **Génération de fichiers réelle, sans dépendance lourde**
  (`filegen/`) : PDF (android.graphics.pdf), ZIP (java.util.zip), KML
  (XML valide), DOCX et XLSX (OOXML minimal écrit à la main, sans Apache
  POI — ouvrable dans Word/Excel/LibreOffice/Google Docs/Sheets).
- **Vault Obsidian réel** (`obsidian/`) : fichiers `.md` avec frontmatter
  YAML, `[[wikilinks]]`, `#tags` — format identique à Obsidian, donc un
  dossier synchronisé (Syncthing, etc.) s'ouvre aussi bien ici que dans
  l'app Obsidian desktop/mobile. Vue graphe interactive
  (`ui/graph/GraphView.kt`) en Compose Canvas pur : pan, zoom, glisser un
  nœud, layout par simulation de forces (`obsidian/GraphModel.kt`).
- **UI complète en Jetpack Compose**, thème "arc reactor" sombre/cyan/or
  (`ui/theme/`), navigation par onglets (Jarvis, Vault, Toile, Téléphone,
  Réglages).

## AICore, Gemini Nano et ton Xiaomi

Au moment de la rédaction (2026), Google a publié une liste d'appareils
Xiaomi/POCO compatibles Gemini Nano v2 via AICore : Xiaomi 14T Pro / 15 /
15T / 15T Pro / 15 Ultra / 17 / 17 Ultra, POCO F7 Ultra / F8 Pro / F8 Ultra /
X7 Pro / X8 Pro. Sur ces appareils, `AiCoreEngine` doit fonctionner nativement.
Sur tout autre appareil (y compris un Xiaomi plus ancien ou milieu de
gamme), l'app bascule automatiquement sur le modèle embarqué MediaPipe —
aucune action requise de ta part, c'est géré par `AiEngineManager`.

**Le SDK AICore (`com.google.ai.edge.aicore:0.0.1-exp01`) est expérimental
côté Google** — l'API a été vérifiée à la main dans ce projet en
décompilant le `.aar` réellement résolu (pas une supposition), donc le code
actuel compile avec la version épinglée dans
`gradle/libs.versions.toml`. Si Google publie une nouvelle version avec une
API différente, seul `ai/aicore/AiCoreEngine.kt` doit être ajusté — tout le
reste de l'app ne dépend que de l'interface `LocalAiEngine` et n'est pas
affecté.

**Modèle local pour le fallback MediaPipe** : le `.task` (ex. Gemma 3 1B
IT int4) n'est pas inclus dans ce dépôt — trop volumineux pour git et pour
les limites du Play Store. Il faut l'importer manuellement dans
`Réglages` une fois l'écran de sélection de fichier branché (actuellement
l'app cherche le fichier à
`Android/data/com.jarvis2.app/files/models/local-llm.task`). Voir
https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference pour
convertir un modèle au format `.task`.

## Limites connues du réseau — pourquoi le Wi-Fi/Bluetooth "ouvrent un
panneau" au lieu de switcher directement

Ce n'est pas un manque de finition : depuis Android 10 (Wi-Fi) et la refonte
des permissions Bluetooth d'Android 12+, les apps tierces n'ont plus le
droit de couper/activer ces radios silencieusement — c'est une restriction
plateforme délibérée. `WifiController` et `BluetoothController` ouvrent donc
le panneau système correspondant, l'utilisateur confirme d'un tap. C'est le
comportement correct et définitif, pas une étape intermédiaire.

## Ce qui reste à construire (priorités suggérées pour la suite)

1. **Sélecteur de fichier `.task`** dans Réglages (SAF) pour importer le
   modèle MediaPipe sans avoir à pousser le fichier via adb.
2. **Sélecteur de dossier vault externe** dans Réglages (le code
   `VaultRepository`/`StorageAccess` gère déjà un vault externe, il manque
   juste le bouton + `ActivityResultContracts.OpenDocumentTree`).
3. **Écran de gestion des permissions runtime** centralisé (actuellement
   chaque intégration suppose la permission déjà accordée — ajouter les
   demandes via `accompanist-permissions`, déjà en dépendance).
4. **Recherche web de secours branchée dans `ChatViewModel`** (le bouton
   "Rechercher" du chat a un TODO explicite — `WebSearchTool` est prêt,
   il manque le câblage + réinjection du résultat dans la conversation).
5. **Reconnaissance vocale / synthèse vocale** (pas encore commencé —
   `SpeechRecognizer`/`TextToSpeech` sont dans le SDK Android standard,
   aucune dépendance externe nécessaire).
6. **Widget/notification permanente style HUD**, raccourcis rapides
   (Quick Settings Tile pour la torche, par exemple).
7. **Tests** (aucun test unitaire n'existe encore — `CommandRouter` et
   `NoteParser` sont les candidats les plus rentables à couvrir en premier,
   ce sont des fonctions pures faciles à tester).
8. **Bump vers targetSdk 36 / AGP 9** avant toute publication sur le Play
   Store (obligatoire depuis fin août 2026) — la structure du projet ne
   change pas, seuls `libs.versions.toml` et `app/build.gradle.kts` sont à
   mettre à jour, plus régénérer le SDK local avec `platforms;android-36`.

## Structure du projet

```
app/src/main/java/com/jarvis2/app/
  ai/              moteur IA (interface, AICore, MediaPipe, mémoire, routeur de commandes, recherche web)
  data/            Room (chat, mémoire) + DataStore (réglages)
  integrations/    torche, bluetooth, wifi, gps, agenda, contacts, mail, stockage
  filegen/         PDF, DOCX, XLSX, KML, ZIP
  obsidian/        modèle de note, parsing markdown, dépôt de vault, graphe + layout
  ui/              écrans Compose (chat, vault, graphe, intégrations, fichiers, réglages) + thème
  di/              modules Koin
```

## Confidentialité

Par conception : aucune donnée de conversation, de vault, ou de fichier
généré ne quitte l'appareil. La seule exception explicite et opt-in est la
recherche web de secours, qui n'envoie que la requête texte que
l'utilisateur a validée — jamais l'historique de conversation, jamais le
contenu du vault.
