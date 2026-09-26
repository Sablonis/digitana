#!/usr/bin/env python3
"""Erzeugt die eigenen Symbole in piktogramme/eigene/.

Ein Teil sind eigene Zeichnungen (Rollstuhl, Person im Rollstuhl, Standing,
Thermometer, Tageszeiten, Wecker, Wochenende), der Rest setzt Mulberry-Symbole
neu zusammen, z. B. Teller + Sonne = Mittagessen. Die fertigen SVG-Dateien
liegen bereits im Ordner; das Skript braucht man nur, wenn man sie ändern will.

Aufruf:
    python3 symbole.py

Gezeichnet wird im Stil der Mulberry Symbols: Zeichenfläche 850 × 850,
Konturen #231f20 in 21 bzw. 14 Einheiten Strichstärke, flache Farben.
"""

import math
import re

from build import PIKTOGRAMME, lade_mulberry

EIGENE = PIKTOGRAMME / "eigene"
LINIE = "#231f20"
DICK, DUENN = 21, 14
SCHRIFT = "Atkinson Hyperlegible, Arial, Helvetica, sans-serif"
ROT, GRUEN, BLAU, HELLBLAU = "#e03131", "#34a047", "#5668af", "#a3d9ff"
GELB, BLASSGELB, GRAU, DUNKEL = "#fbed24", "#fff5b4", "#c8c8c8", "#444041"


def svg(inhalt, groesse=850):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {groesse} {groesse}">'
        f"{inhalt}</svg>\n"
    )


# ---------------------------------------------------------------- Mulberry einbetten

_zaehler = 0


def _praefix(inhalt, p):
    """Macht ids und Klassen eindeutig, damit eingebettete Symbole sich nicht stören."""
    inhalt = re.sub(r'id="([^"]+)"', lambda m: f'id="{p}{m.group(1)}"', inhalt)
    inhalt = re.sub(r"url\(#([^)]+)\)", lambda m: f"url(#{p}{m.group(1)})", inhalt)
    inhalt = re.sub(r'href="#([^"]+)"', lambda m: f'href="#{p}{m.group(1)}"', inhalt)
    inhalt = re.sub(
        r'class="([^"]+)"',
        lambda m: 'class="' + " ".join(p + k for k in m.group(1).split()) + '"',
        inhalt,
    )
    return re.sub(
        r"<style[^>]*>(.*?)</style>",
        lambda m: m.group(0).replace(
            m.group(1), re.sub(r"\.([A-Za-z_][\w-]*)", rf".{p}\1", m.group(1))
        ),
        inhalt,
        flags=re.S,
    )


def mulberry(name, x, y, breite, hoehe, ausschnitt=None, farbe=None):
    """Bettet ein Mulberry-Symbol als verschachteltes SVG an (x, y) ein.

    ausschnitt = (x0, y0, x1, y1) schneidet das Symbol auf seinen Inhalt zu,
    farbe ersetzt die Linienfarbe (z. B. für einen grünen Haken).
    """
    global _zaehler
    _zaehler += 1
    text = lade_mulberry(name).read_text("utf-8")
    kopf, inhalt = re.search(r"<svg\b([^>]*)>(.*)</svg>", text, re.S).groups()
    viewbox = re.search(r'viewBox="([^"]+)"', kopf).group(1)
    if ausschnitt:
        x0, y0, x1, y1 = ausschnitt
        viewbox = f"{x0} {y0} {x1 - x0} {y1 - y0}"
    if farbe:
        inhalt = inhalt.replace("#231f20", farbe).replace(
            'stroke="#000"', f'stroke="{farbe}"'
        )
    inhalt = _praefix(inhalt, f"m{_zaehler}-")
    return (
        f'<svg x="{x}" y="{y}" width="{breite}" height="{hoehe}" viewBox="{viewbox}" '
        f'overflow="visible">{inhalt}</svg>'
    )


