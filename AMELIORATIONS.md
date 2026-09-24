# Améliorations proposées — Omni-Swarm Protocol & Tree4Five OSP Bridge

*Propositions au 2026-09-18, jour de la publication Play Store (v0.6.0, `com.tree4five.osp`).*

Priorités : **P1** = à faire avant de promouvoir l'app au-delà du cercle dev · **P2** = forte valeur, cycle suivant · **P3** = confort / long terme.
Efforts : **S** < 1 h · **M** quelques heures · **L** 1–2 jours et plus.

## Quick wins (P1, effort S — une demi-journée pour tout)

| # | Amélioration | Où | Pourquoi |
|---|---|---|---|
| Q1 | Unifier le contrat `/osp/query` : le CLI Python envoie `text`, le bot JS attend `query` | `mcp/osp_cli.py` + `osp/core.mjs` | Drift de contrat détecté en test live — un interop nœud/CLI quelconque échoue sans message clair |
| Q2 | Persister le budget du bot (fichier JSON rechargé au démarrage) | `whatsapp-bot osp/core.mjs` (`Budget` en mémoire) | Un restart réinitialise le budget à 50 → un pair peut dépasser son quota sans le savoir |
| Q3 | Logger les échecs d'auth **avant** le dispatch (401 actuellement silencieux) | `whatsapp-bot server.js` | Un token erroné ne laisse **aucune trace** — 30 min perdues à diagnostiquer |
| Q4 | CI GitHub Actions : lancer les 87 tests (36 Python + 38 JVM + 13 JS) à chaque push | `.github/workflows/tests.yml` | Le paper revendique 87/87 ; il faut le garantir mécaniquement |
| Q5 | Ajouter le lien Play Store + la vidéo YouTube dans le README | `README.md` | Le dépôt est la porte d'entrée du projet — l'app est désormais installable |

## 1. Sécurité (P1)

| # | Amélioration | Effort | Détail |
|---|---|---|---|
| S1 | **Signer Ed25519 + TLS de production (REQ-S-01)** | L | Engagé dans la fiche Play (« HONEST POSTURE »). Étapes : clé Ed25519 par nœud, champ `sig` des paquets signé à la place du HMAC dev, TLS (certificat auto-signé épinglé via le token de lien) sur le hub HTTP Kotlin et le hub JS. Les tests conformance L2 doivent valider le refus d'un paquet mal signé. |
| S2 | Rotation du token de lien | S | Champ `token_rotated_to` dans un paquet dédié : le pair bascule sans re-saisie manuelle des 48 caractères. |
| S3 | Cache anti-rejeu persistant (jti sur disque, TTL nettoyé) | M | Actuellement en mémoire : un redémarrage rouvre la fenêtre au rejeu d'un paquet intercepté. |
| S4 | HTTPS local partout (`usesCleartextTraffic=false` atteignable) | M | Dépend de S1 ; supprime la seule réserve du formulaire Data safety. |

## 2. Peering & routage (P1–P2)

| # | Amélioration | Effort | Détail |
|---|---|---|---|
| P1b | **Peering par QR code** | M | L'écran « Connect a peer » génère/scanne un QR contenant `url+token` : fini la saisie des 48 caractères au clavier (la source n°1 d'erreurs en test). Zebra Crossing (ZXing) ou ML Kit ; l'URL reste copiable en secours. |
| P2b | Découverte mDNS (`_osp._tcp`) | M | Le bouton START NODE annonce le nœud sur le LAN ; l'app liste les pairs détectés — l'URL manuelle devient l'option avancée. |
| P3b | Routage multi-saut avec table de centroïdes (paper R19) | L | Un nœud N2 relaie vers le nœud au centroïde d'embedding le plus proche ; le TTL/gas limite déjà la boucle. Prototype CLI d'abord, puis Android. |
| P4b | Mapping d'ontologies entre nœuds (paper R18) | L | Chaque nœud expose ses libellés de chunks ; alignement vectoriel au handshake pour annoter les requêtes distantes. |

## 3. Qualité des réponses — le firewall (P2)

