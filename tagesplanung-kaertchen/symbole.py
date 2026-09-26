#!/usr/bin/env python3
"""Erzeugt die eigenen Symbole in piktogramme/eigene/.

Ein Teil sind eigene Zeichnungen (Tageszeiten, Dosett, Kochfeld, Wecker,
Tram, Rechnung, Wochenende), der Rest setzt Mulberry-Symbole neu zusammen,
z. B. Teller + Sonne = Mittagessen. Die fertigen SVG-Dateien liegen bereits
im Ordner; das Skript braucht man nur, wenn man sie ändern will.

Aufruf:
    python3 symbole.py

Gezeichnet wird im Stil der Mulberry Symbols: Zeichenfläche 850 × 850,
Konturen #231f20 in 21 bzw. 14 Einheiten Strichstärke, flache Farben.
"""

import math
import random
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


def dosett():
    tage = ("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    teile = [
        f'<rect x="60" y="250" width="730" height="420" rx="34" fill="{HELLBLAU}" '
        f'stroke="{LINIE}" stroke-width="{DICK}"/>'
    ]
    b, h, luecke = 86, 104, 12
    x0, y0 = 60 + (730 - (7 * b + 6 * luecke)) / 2, 250 + (420 - (3 * h + 2 * 18)) / 2
    for s, tag in enumerate(tage):
        x = x0 + s * (b + luecke)
        teile.append(
            f'<text x="{x + b / 2:.1f}" y="222" text-anchor="middle" font-family="{SCHRIFT}" '
            f'font-weight="700" font-size="46" fill="{LINIE}">{tag}</text>'
        )
        for z in range(3):
            y = y0 + z * (h + 18)
            teile.append(
                f'<rect x="{x:.1f}" y="{y:.1f}" width="{b}" height="{h}" rx="14" fill="#fff" '
                f'stroke="{LINIE}" stroke-width="{DUENN}"/>'
            )
            if s >= 2 and (s + z) % 3 != 1:  # Mo und Di sind schon genommen
                cx, cy = x + b / 2, y + h / 2
                if z == 1:
                    teile.append(
                        f'<rect x="{cx - 26:.1f}" y="{cy - 13:.1f}" width="52" height="26" rx="13" '
                        f'fill="{ROT}" stroke="{LINIE}" stroke-width="8"/>'
                    )
                else:
                    teile.append(
                        f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="19" fill="#faa41a" '
                        f'stroke="{LINIE}" stroke-width="8"/>'
                    )
    return svg("".join(teile))


def kochfeld():
    teile = [
        f'<rect x="85" y="170" width="680" height="510" rx="40" fill="#2b2b2b" '
        f'stroke="{LINIE}" stroke-width="{DICK}"/>'
    ]
    for cx, cy, r in ((270, 330, 110), (590, 300, 76), (590, 486, 92), (270, 530, 66)):
        teile.append(
            f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="{GRAU}" stroke-width="13"/>'
            f'<circle cx="{cx}" cy="{cy}" r="{r * 0.58:.0f}" fill="none" stroke="#797878" stroke-width="9"/>'
        )
    for k in range(7):
        teile.append(
            f'<rect x="{278 + k * 44}" y="616" width="28" height="22" rx="6" fill="#797878"/>'
        )
    return svg("".join(teile))


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


def tram():
    fenster = "".join(
        f'<rect x="{92 + k * 114}" y="370" width="94" height="104" rx="14" fill="{HELLBLAU}" '
        f'stroke="{LINIE}" stroke-width="{DUENN}"/>'
        for k in range(6)
    )
    raeder = "".join(
        f'<circle cx="{x}" cy="600" r="36" fill="{DUNKEL}" stroke="{LINIE}" stroke-width="{DUENN}"/>'
        for x in (185, 295, 555, 665)
    )
    koerper = 'x="55" y="330" width="740" height="262" rx="46"'
    return svg(
        f'<path d="M30 196H820" stroke="{LINIE}" stroke-width="12"/>'
        f'<path d="M378 330L452 268L392 208M338 204H502" fill="none" stroke="{LINIE}" '
        f'stroke-width="{DICK}" stroke-linecap="round" stroke-linejoin="round"/>'
        f'<defs><clipPath id="wagen"><rect {koerper}/></clipPath></defs>'
        f'<rect {koerper} fill="#fff"/><rect x="55" y="505" width="740" height="90" '
        f'fill="{BLAU}" clip-path="url(#wagen)"/>'
        f'<rect {koerper} fill="none" stroke="{LINIE}" stroke-width="{DICK}"/>{fenster}{raeder}'
        f'<path d="M30 646H820" stroke="{LINIE}" stroke-width="{DUENN}"/>'
    )


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