# Fragezeichen aus dem Mulberry-Symbol «how», als Pfad zum freien Platzieren
FRAGEZEICHEN = (
    '<g fill="none" stroke="#231f20" stroke-width="52"><path d="M412.787 699.493v74.634'
    "M256.791 255.432c0-98.92 75.196-179.11 167.924-179.11 92.738 0 167.913 80.19 "
    "167.913 179.11 0 74.056-42.125 137.615-102.233 164.886 0 0-77.608 29.126-77.608 "
    '94.451v121.287"/></g>'
)


def fragezeichen(x, y, hoehe, strich=None):
    """Das Mulberry-Fragezeichen (Inhalt 257–593 × 76–774) an (x, y) mit Höhe hoehe.

    strich legt die sichtbare Strichstärke fest (sonst wird sie mitskaliert).
    """
    s = hoehe / 698
    zeichen = FRAGEZEICHEN
    if strich:
        zeichen = zeichen.replace(
            'stroke-width="52"', f'stroke-width="{strich / s:.1f}"'
        )
    return f'<g transform="translate({x} {y}) scale({s:.4f}) translate(-257 -76)">{zeichen}</g>'


# ---------------------------------------------------------------- Tageszeiten


def strahlen(cx, cy, r1, r2, winkel, breite=DICK):
    linien = "".join(
        f'<line x1="{cx + r1 * math.cos(math.radians(a)):.1f}" y1="{cy + r1 * math.sin(math.radians(a)):.1f}" '
        f'x2="{cx + r2 * math.cos(math.radians(a)):.1f}" y2="{cy + r2 * math.sin(math.radians(a)):.1f}"/>'
        for a in winkel
    )
    return f'<g stroke="{LINIE}" stroke-width="{breite}" stroke-linecap="round">{linien}</g>'


def sonne(cx, cy, r=62, winkel=range(0, 360, 45)):
    return strahlen(cx, cy, r + 28, r + 72, winkel) + (
        f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{GELB}" stroke="{LINIE}" stroke-width="{DICK}"/>'
    )


def pfeil(x, y1, y2):
    d = 42 if y2 < y1 else -42
    return (
        f'<path d="M{x} {y1}V{y2}M{x - d} {y2 + d}L{x} {y2}L{x + d} {y2 + d}" fill="none" '
        f'stroke="{LINIE}" stroke-width="24" stroke-linecap="round" stroke-linejoin="round"/>'
    )


def stern(cx, cy, r):
    punkte = " ".join(
        f"{cx + (r if k % 2 == 0 else r * 0.45) * math.cos(math.radians(-90 + k * 36)):.1f},"
        f"{cy + (r if k % 2 == 0 else r * 0.45) * math.sin(math.radians(-90 + k * 36)):.1f}"
        for k in range(10)
    )
    return (
        f'<polygon points="{punkte}" fill="{BLASSGELB}" stroke="{LINIE}" '
        f'stroke-width="{DUENN}" stroke-linejoin="round"/>'
    )


def tageszeit(himmel, gras, inhalt):
    rahmen = 'x="85" y="85" width="680" height="680" rx="64"'
    bogen = (
        '<path d="M215 640C290 150 560 150 635 640" fill="none" stroke="#231f20" '
        'stroke-opacity=".3" stroke-width="12" stroke-dasharray="24 24" stroke-linecap="round"/>'
    )
    huegel = (
        f'<path d="M60 648C260 590 590 590 790 640V790H60Z" fill="{gras}" stroke="{LINIE}" '
        f'stroke-width="{DICK}" stroke-linejoin="round"/>'
    )
    return svg(
        f'<defs><clipPath id="rahmen"><rect {rahmen}/></clipPath></defs>'
        f'<g clip-path="url(#rahmen)"><rect width="850" height="850" fill="{himmel}"/>'
        f"{bogen if himmel != '#1f2d6e' else ''}{inhalt}{huegel}</g>"
        f'<rect {rahmen} fill="none" stroke="{LINIE}" stroke-width="{DICK}"/>'
    )