| # | Amélioration | Effort | Détail |
|---|---|---|---|
| F1 | **Vérification NLI en couche L2** (remplace/complète la couverture d'embedding) | M | Le score `groundedness` actuel mesure la couverture côté requête ; un petit NLI local (ex. DeBERTa-3-small ONNX, ~90 Mo) note (evidence → answer) et détecte les contradictions que la similarité cosine laisse passer. Le paper (§6, R15/R16) le positionne comme suite logique. |
| F2 | Calibration des scores avant affichage | M | Température/Platt scaling sur un jeu de validation : `0.719` doit vouloir dire 72 % de vraisemblance d'être ancré (R15). Stocker les paramètres par nœud dans les prefs. |
| F3 | Seuil groundedness configurable par tier | S | T0 = 0.35 (défaut), T2 (sensible) = 0.50 ; expose le réglage dans l'app. |
| F4 | Afficher les citations dans l'app | S | L'outcome contient déjà les refs de chunks : les rendre cliquables sous la réponse (source, score par chunk). Argument de vente n°1 pour la review Play et la vidéo. |
| F5 | Numérotation d'articles dans l'indexation chroma | S | « résume le 3ème article » → INSUFFICIENT_EVIDENCE : découper les documents avec métadonnées `article_no` et filtrer dessus. Corrige le seul scénario de démo qui échoue. |

## 4. App Android (P2)

| # | Amélioration | Effort | Détail |
|---|---|---|---|
| A1 | i18n FR/EN (`values-fr/`) | S | La Play Store listing est EN ; les utilisateurs français méritent l'UI. Les textes sont déjà écrits (guide PDF FR). |
| A2 | Liste des pairs gérable (voir/éditer/supprimer, état connecté) | M | Aujourd'hui un champ URL+token aveugle : on ne voit pas si le pair est joignable sans lancer une requête. |
| A3 | Historique des requêtes (SQLite, 30 derniers outcomes) | M | L'écran ne montre que la dernière réponse ; l'historique fait de l'app un outil d'audit (cohérent avec Forenseek LOG ALL). |
| A4 | Notification foreground + exemption optimisation batterie | S | Le nœud doit survivre au Doze ; guidage vers le réglage par fabricant. |
| A5 | Écran « stats du nœud » (paquets émis/reçus, refus firewall, gas consommé) | M | Matérialise le cost ledger du paper ; alimente la réputation locale. |

## 5. Diffusion & documentation (P2–P3)

| # | Amélioration | Effort | Détail |
|---|---|---|---|
| D1 | Localisation fr-FR de la fiche Play | S | La Console accepte les traductions : traduire nom (garder EN), description courte/longue, notes v0.6.1. |
| D2 | 4e capture : l'écran REJECTED 0.00 | S | La seule capture qui montre le firewall — différentiatrice face aux « AI apps » génériques. |
| D3 | Lien vidéo YouTube sur la fiche + README | S | La démo de 140 s existe déjà ; la fiche Play accepte une URL YouTube. |
| D4 | GitHub Pages : privacy policy + docs HTML | S | URL stable `ybenyedder.github.io/swarmknowledgeprotocol/` pour la politique de confidentialité (plus présentable qu'un lien raw) et lecture confortable des docs. |
| D5 | Paper sur arXiv (cs.DC / cs.AI) | M | `osp_paper.html` est complet ; conversion LaTeX + soumission. Crédibilité académique + citations entrantes. |
| D6 | Démo essaim à 3+ nœuds en vidéo | M | La vidéo actuelle montre 2 nœuds ; un essaim tablette + 2 téléphones (avec QR peering de P1b) illustre le routage. |

## Packing suggéré

- **v0.6.1** (hotfix, cette semaine) : Q1, Q2, Q3, A1, D1, D2, D3 — versionCode 2.
- **v0.7** (1–2 semaines) : S1 (+S2, S3), P1b QR peering, F4, A2, D4 — versionCode 3, la version « prête à montrer ».
- **v0.8** (moins prioritaire) : P2b/P3b, F1/F2, A3/A5, D5, D6.
