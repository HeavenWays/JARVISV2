# 🤖 Jarvis — Assistant IA intégré au téléphone (Android)

Assistant vocal agentique **100 % gratuit** : il t'écoute, **voit ton écran**, se souvient de
ce qui a été affiché récemment, **ouvre des apps**, lance des recherches web, navigue et appuie
à ta place — le tout piloté par un cerveau **Groq (Llama)**.

> Cible : **Android / Samsung** (Android 8.0+ / API 26+). iOS n'est pas supporté : Apple
> interdit la lecture d'écran et l'automatisation d'apps en arrière-plan.

---

## 🧠 Comment ça marche (architecture)

```
Toi (voix) ─► Reconnaissance vocale ─► CERVEAU (Groq/Llama) ─┬─► Réponse à voix haute (TTS)
     ▲                                        ▲               └─► Action réelle sur le tel
 Bulle flottante                     Contexte fourni :            (ouvrir app / URL / clic / scroll)
 (appui = parler)             • texte de l'écran (Accessibilité)
                              • mémoire des 15 dernières minutes
                              • capture d'écran (questions visuelles)
```

| Rôle | Composant Android | Fichier |
|------|-------------------|---------|
| Cerveau | API Groq (Llama 3.3 / Llama 4 vision) | `core/Brain.kt` |
| Yeux (texte) | Service d'Accessibilité | `service/JarvisAccessibilityService.kt` |
| Yeux (image) | `takeScreenshot()` de l'accessibilité | idem |
| Mains | Gestes + Intents | `core/ActionExecutor.kt` |
| Oreilles | `SpeechRecognizer` | `voice/VoiceManager.kt` |
| Voix | `TextToSpeech` | `core/Jarvis.kt` |
| Mémoire | Tampon roulant 15 min | `core/ScreenMemory.kt` |
| Apps | `PackageManager` | `apps/AppScanner.kt` |
| Présence | Service au premier plan + bulle | `service/JarvisForegroundService.kt` |

---

## 🛠️ 1. Compiler l'app (Android Studio, gratuit)

1. Installe **Android Studio** (dernière version) : https://developer.android.com/studio
2. **File → Open** → sélectionne le dossier `C:\Users\hp\JarvisAI`.
3. Laisse Android Studio **synchroniser Gradle** (il télécharge tout seul le SDK et les
   dépendances la première fois — 5-10 min). Accepte l'installation du SDK Android 34 s'il
   te la propose.
4. Ta **clé Groq est déjà** dans `local.properties` (`GROQ_API_KEY=...`). Tu pourras aussi la
   changer directement dans l'app (Réglages avancés).
5. Branche ton **Samsung en USB** et active le **Débogage USB** :
   *Paramètres → À propos du téléphone → tape 7 fois sur « Numéro de build »* pour débloquer le
   **mode développeur**, puis *Paramètres → Options pour les développeurs → Débogage USB*.
6. Dans Android Studio, choisis ton téléphone en haut, puis clique **▶ Run**.
   → L'app s'installe et se lance sur ton Samsung.

> Alternative sans câble : **Build → Build APK(s)**, puis copie le fichier
> `app/build/outputs/apk/debug/app-debug.apk` sur le téléphone et ouvre-le pour l'installer
> (autorise « sources inconnues » si demandé).

---

## ✅ 2. Donner les autorisations (écran d'accueil de l'app)

L'app affiche une carte par autorisation, avec un bouton pour chacune. Donne les **4** :

1. **Clé IA (Groq)** — déjà verte si la clé est présente.
2. **Superposition à l'écran** → « Autoriser » → active pour Jarvis (la bulle flottante).
3. **Accessibilité (voir & agir)** → « Activer » → dans la liste, choisis **Jarvis**, active-le.
   *(C'est ce qui lui permet de lire l'écran et d'agir. Android affiche un avertissement, c'est normal.)*
4. **Microphone** → « Autoriser ».
5. **Notifications** → « Autoriser » (garde Jarvis vivant en arrière-plan).

Quand tout est vert → le gros bouton **« Activer Jarvis »** devient cliquable.

---

## 🎙️ 3. Tester

**Sans la voix (rapide, pour vérifier le cerveau)**
Dans l'app, section *« Tester une commande »*, écris par ex. :
- `Bonjour, qui es-tu ?`
- `Ouvre YouTube`
- `Cherche la météo à Paris`
- `Quelle est la capitale de l'Australie ?`

Jarvis répond à voix haute + exécute l'action, et affiche le détail sous le bouton.

**Avec la voix (le vrai Jarvis)**
1. Appuie sur **Activer Jarvis** → une **bulle bleue** apparaît par-dessus tout l'écran.
2. Va sur n'importe quelle app. Dis « **Jarvis** » (si le modèle vocal est installé) ou
   **appuie sur la bulle** ; Jarvis dit « Oui ? », puis parle :
   - « **Ouvre WhatsApp** » (ouvre une app)
   - « **Reviens à l'accueil** » · « **Fais défiler vers le bas** » (navigation)
   - « **Que vois-tu à l'écran ?** » (capture + analyse visuelle)
   - « **Qu'est-ce que j'ai vu il y a quelques minutes ?** » (mémoire d'écran)
   - « **C'est quoi la tour Eiffel ?** » (réponse cherchée sur le web, dite à l'oral)
   - « **Va sur YouTube** » (ouvre le navigateur)
   - « **Ouvre WhatsApp et écris *j'arrive* à Maman** » (tâche multi-étapes)