def tageszeiten():
    oben = (205, 240, 300, 335)  # Strahlen über dem Horizont
    mond = (
        f'<path d="M456 156A177 177 0 1 0 456 510A99 177 0 0 1 456 156Z" fill="{BLASSGELB}" '
        f'stroke="{LINIE}" stroke-width="{DICK}" stroke-linejoin="round"/>'
    )
    return {
        "morgen": tageszeit(
            BLASSGELB, "#abd153", sonne(215, 606, winkel=oben) + pfeil(215, 450, 280)
        ),
        "mittag": tageszeit(HELLBLAU, "#abd153", sonne(425, 272)),
        "nachmittag": tageszeit("#d3ecff", "#abd153", sonne(548, 364)),
        "abend": tageszeit(
            "#fcc98a", "#8dbb3f", sonne(635, 606, winkel=oben) + pfeil(635, 280, 450)
        ),
        "nacht": tageszeit(
            "#1f2d6e",
            "#2f6b3a",
            mond + stern(595, 200, 50) + stern(666, 400, 37) + stern(190, 207, 34),
        ),
    }


# ---------------------------------------------------------------- eigene Gegenstände


def wecker():
    cx, cy, r = 425, 480, 262
    teile = [
        # Glocken, Hammer, Füsse
        f'<g fill="{GRAU}" stroke="{LINIE}" stroke-width="{DICK}">'
        '<path d="M150 322A118 118 0 0 1 318 156Z"/><path d="M700 322A118 118 0 0 0 532 156Z"/></g>'
        f'<path d="M425 214V150M385 142H465" stroke="{LINIE}" stroke-width="{DICK}" stroke-linecap="round"/>'
        f'<path d="M270 700L205 790M580 700L645 790" stroke="{LINIE}" stroke-width="30" stroke-linecap="round"/>'
        f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{BLAU}" stroke="{LINIE}" stroke-width="{DICK}"/>'
        f'<circle cx="{cx}" cy="{cy}" r="{r - 48}" fill="#fff" stroke="{LINIE}" stroke-width="{DUENN}"/>'
    ]
    for k in range(12):
        a = math.radians(k * 30 - 90)
        r1 = r - 102 if k % 3 == 0 else r - 84
        teile.append(
            f'<line x1="{cx + r1 * math.cos(a):.1f}" y1="{cy + r1 * math.sin(a):.1f}" '
            f'x2="{cx + (r - 64) * math.cos(a):.1f}" y2="{cy + (r - 64) * math.sin(a):.1f}" '
            f'stroke="{LINIE}" stroke-width="{DICK if k % 3 == 0 else DUENN}" stroke-linecap="round"/>'
        )
    stunde, minute = math.radians(7 * 30 - 90), math.radians(-90)  # 7 Uhr
    teile.append(
        f'<path d="M{cx + 105 * math.cos(stunde):.1f} {cy + 105 * math.sin(stunde):.1f}L{cx} {cy}'
        f'L{cx + 160 * math.cos(minute):.1f} {cy + 160 * math.sin(minute):.1f}" fill="none" '
        f'stroke="{LINIE}" stroke-width="26" stroke-linecap="round" stroke-linejoin="round"/>'
        f'<circle cx="{cx}" cy="{cy}" r="20" fill="{LINIE}"/>'
    )
    return svg("".join(teile))


def einkaufszettel():
    zeilen = []
    for k, y in enumerate((210, 318, 426, 534)):
        zeilen.append(
            f'<rect x="200" y="{y - 32}" width="64" height="64" rx="8" fill="#fff" '
            f'stroke="{LINIE}" stroke-width="{DUENN}"/>'
            f'<path d="M298 {y + 6}H520" stroke="#797878" stroke-width="{DUENN}" stroke-linecap="round"/>'
        )
        if k < 2:
            zeilen.append(
                f'<path d="M208 {y}L228 {y + 22}L262 {y - 26}" fill="none" stroke="{GRUEN}" '
                f'stroke-width="{DICK}" stroke-linecap="round" stroke-linejoin="round"/>'
            )
    zettel = (
        f'<g transform="rotate(-7 360 380)"><rect x="150" y="90" width="420" height="580" rx="12" '
        f'fill="#fff" stroke="{LINIE}" stroke-width="{DICK}"/>{"".join(zeilen)}</g>'
    )
    return svg(zettel + mulberry("basket_2", 420, 410, 410, 418, (66, 62, 776, 784)))


