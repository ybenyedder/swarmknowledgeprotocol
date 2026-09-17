#!/usr/bin/env python3
"""Guide d'utilisation PDF (français) pour Tree4Five OSP Bridge.

Pages A4 (150 dpi) au style tree4five (fond #0f0c29, accents cyan),
captures réelles prises sur la tablette pendant une session vécue :
démarrage du nœud, appairage avec le bot distant, requête vérifiée
« résume le magazine de robotique reçu » (RESOLVED) et pare-feu
groundedness (REJECTED).

  python3 make_guide.py  →  user_guide_fr.pdf
"""

from PIL import Image, ImageDraw, ImageFont
import os

HERE = os.path.dirname(os.path.abspath(__file__))
GUIDE = os.path.join(HERE, "guide")

PAGE = (1240, 1754)          # A4 @150dpi
MARGIN = 70
DARK = (15, 12, 41)          # #0f0c29
SURFACE = (36, 36, 62)       # #24243e
CYAN = (0, 242, 254)         # #00f2fe
BLUE = (79, 172, 254)        # #4facfe
TXT = (222, 226, 244)
TXT_DIM = (160, 166, 196)

FONTDIR = "/usr/share/fonts/truetype/dejavu/"
def F(size, bold=False):
    return ImageFont.truetype(FONTDIR + ("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"), size)


def wrap(d, text, font, maxw):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if d.textlength(t, font=font) <= maxw:
            cur = t
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


class Page:
    def __init__(self):
        self.img = Image.new("RGB", PAGE, DARK)
        self.d = ImageDraw.Draw(self.img)
        self.y = MARGIN

    def title(self, text, size=40, color=CYAN, y=None, wrap_w=None):
        f = F(size, bold=True)
        yy = self.y if y is None else y
        for line in (wrap(self.d, text, f, wrap_w) if wrap_w else [text]):
            self.d.text((MARGIN, yy), line, font=f, fill=color)
            yy += int(size * 1.3)
        self.y = yy + 18

    def paras(self, items, x=MARGIN, w=PAGE[0] - 2 * MARGIN):
        """items = [(texte, size, bold, color, gap_after)]"""
        for text, size, bold, color, gap in items:
            f = F(size, bold=bold)
            for line in wrap(self.d, text, f, w):
                self.d.text((x, self.y), line, font=f, fill=color)
                self.y += int(size * 1.42)
            self.y += gap

    def shot(self, path, x, y, w, border=SURFACE):
        img = Image.open(path)
        h = int(img.height * w / img.width)
        img = img.resize((w, h), Image.LANCZOS)
        self.d.rectangle([x - 4, y - 4, x + w + 3, y + h + 3], outline=border, width=2)
        self.img.paste(img, (x, y))
        return h

    def footer(self, n, total):
        f = F(20)
        self.d.text((MARGIN, PAGE[1] - 52),
                    "Tree4Five OSP Bridge — guide d'utilisation — OSP v0.6", font=f, fill=TXT_DIM)
        t = f"page {n}/{total}"
        self.d.text((PAGE[0] - MARGIN - self.d.textlength(t, font=f), PAGE[1] - 52),
                    t, font=f, fill=TXT_DIM)


def text_width(d, text, font):
    return d.textlength(text, font=font)


pages = []