La bulle est **déplaçable** (glisse-la où tu veux).

---

## 🎨 Design

- **Icône adaptative** (`res/mipmap-anydpi-v26/`) : réacteur / anneau lumineux dégradé
  cyan → bleu avec un « J », fond sombre profond. S'adapte à toutes les formes Samsung/Android.
- **Interface** : thème sombre « cockpit », **orbe central animé** (anneaux tournants + noyau
  pulsant), cartes en verre, accents cyan électrique. Défini dans `ui/Theme.kt` + `MainActivity.kt`.
- **Bulle flottante** : logo Jarvis sur pastille circulaire avec halo.

---

## 🔧 Dépannage

| Problème | Solution |
|----------|----------|
| « Erreur du serveur IA (code 400/404) » | Le nom du modèle Groq a changé. Ouvre **Réglages avancés** dans l'app et mets un modèle valide (voir https://console.groq.com/docs/models). Ex. texte : `llama-3.3-70b-versatile`. |
| « Erreur (code 401) » | Clé Groq invalide/expirée → régénère-la sur console.groq.com et colle-la dans Réglages. |
| Jarvis n'agit pas (ouvre/clique) | Le service **Accessibilité** n'est pas activé, ou a été coupé par Samsung → réactive-le. |
| La bulle n'apparaît pas | Autorisation **Superposition** manquante. |
| Il ne m'entend pas | Autorisation **Micro** + installe/active la saisie vocale Google (reconnaissance FR). |
| Samsung tue l'app en arrière-plan | *Paramètres → Batterie → Jarvis → « Sans restriction »*. |

---

## 🗣️ Mot d'activation « Jarvis » (mains-libres)

Pour déclencher Jarvis à la voix **sans toucher la bulle**, ajoute le modèle vocal offline :
voir **`app/src/main/assets/LISEZ-MOI_modele_vocal.txt`** (télécharger `vosk-model-small-fr`,
le décompresser dans `app/src/main/assets/model-fr/`). C'est gratuit et 100 % hors-ligne.
Sans ce modèle, tout marche pareil mais tu déclenches Jarvis en appuyant sur la bulle.

## 🧩 Modes de recherche web

- **« C'est quoi le Bitcoin ? », « Qui a gagné hier ? »** → `web_answer` : Jarvis **cherche sur
  le web (DuckDuckGo, gratuit) et te répond à l'oral**, sans ouvrir le navigateur.
- **« Va sur YouTube », « Ouvre le site de la SNCF »** → `open_url` : ouvre le **navigateur**
  directement sur la page.
- **« Cherche des recettes de crêpes et montre-moi »** → `web_search` : ouvre le **navigateur**
  sur la page de résultats.

## 🦾 Tâches multi-étapes (agent)

Pour une demande complexe, Jarvis passe en **mode agent** : il regarde l'écran, fait UNE action,
attend, re-regarde, refait une action… jusqu'à finir (max 8 étapes).
Exemple : « **Ouvre WhatsApp et écris *j'arrive* à Maman** » → il ouvre l'app, trouve la
conversation, sélectionne le champ, tape le texte, appuie sur envoyer.
*(Nécessite le service Accessibilité actif. La fiabilité dépend de l'app ciblée.)*

## 🗺️ Ce qui marche déjà vs. la suite

**✅ Déjà fonctionnel**
- Voix (FR) entrée/sortie · bulle flottante persistante
- **Mot d'activation « Jarvis »** mains-libres (avec le modèle Vosk)
- Cerveau Groq (texte + vision) · réponses parlées
- Lecture continue de l'écran + mémoire 15 min
- **Réponse web parlée** (web_answer) **et** ouverture navigateur (open_url / web_search)
- **Agent multi-étapes** (ouvrir/cliquer/écrire/défiler en chaîne)
- Actions : ouvrir app, ouvrir URL, accueil/retour/récents, défilement, clic (texte + icône), saisie de texte
- Scan automatique des apps installées

**🔜 Améliorations possibles**
- Historique de conversation + fiches mémoire persistantes
- Réglage du nombre max d'étapes de l'agent
- Modèle vocal wake-word intégré à l'APK (au lieu du téléchargement manuel)

---

## ⚠️ Sécurité

- La clé Groq vit dans `local.properties` (ignoré par git). **Ne la partage pas.** Si elle a fuité, régénère-la.
- Le Service d'Accessibilité est **très puissant** (il voit tout l'écran). N'installe cette app que
  sur **ton** téléphone. Le code n'envoie rien d'autre que tes demandes + le contexte d'écran à
  Groq pour obtenir la réponse.