def wochenende():
    """Zwei Kalenderblätter «Sa» und «So» (Zeichenfläche 500)."""
    teile = []
    for x, tag in ((45, "Sa"), (265, "So")):
        w, h, y = 190, 250, 132
        ringe = "".join(
            f'<rect x="{x + dx - 9}" y="{y - 26}" width="18" height="46" rx="9" fill="#fff" '
            f'stroke="{LINIE}" stroke-width="10"/>'
            for dx in (58, 132)
        )
        teile.append(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="18" fill="#fff" stroke="{LINIE}" stroke-width="12"/>'
            f'<path d="M{x} {y + 64}V{y + 18}a18 18 0 0 1 18-18H{x + w - 18}a18 18 0 0 1 18 18V{y + 64}Z" '
            f'fill="{ROT}" stroke="{LINIE}" stroke-width="12" stroke-linejoin="round"/>{ringe}'
            f'<text x="{x + 95}" y="{y + 205}" text-anchor="middle" font-family="{SCHRIFT}" '
            f'font-weight="700" font-size="112" fill="#c92a2a">{tag}</text>'
        )
    return svg("".join(teile), groesse=500)


# ---------------------------------------------------------------- Figuren im Mulberry-Stil

HAUT, HAAR, HEMD, HOSE, POLSTER = "#ffeec8", "#9e5c26", "#a9d7f3", "#7a7878", BLAU
FIGUR = 16  # Strichstärke der Figuren


def form(d, fuellung, breite=FIGUR):
    return (
        f'<path d="{d}" fill="{fuellung}" stroke="{LINIE}" stroke-width="{breite}" '
        'stroke-linejoin="round"/>'
    )


def hals(dx=0):
    return f'<g transform="translate({dx} 0)">{form("M300 240V200H338V240Z", HAUT)}</g>'


def kopf(dx=0):
    """Kopf im Profil mit Blick nach rechts, Mitte etwa bei (320 + dx, 148)."""
    teile = (
        form(
            "M318 72C362 72 388 102 388 138L402 160Q404 167 396 169L388 171"
            "C384 205 356 226 320 226C282 226 254 196 254 150C254 104 280 72 318 72Z",
            HAUT,
        )
        + form(
            "M252 162C238 96 280 58 330 60C374 62 398 90 394 118C366 100 334 104 310 126"
            "C300 136 294 152 290 174C274 178 258 174 252 162Z",
            HAAR,
            14,
        )
        + f'<ellipse cx="300" cy="160" rx="14" ry="20" fill="{HAUT}" stroke="{LINIE}" stroke-width="10"/>'
        + f'<circle cx="362" cy="138" r="8" fill="{LINIE}"/>'
        + f'<path d="M346 118Q361 110 376 117M362 196Q373 200 384 193" fill="none" '
        f'stroke="{LINIE}" stroke-width="8" stroke-linecap="round"/>'
    )
    return f'<g transform="translate({dx} 0)">{teile}</g>'