# ---------------------------------------------------------------- couverture
p = Page()
icon = Image.open(os.path.join(HERE, "play_icon_512.png")).resize((230, 230), Image.LANCZOS)
p.img.paste(icon, ((PAGE[0] - 230) // 2, 150))
p.y = 410
p.title("Tree4Five OSP Bridge", size=54, color=TXT, y=p.y)
p.title("Guide d'utilisation — version 0.6.0", size=30, y=p.y + 6)
p.y += 60
p.paras([
    ("Transformez votre tablette en nœud d'un essaim de connaissances : vos "
     "appareils se posent des questions directement, entre eux, sans cloud — "
     "et aucune réponse n'est acceptée sans preuve vérifiable.", 26, False, TXT, 26),
], w=PAGE[0] - 2 * MARGIN - 160)
# encadré exemple filé
box_top = p.y + 10
p.paras([
    ("L'exemple filé de ce guide", 28, True, CYAN, 10),
    ("« Demande au pair distant de me résumer le magazine de robotique reçu. » "
     "Nous allons démarrer le nœud, appairer la tablette avec le bot, poser la "
     "question « résume le magazine de robotique reçu » et lire la réponse "
     "vérifiée — puis voir ce que fait l'essaim quand la preuve manque.", 24, False, TXT, 0),
], x=MARGIN + 30, w=PAGE[0] - 2 * MARGIN - 60)
p.d.rectangle([MARGIN, box_top - 16, PAGE[0] - MARGIN, p.y + 16], outline=BLUE, width=2)
p.footer(1, 6)
pages.append(p)

# ------------------------------------------------- étape 1 : démarrer le nœud
p = Page()
p.title("1 · Démarrez votre nœud", wrap_w=620)
p.paras([
    ("Ouvrez Tree4Five OSP Bridge et touchez START NODE.", 26, True, TXT, 18),
    ("La carte « Node status » affiche l'état du nœud : son identité "
     "(ici osp-dbda549f, classe N2), le moteur IA local lié (LLMProvider "
     "v1.1.4, embeddings dim=896), les connaissances locales (0 chunk au "
     "départ — Teach a chunk les ajoute) et le budget du jour (50 requêtes "
     "vérifiées).", 24, False, TXT, 14),
    ("La ligne HTTP montre le port d'écoute (8090) : c'est l'adresse que vos "
     "autres appareils utiliseront pour interroger CETTE tablette. Le secret "
     "de liaison n'est jamais affiché en clair — seulement ses premiers "
     "caractères (79daa6…).", 24, False, TXT, 14),
    ("STOP arrête le nœud proprement.", 24, False, TXT_DIM, 0),
], x=MARGIN, w=600)
p.shot(os.path.join(GUIDE, "step1_node_started.png"), 720, MARGIN + 10, 450)
p.footer(2, 6)
pages.append(p)

# ------------------------------------------------- étape 2 : appairer le pair
p = Page()
p.title("2 · Connectez le pair distant", wrap_w=620)
p.paras([
    ("La carte « Connect a peer » relie la tablette à un autre nœud — ici le "
     "bot WhatsApp de la maison, joignable sur le réseau local via son relais "
     "http://192.168.1.105:8390.", 24, False, TXT, 14),
    ("Saisissez l'URL du pair, puis son token de liaison (le secret partagé "
     "que le propriétaire du pair vous a remis — collez-le SANS espaces). "
     "Touchez ADD PEER : l'app interroge /osp/status du pair, en déduit son "
     "identité (whatsapp-bot) et l'enregistre.", 24, False, TXT, 14),
    ("Le bloc résultat confirme : « peer added: whatsapp-bot (1 peers) ». Le "
     "token est rangé dans l'espace privé de l'app et n'apparaît plus jamais "
     "en clair.", 24, False, TXT, 14),
    ("Astuce : un token collé avec un espace parasite est la cause n°1 des "
     "échecs « 0 verified capable bids ».", 22, False, CYAN, 0),
], x=MARGIN, w=600)
p.shot(os.path.join(GUIDE, "step2_peer_url.png"), 720, MARGIN + 10, 450)
p.footer(3, 6)
pages.append(p)

# --------------------------------------------- étape 3 : la question vérifiée
p = Page()
p.title("3 · Posez la question vérifiée", wrap_w=620)
p.paras([
    ("Touchez la puce « résume le magazine de robotique reçu » (ou tapez "
     "votre question), puis RUN VERIFIED QUERY (T0).", 24, False, TXT, 14),
    ("Derrière un seul toucher : un paquet scellé PROPOSE part vers le pair, "
     "le pair répond BID avec la preuve qu'il détient (couverture 0.65), "
     "génère la réponse DANS l'enveloppe de preuve (RESOLVE), et la tablette "
     "vérifie l'ancrage de la réponse avant de l'afficher.", 24, False, TXT, 14),
    ("Comptez 30 à 90 s : la génération a lieu sur le LLM du pair.", 22, False, TXT_DIM, 14),
    ("Résultat : mode=RESOLVED, groundedness=0.654 — « Le magazine s'appelle "
     "« PLANÈTE ROBOTS », une « Publication bimestrielle » dont le titre "
     "complet est « ÉDITO INTELLIGENCE ARTIFICIELLE ET ROBOTIQUE ». Il est "
     "publié par la société « SARL Lexing Editions ». »", 24, False, CYAN, 0),
], x=MARGIN, w=600)
p.shot(os.path.join(GUIDE, "step3_query_resolved.png"), 720, MARGIN + 10, 450)
p.footer(4, 6)
pages.append(p)

# ------------------------------------------------- étape 4 : lire le verdict
p = Page()
p.title("4 · Lisez le verdict", wrap_w=620)
p.paras([
    ("RESOLVED — réponse scellée et ancrée dans la preuve (groundedness "
     "suffisante).", 24, True, CYAN, 10),
    ("REJECTED — le pare-feu a bloqué la réponse : son ancrage (0.00) est "
     "sous le seuil (0.35). Ici « que montre la vidéo reçue aujourd'hui » : "
     "aucune preuve textuelle n'existe pour cette vidéo, donc aucune réponse "
     "n'est affichée. Le nœud préfère se taire qu'inventer.", 24, False, TXT, 10),
    ("NO_QUORUM — aucun pair vérifié n'a pu répondre : « 0 verified capable "
     "bids » (token/URL du pair fautif) ou « winner could not generate » (le "
     "LLM du pair était occupé — réessayez).", 24, False, TXT, 10),
    ("Et pour « résume le 3ᵉ article du magazine » ? Le pair a retrouvé des "
     "extraits du magazine, mais la preuve indexée ne numérote pas les "
     "articles : il répond INSUFFICIENT_EVIDENCE plutôt que de choisir un "
     "article au hasard. Reformulez par le sujet (« résume le magazine de "
     "robotique reçu ») et la réponse vient.", 24, False, TXT, 0),
], x=MARGIN, w=600)
p.shot(os.path.join(GUIDE, "step4_rejected.png"), 720, MARGIN + 10, 450)
p.footer(5, 6)
pages.append(p)

# ------------------------------------------------------- sécurité + mémo
p = Page()
p.title("Sécurité & mémo de dépannage")
p.paras([
    ("Ce qui protège l'échange", 28, True, CYAN, 12),
    ("• Paquets scellés et signés, horodatés, TTL 60 s, identifiant unique "
     "(jti) contre la relecture.", 23, False, TXT, 8),
    ("• Gaz C1 : chaque saut coûte 3, la profondeur coûte 1/saut — les "
     "requêtes ne bouclent pas.", 23, False, TXT, 8),
    ("• Budget 50 requêtes vérifiées/jour (T0), recharge quotidienne.", 23, False, TXT, 8),
    ("• Les questions et les chunks ne transitent qu'entre VOS appareils "
     "appairés — aucun serveur tree4five, aucun cloud.", 23, False, TXT, 12),
    ("Mémo de dépannage", 28, True, CYAN, 12),
    ("• REJECTED groundedness x.xx < 0.35 → la question dépasse la preuve "
     "disponible ; reformulez ou enseignez le chunk (Teach a chunk).", 23, False, TXT, 8),
    ("• NO_QUORUM 0 verified capable bids → peer injoignable ou token "
     "erroné (espaces !) : re-ajoutez le pair.", 23, False, TXT, 8),
    ("• winner could not generate / RFO timeout → le LLM du pair est en "
     "file d'attente ; attendez un instant et relancez.", 23, False, TXT, 8),
    ("• Après une réinstallation de l'app, touchez START NODE — le peering "
     "persiste, le service, lui, ne redémarre pas tout seul.", 23, False, TXT, 12),
    ("Bon à savoir", 28, True, CYAN, 12),
    ("• Les puces d'exemples remplissent le champ question en un toucher.", 23, False, TXT, 8),
    ("• Teach a chunk ajoute une connaissance locale : cette tablette saura "
     "y répondre quand d'autres nœuds l'interrogeront.", 23, False, TXT, 8),
    ("• Le menu Forenseek rassemble les outils de diagnostic : LOG ALL exporte "
     "un journal complet du nœud (secrets tronqués), VERSION affiche app et "
     "protocole, RESET ALL efface identité, pairs et connaissances.", 23, False, TXT, 0),
])
p.footer(6, 6)
pages.append(p)

out = os.path.join(HERE, "user_guide_fr.pdf")
imgs = [p.img for p in pages]
imgs[0].save(out, save_all=True, append_images=imgs[1:], resolution=150.0)
print("wrote", out, f"({len(pages)} pages)")