def rechnung():
    """Schweizer QR-Rechnung, vereinfacht (Zeichenfläche 500)."""
    random.seed(7)
    n, m, x0, y0 = 17, 7, 146, 282  # 17 × 17 Module à 7 Einheiten
    raster = [[random.random() < 0.5 for _ in range(n)] for _ in range(n)]
    for ox, oy in ((0, 0), (n - 7, 0), (0, n - 7)):  # Positionsmarken
        for i in range(-1, 8):
            for j in range(-1, 8):
                if 0 <= oy + i < n and 0 <= ox + j < n:
                    innen = 0 <= i < 7 and 0 <= j < 7
                    raster[oy + i][ox + j] = innen and (
                        i in (0, 6) or j in (0, 6) or (2 <= i <= 4 and 2 <= j <= 4)
                    )
    module = "".join(
        f'<rect x="{x0 + j * m}" y="{y0 + i * m}" width="{m}" height="{m}"/>'
        for i in range(n)
        for j in range(n)
        if raster[i][j]
    )
    cx, cy = x0 + n * m / 2, y0 + n * m / 2
    kreuz = (
        f'<rect x="{cx - 19}" y="{cy - 19}" width="38" height="38" fill="#fff"/>'
        f'<rect x="{cx - 15}" y="{cy - 15}" width="30" height="30" fill="{LINIE}"/>'
        f'<rect x="{cx - 3.5}" y="{cy - 10}" width="7" height="20" fill="#fff"/>'
        f'<rect x="{cx - 10}" y="{cy - 3.5}" width="20" height="7" fill="#fff"/>'
    )
    zeilen = "".join(
        f'<line x1="150" y1="{y}" x2="350" y2="{y}"/>' for y in (160, 192, 224)
    )
    return svg(
        f'<rect x="112" y="36" width="276" height="428" rx="12" fill="#fff" stroke="{LINIE}" stroke-width="12"/>'
        f'<g stroke="{LINIE}" stroke-width="9" stroke-linecap="round"><line x1="260" y1="84" x2="350" y2="84"/>'
        '<line x1="290" y1="112" x2="350" y2="112"/></g>'
        f'<g stroke="#868E96" stroke-width="9" stroke-linecap="round">{zeilen}</g>'
        f'<line x1="126" y1="258" x2="374" y2="258" stroke="{LINIE}" stroke-width="5" stroke-dasharray="10 9"/>'
        f'<g fill="{LINIE}">{module}</g>{kreuz}'
        f'<text x="324" y="338" text-anchor="middle" font-family="{SCHRIFT}" font-weight="700" '
        f'font-size="44" fill="{LINIE}">CHF</text>'
        f'<g stroke="{LINIE}" stroke-width="7" stroke-linecap="round"><line x1="284" y1="388" x2="364" y2="388"/>'
        '<line x1="284" y1="416" x2="344" y2="416"/></g>',
        groesse=500,
    )


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


# ---------------------------------------------------------------- Kombinationen aus Mulberry


def kombinationen():
    zeichen = (
        '<g transform="translate(418 372) rotate(23)"><rect x="-30" y="-165" width="60" height="205" '
        f'rx="30" fill="{ROT}"/><circle cx="0" cy="112" r="34" fill="{ROT}"/></g>'
    )
    gedanke = lade_mulberry("wrong_thought").read_text("utf-8")
    gedanke = re.sub(
        r'<path stroke-width="21"[^>]*d="M5[0-9.]+ [0-9.]+l[^"]*"/>', "", gedanke
    )
    gedanke = gedanke.replace(
        "</svg>", fragezeichen(598, 150, 150, strich=24) + "</svg>"
    )
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
        "wichtig": svg(
            mulberry("post-it", 40, 40, 770, 745, (70, 79, 785, 770)) + zeichen
        ),
        "mittagessen": svg(
            mulberry("dinner", 40, 310, 640, 450, (79, 179, 765, 661))
            + mulberry("sun", 555, 40, 265, 265, (88, 89, 762, 759))
        ),
        "abendessen": svg(
            mulberry("dinner", 40, 310, 640, 450, (79, 179, 765, 661))
            + mulberry("night", 540, 60, 280, 241, (88, 78, 760, 656))
        ),
        "freizeit": svg(
            mulberry("music", 40, 80, 390, 309, (105, 168, 746, 676))
            + mulberry("playing_cards", 455, 40, 355, 363, (66, 62, 780, 792))
            + mulberry("read_book_,_to", 280, 405, 290, 410, (166, 58, 683, 789))
        ),
        "vielleicht": svg(
            fragezeichen(300, 40, 500)
            + mulberry("correct", 50, 590, 330, 217, (94, 204, 762, 643), farbe=GRUEN)
            + mulberry(
                "mistake_no_wrong", 545, 565, 250, 241, (90, 111, 749, 746), farbe=ROT
            )
        ),
        "weiss_nicht": gedanke,
        "ja": svg(
            mulberry("correct", 60, 190, 730, 480, (94, 204, 762, 643), farbe=GRUEN)
        ),
        "nein": svg(
            mulberry(
                "mistake_no_wrong", 110, 110, 630, 607, (90, 111, 749, 746), farbe=ROT
            )
        ),
    }


def main():
    EIGENE.mkdir(parents=True, exist_ok=True)
    symbole = {
        **tageszeiten(),
        "dosett": dosett(),
        "kochfeld": kochfeld(),
        "wecker": wecker(),
        "tram": tram(),
        "einkaufszettel": einkaufszettel(),
        "rechnung": rechnung(),
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