def person_sitzend():
    """Sitzende Person im Profil, passend zum Rollstuhl."""
    return (
        hals()
        + form("M240 400L238 300Q240 240 300 232H340Q388 240 392 300L398 400Z", HEMD)
        + form("M524 440L568 430L666 684L614 696Z", HOSE)
        + form("M604 670Q612 660 660 662Q716 670 736 700H604Z", LINIE)
        + form(
            "M238 384H398Q402 392 412 392H540Q568 395 568 426Q568 460 540 462"
            "H262Q238 462 238 440Z",
            HOSE,
        )
        + kopf()
        + form(
            "M292 262Q288 330 312 405Q318 420 336 420L470 418V372H356"
            "Q342 320 340 262Q330 244 312 244Q296 246 292 262Z",
            HEMD,
        )
        + form("M466 372Q505 366 512 390Q514 414 480 420H466Z", HAUT)
    )


RAD_X, RAD_Y = 330, 605


def rollstuhl(mit_person=True):
    """Rollstuhl im Profil (Fahrtrichtung rechts), wahlweise mit Person."""
    rahmen = (
        '<path d="M160 214Q204 205 208 238L222 470H548L562 690L575 733M548 480L660 690'
        f'M640 700H745" fill="none" stroke="{LINIE}" stroke-width="30" '
        'stroke-linecap="round" stroke-linejoin="round"/>'
    )
    speichen = "".join(
        f'<line x1="{RAD_X + 26 * math.cos(math.radians(a)):.1f}" y1="{RAD_Y + 26 * math.sin(math.radians(a)):.1f}" '
        f'x2="{RAD_X + 128 * math.cos(math.radians(a)):.1f}" y2="{RAD_Y + 128 * math.sin(math.radians(a)):.1f}"/>'
        for a in range(0, 360, 30)
    )
    raeder = (
        f'<circle cx="{RAD_X}" cy="{RAD_Y}" r="160" fill="none" stroke="{LINIE}" stroke-width="30"/>'
        f'<circle cx="{RAD_X}" cy="{RAD_Y}" r="136" fill="none" stroke="{HOSE}" stroke-width="18"/>'
        f'<g stroke="{LINIE}" stroke-width="7">{speichen}</g>'
        f'<circle cx="{RAD_X}" cy="{RAD_Y}" r="26" fill="{HOSE}" stroke="{LINIE}" stroke-width="8"/>'
        f'<circle cx="575" cy="733" r="40" fill="{HOSE}" stroke="{LINIE}" stroke-width="14"/>'
        f'<circle cx="575" cy="733" r="9" fill="{LINIE}"/>'
    )
    return rahmen + (person_sitzend() if mit_person else "") + raeder


def standing():
    """Stehtrainer im Profil mit Hüft- und Kniepolster, Tisch und Rollen."""
    dx = 72
    return (
        f'<rect x="246" y="480" width="30" height="262" fill="{GRAU}" stroke="{LINIE}" stroke-width="14"/>'
        f'<rect x="262" y="408" width="56" height="114" rx="22" fill="{POLSTER}" stroke="{LINIE}" stroke-width="14"/>'
        f'<rect x="200" y="738" width="500" height="34" rx="14" fill="{HOSE}" stroke="{LINIE}" stroke-width="16"/>'
        f'<circle cx="244" cy="796" r="22" fill="{DUNKEL}" stroke="{LINIE}" stroke-width="12"/>'
        f'<circle cx="656" cy="796" r="22" fill="{DUNKEL}" stroke="{LINIE}" stroke-width="12"/>'
        f'<rect x="546" y="446" width="32" height="296" fill="{GRAU}" stroke="{LINIE}" stroke-width="14"/>'
        + hals(dx)
        + form("M316 430L312 300Q314 240 372 232H412Q460 240 464 300L470 430Z", HEMD)
        + form(
            "M314 418H470L466 520Q462 575 452 612L440 712H368L352 612Q326 530 314 470Z",
            HOSE,
        )
        + form("M360 700H440Q494 706 508 738H354Z", LINIE)
        + f'<rect x="452" y="556" width="100" height="74" rx="24" fill="{POLSTER}" stroke="{LINIE}" stroke-width="14"/>'
        + f'<rect x="398" y="420" width="372" height="42" rx="12" fill="#f6cc4b" stroke="{LINIE}" stroke-width="16"/>'
        + form(
            "M366 262Q362 330 378 408Q384 422 400 422L540 420V380H422"
            "Q414 320 414 262Q404 244 388 244Q370 246 366 262Z",
            HEMD,
        )
        + form("M536 380Q578 376 584 400Q586 420 552 422H536Z", HAUT)
        + kopf(dx)
    )


def pfeilspitze(x, y, richtung_x, richtung_y, laenge=46, breite=24):
    """Zwei Striche als Pfeilspitze an (x, y) in Richtung (richtung_x, richtung_y)."""
    winkel = math.atan2(richtung_y, richtung_x)
    punkte = [
        (
            x - laenge * math.cos(winkel + s * 0.6),
            y - laenge * math.sin(winkel + s * 0.6),
        )
        for s in (-1, 1)
    ]
    (x1, y1), (x2, y2) = punkte
    return (
        f'<path d="M{x1:.1f} {y1:.1f}L{x} {y}L{x2:.1f} {y2:.1f}" fill="none" stroke="{LINIE}" '
        f'stroke-width="{breite}" stroke-linecap="round" stroke-linejoin="round"/>'
    )


def kurvenpfeil(p0, c1, c2, p1, breite=24):
    (x0, y0), (a, b), (c, d), (x1, y1) = p0, c1, c2, p1
    return (
        f'<path d="M{x0} {y0}C{a} {b} {c} {d} {x1} {y1}" fill="none" stroke="{LINIE}" '
        f'stroke-width="{breite}" stroke-linecap="round"/>'
        + pfeilspitze(x1, y1, x1 - c, y1 - d)
    )


def kreispfeile(cx, cy, r):
    """Zwei gebogene Pfeile im Kreis: «anders, umstellen»."""
    teile = []
    for von, bis in ((200, 330), (20, 150)):
        a0, a1 = math.radians(von), math.radians(bis)
        x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
        x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
        teile.append(
            f'<path d="M{x0:.1f} {y0:.1f}A{r} {r} 0 0 1 {x1:.1f} {y1:.1f}" fill="none" '
            f'stroke="{LINIE}" stroke-width="24" stroke-linecap="round"/>'
            + pfeilspitze(round(x1, 1), round(y1, 1), -math.sin(a1), math.cos(a1), 40)
        )
    return "".join(teile)


def schneeflocke(cx, cy, r, farbe="#1c7ed6"):
    wege = []
    for a in range(0, 360, 60):
        t = math.radians(a - 90)
        wege.append(f"M{cx} {cy}L{cx + r * math.cos(t):.1f} {cy + r * math.sin(t):.1f}")
        bx, by = cx + 0.58 * r * math.cos(t), cy + 0.58 * r * math.sin(t)
        for seite in (-1, 1):
            u = t + seite * math.radians(45)
            wege.append(
                f"M{bx:.1f} {by:.1f}L{bx + 0.34 * r * math.cos(u):.1f} {by + 0.34 * r * math.sin(u):.1f}"
            )
    return (
        f'<path d="{"".join(wege)}" fill="none" stroke="{farbe}" stroke-width="20" '
        'stroke-linecap="round"/>'
    )


def thermometer():
    skala = "".join(
        f'<line x1="484" y1="{y}" x2="{524 if k % 2 == 0 else 508}" y2="{y}"/>'
        for k, y in enumerate(range(200, 540, 56))
    )
    return svg(
        f'<path d="M385 150A40 40 0 0 1 465 150V568A90 90 0 1 1 385 568Z" fill="#fff" '
        f'stroke="{LINIE}" stroke-width="{DICK}" stroke-linejoin="round"/>'
        f'<rect x="408" y="330" width="34" height="320" rx="17" fill="{ROT}"/>'
        f'<circle cx="425" cy="649" r="64" fill="{ROT}"/>'
        f'<g stroke="{LINIE}" stroke-width="{DUENN}" stroke-linecap="round">{skala}</g>'
        + schneeflocke(200, 560, 104)
        + sonne(672, 205, r=54)
    )


# ---------------------------------------------------------------- Kombinationen aus Mulberry


def kombinationen():
    klein = 0.66  # Rollstuhl mit Person im Bild «draussen»
    return {
        "wie_geht_es": svg(
            fragezeichen(60, 150, 540)
            + mulberry("face_neutral_3", 320, 110, 480, 600, (156, 82, 697, 758))
        ),
        "was_heute": svg(
            fragezeichen(60, 150, 540)
            + mulberry("today", 330, 130, 480, 578, (132, 72, 717, 777))
        ),
        "termine": svg(
            mulberry("calendar_month", 70, 50, 520, 661, (155, 83, 697, 772))
            + '<circle cx="640" cy="600" r="182" fill="#fff"/>'
            + mulberry("clock", 470, 430, 340, 340, (49, 55, 797, 803))
        ),
        "mittagessen": svg(
            mulberry("dinner", 40, 310, 640, 450, (79, 179, 765, 661))
            + mulberry("sun", 555, 40, 265, 265, (88, 89, 762, 759))
        ),
        "abendessen": svg(
            mulberry("dinner", 40, 310, 640, 450, (79, 179, 765, 661))
            + mulberry("night", 540, 60, 280, 241, (88, 78, 760, 656))
        ),
        "was_trinken": svg(
            fragezeichen(50, 150, 540)
            + mulberry("water", 320, 70, 245, 402, (222, 91, 649, 793))
            + mulberry("tea", 480, 400, 330, 330, (71, 67, 780, 780))
        ),
        "rollstuhl": svg(
            rollstuhl(mit_person=False)
            + kurvenpfeil((720, 110), (730, 300), (620, 390), (452, 410))
        ),
        "sitzt_bequem": svg(rollstuhl() + fragezeichen(600, 60, 300)),
        "anders_sitzen": svg(rollstuhl() + kreispfeile(650, 220, 115)),
        "standing": svg(standing()),
        "hinlegen": svg(
            mulberry("lie_on_back_,_to", 40, 232, 770, 385, (81, 254, 769, 598))
        ),
        "liegst_bequem": svg(
            mulberry("lie_on_back_,_to", 40, 330, 770, 385, (81, 254, 769, 598))
            + fragezeichen(600, 40, 260)
        ),
        "anders_liegen": svg(
            mulberry("lie_on_back_,_to", 40, 330, 770, 385, (81, 254, 769, 598))
            + kreispfeile(650, 175, 115)
        ),
        "draussen": svg(
            f'<rect x="30" y="742" width="790" height="40" rx="20" fill="#abd153" '
            f'stroke="{LINIE}" stroke-width="{DUENN}"/>'
            + mulberry("tree", 482, 277, 330, 465, (170, 70, 675, 781))
            + mulberry("sun", 60, 40, 190, 190, (88, 89, 762, 759))
            + f'<g transform="translate({40 - 145 * klein:.1f} {745 - 787 * klein:.1f}) scale({klein})">'
            + rollstuhl()
            + "</g>"
        ),
        "wie_war_tag": svg(
            mulberry("good", 40, 60, 390, 404, (107, 94, 754, 764))
            + mulberry("bad", 450, 360, 350, 471, (149, 70, 670, 783))
        ),
    }


def main():
    EIGENE.mkdir(parents=True, exist_ok=True)
    symbole = {
        **tageszeiten(),
        "wecker": wecker(),
        "thermometer": thermometer(),
        "einkaufszettel": einkaufszettel(),
        "wochenende": wochenende(),
        **kombinationen(),
    }
    for name, inhalt in symbole.items():
        (EIGENE / f"{name}.svg").write_text(inhalt, "utf-8")
    print(
        f"{len(symbole)} Symbole in {EIGENE.relative_to(PIKTOGRAMME.parent)}/ geschrieben"
    )


if __name__ == "__main__":
    main()
