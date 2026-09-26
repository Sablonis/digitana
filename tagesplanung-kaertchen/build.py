#!/usr/bin/env python3
"""Tagesplanungs-Kärtchen: erzeugt die Druckvorlagen als HTML und PDF.

Aufruf:
    python3 build.py              HTML und PDF (Du- und Sie-Version)
    python3 build.py --nur-html   nur die HTML-Dateien

Braucht nur Python 3 ohne Zusatzpakete. Für die PDFs wird Google Chrome
oder Chromium verwendet; den Pfad kann man mit --chrome oder der
Umgebungsvariable CHROME angeben. Fehlende ARASAAC-Piktogramme werden
automatisch von static.arasaac.org geladen.
"""

import argparse
import base64
import html
import json
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

HIER = Path(__file__).resolve().parent
PIKTOGRAMME = HIER / "piktogramme"
AUSGABE = HIER / "druckvorlagen"
TITEL = "Tagesplanung mit Bildkarten"
LIZENZ_KURZ = (
    "Piktogramme: Sergio Palao · Herkunft: ARASAAC (arasaac.org) · "
    "Lizenz: CC BY-NC-SA 4.0 · Eigentum: Regierung von Aragón (Spanien)"
)
LIZENZ_LANG = (
    "Die verwendeten piktografischen Symbole sind Eigentum der Regierung von Aragón und wurden von "
    "Sergio Palao für ARASAAC (https://arasaac.org) erstellt, die sie unter der Creative-Commons-Lizenz "
    "BY-NC-SA 4.0 verbreitet."
)


# ---------------------------------------------------------------- Hilfen


def esc(text):
    return html.escape(text, quote=True)


def mische(farbe, anteil):
    """Mischt eine Hex-Farbe mit Weiss (anteil = Anteil der Farbe, 0..1)."""
    r, g, b = (int(farbe[i : i + 2], 16) for i in (1, 3, 5))
    r, g, b = (round(255 - (255 - c) * anteil) for c in (r, g, b))
    return f"#{r:02X}{g:02X}{b:02X}"


def b64(daten):
    return base64.b64encode(daten).decode("ascii")


def lade_arasaac(nummer):
    ziel = PIKTOGRAMME / "arasaac" / f"{nummer}.png"
    if not ziel.exists():
        ziel.parent.mkdir(parents=True, exist_ok=True)
        url = f"https://static.arasaac.org/pictograms/{nummer}/{nummer}_500.png"
        print(f"  lade Piktogramm {nummer} …")
        anfrage = urllib.request.Request(
            url, headers={"User-Agent": "tagesplanung-kaertchen"}
        )
        with urllib.request.urlopen(anfrage, timeout=30) as antwort:
            ziel.write_bytes(antwort.read())
    return ziel


class Bilder:
    """Wandelt Bild-Verweise ('arasaac:123', 'eigene:name') in data-URIs um."""

    def __init__(self, schrift_css):
        self.schrift_css = schrift_css
        self.cache = {}

    def __call__(self, verweis):
        if verweis not in self.cache:
            quelle, name = verweis.split(":", 1)
            if quelle == "arasaac":
                daten = lade_arasaac(name).read_bytes()
                self.cache[verweis] = "data:image/png;base64," + b64(daten)
            elif quelle == "eigene":
                svg = (PIKTOGRAMME / "eigene" / f"{name}.svg").read_text("utf-8")
                if "<text" in svg:  # Schrift einbetten, damit SVG-Texte gleich aussehen
                    svg = svg.replace(">", f"><style>{self.schrift_css}</style>", 1)
                self.cache[verweis] = "data:image/svg+xml;base64," + b64(
                    svg.encode("utf-8")
                )
            else:
                raise ValueError(f"Unbekannte Bildquelle: {verweis}")
        return self.cache[verweis]


# ---------------------------------------------------------------- Symbole (inline SVG)

HAKEN = (
    '<svg class="ico" viewBox="0 0 24 24"><path d="M4.5 12.5 10 18 19.5 6.5" fill="none" '
    'stroke="#2B8A3E" stroke-width="3.6" stroke-linecap="round" stroke-linejoin="round"/></svg>'
)
KREUZ = (
    '<svg class="ico" viewBox="0 0 24 24"><path d="M6.5 6.5 17.5 17.5M17.5 6.5 6.5 17.5" '
    'stroke="#E03131" stroke-width="3.6" stroke-linecap="round"/></svg>'
)
UHR = (
    '<svg class="ico uhr" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9.5" fill="#fff" '
    'stroke="#212529" stroke-width="2"/><path d="M12 6.5V12l3.8 2.4" fill="none" stroke="#212529" '
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>'
)
STIFT = (
    '<svg class="ico stift" viewBox="0 0 24 24"><path d="M4 20l1.2-4.6L15.8 4.8a2 2 0 0 1 2.8 0'
    'l.6.6a2 2 0 0 1 0 2.8L8.6 18.8z" fill="#fff" stroke="#495057" stroke-width="1.8" '
    'stroke-linejoin="round"/><path d="M14 6.6l3.4 3.4" stroke="#495057" stroke-width="1.8"/></svg>'
)


def gesicht(stimmung):
    farben = {"gut": "#8CE99A", "mittel": "#FFE066", "schlecht": "#FFA8A8"}
    mund = {
        "gut": "M12.5 24.5Q20 31.5 27.5 24.5",
        "mittel": "M13 26.5H27",
        "schlecht": "M12.5 29Q20 22 27.5 29",
    }
    return (
        f'<svg class="gesicht" viewBox="0 0 40 40"><circle cx="20" cy="20" r="17.5" '
        f'fill="{farben[stimmung]}" stroke="#212529" stroke-width="2.6"/>'
        '<circle cx="14" cy="16" r="2.5" fill="#212529"/><circle cx="26" cy="16" r="2.5" fill="#212529"/>'
        f'<path d="{mund[stimmung]}" fill="none" stroke="#212529" stroke-width="2.6" '
        'stroke-linecap="round"/></svg>'
    )


# ---------------------------------------------------------------- Karten-Bausteine


def antwortfeld(art, daten, bild):
    if art == "linie":
        return (
            f'<div class="af af-linie">{STIFT}<span class="schreiblinie"></span></div>'
        )
    if art == "uhrzeit":
        return (
            f'<div class="af">{UHR}<span class="feld"></span><span class="dp">:</span>'
            '<span class="feld"></span><span>Uhr</span></div>'
        )
    if art == "janein":
        return (
            f'<div class="af"><span class="wahl">{HAKEN}Ja</span>'
            f'<span class="wahl">{KREUZ}Nein</span></div>'
        )
    if art == "gesichter":
        return (
            '<div class="af af-gesichter">'
            + "".join(gesicht(s) for s in ("gut", "mittel", "schlecht"))
            + "</div>"
        )
    if art == "wochentage":
        tage = "".join(
            f'<span class="wt{" we" if t in ("Sa", "So") else ""}">{t}</span>'
            for t in ("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        )
        return f'<div class="af af-wochentage">{tage}</div>'
    if art in ("wetter", "unterwegs"):
        symbole = "".join(
            f'<span class="sym"><img src="{bild(v)}" alt=""></span>'
            for v in daten["symbole"][art]
        )
        return f'<div class="af af-symbole">{symbole}</div>'
    if art == "geld":
        return '<div class="af"><span>Fr.</span><span class="feld lang"></span></div>'
    raise ValueError(f"Unbekanntes Antwortfeld: {art}")


def farben_css(kat):
    farbe = kat["farbe"]
    return (
        f"--c:{farbe};--t:{kat.get('schrift', '#fff')};"
        f"--tint:{mische(farbe, 0.13)};--hell:{mische(farbe, 0.06)}"
    )


def karte_frage(karte, nr, kat, version, daten, bild):
    text = karte[version]
    return (
        f'<div class="karte frage-karte" style="{farben_css(kat)}">'
        f'<div class="band"><span>{esc(kat["name"])}</span><span class="nr">{nr}</span></div>'
        f'<div class="bild"><img src="{bild(karte["bild"])}" alt=""></div>'
        f'<div class="frage fit" contenteditable="true" spellcheck="false">{esc(text)}</div>'
        f"{antwortfeld(karte['antwort'], daten, bild)}</div>"
    )


def karte_gross(karte, nr, kat, bild):
    zusatz = (
        f'<div class="zusatz">{esc(karte["zusatz"])}</div>'
        if karte.get("zusatz")
        else ""
    )
    return (
        f'<div class="karte gross-karte" style="{farben_css(kat)}">'
        f'<div class="band"><span>{esc(kat["name"])}</span><span class="nr">{nr}</span></div>'
        f'<div class="bild"><img src="{bild(karte["bild"])}" alt=""></div>'
        f'<div class="gross-text"><div class="text fit" contenteditable="true" spellcheck="false">'
        f"{esc(karte['text'])}</div>{zusatz}</div></div>"
    )


def karte_leer(kat):
    return (
        f'<div class="karte frage-karte leer-karte" style="{farben_css(kat)}">'
        f'<div class="band"><span>{esc(kat["name"])}</span><span class="nr"></span></div>'
        '<label class="bild platzhalter"><input type="file" accept="image/*">'
        '<span class="hinweis">Bild aufkleben<br>oder zeichnen<span class="nur-bildschirm"><br>– oder hier klicken</span></span></label>'
        '<div class="frage fit leer" contenteditable="true" spellcheck="false"></div>'
        f'<div class="af af-linie">{STIFT}<span class="schreiblinie"></span></div></div>'
    )


# ---------------------------------------------------------------- Seiten

X_SCHNITTE = (10.5, 73.5, 136.5, 199.5)
Y_SCHNITTE = (16.5, 104.5, 192.5, 280.5)


def schnittmarken():
    linien = []
    for x in X_SCHNITTE:
        linien.append(
            f'<line class="gestrichelt" x1="{x}" y1="16.5" x2="{x}" y2="280.5"/>'
        )
        linien.append(f'<line class="marke" x1="{x}" y1="10" x2="{x}" y2="15"/>')
        linien.append(f'<line class="marke" x1="{x}" y1="282" x2="{x}" y2="287"/>')
    for y in Y_SCHNITTE:
        linien.append(
            f'<line class="gestrichelt" x1="10.5" y1="{y}" x2="199.5" y2="{y}"/>'
        )
        linien.append(f'<line class="marke" x1="4" y1="{y}" x2="9" y2="{y}"/>')
        linien.append(f'<line class="marke" x1="201" y1="{y}" x2="206" y2="{y}"/>')
    return f'<svg class="schnitt" viewBox="0 0 210 297">{"".join(linien)}</svg>'


def kartenseite(karten, ueberschrift, seite, seiten, version_name):
    zellen = "".join(f'<div class="zelle">{k}</div>' for k in karten)
    zellen += '<div class="zelle"></div>' * (9 - len(karten))
    return (
        f'<section class="page">'
        f'<div class="kopf"><span>{TITEL} · {esc(ueberschrift)} · {version_name}</span>'
        f"<span>Seite {seite} von {seiten}</span></div>"
        f'<div class="raster">{zellen}</div>{schnittmarken()}'
        f'<div class="fuss">{LIZENZ_KURZ}</div></section>'
    )


def deckblatt(daten, kategorien, version, version_name, bild):
    fragen = daten["fragekarten"]
    anzahl_bereiche = sum(
        1 for k in kategorien.values() if k["id"] not in ("antwort", "zeit")
    )
    beispiel = (
        "Gehst du heute ins Atelier?"
        if version == "du"
        else "Gehen Sie heute ins Atelier?"
    )
    bereiche = "".join(
        f'<li><span class="chip" style="background:{k["farbe"]}"></span>{esc(k["name"])}'
        f'<span class="anzahl">{sum(1 for f in fragen if f["kat"] == k["id"])}</span></li>'
        for k in kategorien.values()
        if k["id"] not in ("antwort", "zeit")
    )
    muster_stil = farben_css(kategorien["tag"])
    felder = [
        ("janein", "Ja oder Nein ankreuzen"),
        ("gesichter", "Gefühl zeigen oder einkreisen"),
        ("uhrzeit", "Uhrzeit eintragen"),
        ("linie", "Kurze Antwort schreiben"),
        ("wochentage", "Wochentag einkreisen"),
        ("unterwegs", "Bild einkreisen (auch Wetter)"),
        ("geld", "Betrag eintragen"),
    ]
    legende = "".join(
        f'<div class="legende-zeile"><div class="muster" style="{muster_stil}">'
        f"{antwortfeld(art, daten, bild)}</div><span>{text}</span></div>"
        for art, text in felder
    )
    streifen = "".join(
        f'<span style="background:{k["farbe"]}"></span>' for k in kategorien.values()
    )
    return f"""<section class="page deckblatt">
<div class="streifen">{streifen}</div>
<div class="innen">
  <p class="dachzeile">Bausatz für die Wohnassistenz · {version_name}</p>
  <h1>{TITEL}</h1>
  <p class="unterzeile">Die wichtigsten Fragen für die Tagesplanung – mit Piktogrammen, zum Ausdrucken,
  Laminieren und Wiederverwenden.</p>
  <div class="spalten">
    <div>
      <h2>Was ist drin?</h2>
      <ul class="inhalt">
        <li><b>{len(fragen)} Fragekarten</b> in {anzahl_bereiche} Bereichen</li>
        <li><b>{len(daten["antwortkarten"])} Antwortkarten</b>, z. B. Ja, Nein, Später</li>
        <li><b>{len(daten["zeitkarten"])} Zeitkarten</b>, z. B. Am Morgen, Heute, Zuerst</li>
        <li><b>{anzahl_bereiche} leere Karten</b> für eigene Fragen</li>
      </ul>
      <h2>Die Bereiche</h2>
      <ul class="bereiche">{bereiche}</ul>
      <p class="klein">Jeder Bereich hat eine eigene Farbe. So findet man die Karten schnell, und die Farbe
      hilft zusätzlich beim Wiedererkennen.</p>
    </div>
    <div>
      <h2>Antwortfelder</h2>
      <p class="klein">Unten auf jeder Fragekarte. Mit abwischbarem Stift ausfüllen, später abwischen.</p>
      <div class="legende">{legende}</div>
    </div>
  </div>
  <h2>So entstehen die Karten</h2>
  <ol class="schritte">
    <li><b>Drucken.</b> Farbig auf A4, am besten auf festeres Papier (160–250 g/m²). Im Druckfenster
    «Tatsächliche Grösse» bzw. «100 %» wählen, damit die Karten 6,3 × 8,8 cm gross werden.</li>
    <li><b>Ausschneiden.</b> Entlang der gestrichelten Linien, am einfachsten mit einem Papierschneider.
    Die Striche am Rand zeigen, wo geschnitten wird.</li>
    <li><b>Laminieren.</b> Karten mit etwa 5 mm Abstand auf eine A4-Laminierfolie legen, laminieren und
    mit 2–3 mm Rand ausschneiden. Mit abgerundeten Ecken halten sie länger.</li>
    <li><b>Ausfüllen.</b> Antworten mit einem abwischbaren Folienstift oder Whiteboard-Marker eintragen.
    Mit einem feuchten Tuch wieder abwischen.</li>
  </ol>
  <h2>Tipps für den Alltag</h2>
  <ul class="tipps">
    <li>Immer dieselbe Karte für dieselbe Frage verwenden. So wird das Bild vertraut, und die Frage wird
    mit der Zeit schneller erkannt.</li>
    <li>Mit wenigen Karten anfangen und nach und nach ergänzen.</li>
    <li>Karten in der Reihenfolge des Tages hinlegen. Die Zeitkarten dienen als Überschriften.</li>
    <li>Antwortkarten helfen, wenn Sprechen schwerfällt: auf «Ja», «Nein» oder «Später» zeigen.</li>
    <li>Mit Klettpunkten auf der Rückseite halten die Karten an einer Pinnwand, einer Tür oder am Kühlschrank.</li>
    <li>Leere Karten für eigene Fragen nutzen, z. B. «{beispiel}», und ein Bild aufkleben oder zeichnen.</li>
  </ul>
  <p class="lizenz">{LIZENZ_LANG} Die Symbole für Tageszeiten, Wetter, Rechnung und Wochenende sind eigene
  Zeichnungen. Das Kartenset steht ebenfalls unter CC BY-NC-SA 4.0: Verändern und Weitergeben ist erlaubt, wenn die
  Quellen genannt werden und die gleiche Lizenz gilt. Kommerzielle Nutzung ist nicht erlaubt. Schrift: Atkinson
  Hyperlegible (Braille Institute, SIL Open Font License).</p>
</div>
</section>"""


# ---------------------------------------------------------------- Dokument


def schrift_css():
    teile = []
    for gewicht, datei in (
        (400, "AtkinsonHyperlegible-Regular.ttf"),
        (700, "AtkinsonHyperlegible-Bold.ttf"),
    ):
        daten = (HIER / "schrift" / datei).read_bytes()
        teile.append(
            "@font-face{font-family:'Atkinson Hyperlegible';font-style:normal;"
            f"font-weight:{gewicht};src:url(data:font/ttf;base64,{b64(daten)}) format('truetype')}}"
        )
    return "".join(teile)


CSS = """
@page { size: A4; margin: 0; }
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body { font-family: 'Atkinson Hyperlegible', Arial, Helvetica, sans-serif; color: #1F2328;
  -webkit-print-color-adjust: exact; print-color-adjust: exact; }
.page { width: 210mm; height: 297mm; position: relative; overflow: hidden; background: #fff;
  break-after: page; page-break-after: always; }
.page:last-of-type { break-after: auto; page-break-after: auto; }

.kopf { position: absolute; top: 6mm; left: 10.5mm; right: 10.5mm; display: flex;
  justify-content: space-between; font-size: 8pt; color: #6C757D; }
.fuss { position: absolute; bottom: 4.5mm; left: 10.5mm; right: 10.5mm; text-align: center;
  font-size: 6.5pt; color: #868E96; }
.raster { position: absolute; left: 10.5mm; top: 16.5mm; width: 189mm; height: 264mm; display: grid;
  grid-template-columns: repeat(3, 63mm); grid-template-rows: repeat(3, 88mm); }
.zelle { position: relative; }
.schnitt { position: absolute; left: 0; top: 0; width: 210mm; height: 297mm; pointer-events: none; }
.schnitt .gestrichelt { stroke: #B8BEC4; stroke-width: .2; stroke-dasharray: 1.4 1.1; }
.schnitt .marke { stroke: #495057; stroke-width: .25; }

.karte { position: absolute; inset: 2.5mm; border: 1.3mm solid var(--c); border-radius: 4mm;
  background: #fff; overflow: hidden; }
.band { height: 6mm; background: var(--c); color: var(--t); display: flex; align-items: center;
  justify-content: space-between; padding: 0 2.4mm; font-size: 6.8pt; font-weight: 700;
  letter-spacing: .06em; text-transform: uppercase; white-space: nowrap; }
.band .nr { letter-spacing: 0; opacity: .85; }
.bild { position: absolute; left: 50%; transform: translateX(-50%); top: 8.2mm; width: 40mm; height: 40mm; }
.bild img { width: 100%; height: 100%; object-fit: contain; display: block; }
.frage { position: absolute; left: 1.8mm; right: 1.8mm; top: 49.2mm; height: 16.2mm; display: flex;
  align-items: center; justify-content: center; text-align: center; font-weight: 700; font-size: 13.5pt;
  line-height: 1.12; outline: none; text-wrap: balance; }

.af { position: absolute; left: 2mm; right: 2mm; bottom: 2mm; height: 11.4mm; border-radius: 2.4mm;
  background: var(--tint); display: flex; align-items: center; justify-content: center; gap: 1.5mm;
  font-weight: 700; font-size: 9.5pt; color: #343A40; }
.af .ico { width: 5mm; height: 5mm; flex: none; }
.af .uhr { width: 5.6mm; height: 5.6mm; }
.af-linie { justify-content: flex-start; padding-left: 2.2mm; }
.af-linie .stift { width: 4.2mm; height: 4.2mm; align-self: flex-end; margin-bottom: 2.2mm; }
.schreiblinie { flex: 1; align-self: flex-end; margin: 0 2.8mm 2.6mm .6mm; border-bottom: .35mm solid #8A939B; }
.feld { width: 9mm; height: 6.4mm; border-bottom: .35mm solid #8A939B; }
.feld.lang { width: 31mm; }
.dp { margin: 0 -.6mm; }
.wahl { background: #fff; border: .3mm solid #CED4DA; border-radius: 1.8mm; height: 7.6mm;
  padding: 0 2.6mm 0 1.2mm; display: flex; align-items: center; gap: .8mm; }
.af-gesichter { gap: 4.5mm; }
.gesicht { width: 8.6mm; height: 8.6mm; }
.af-wochentage { gap: .7mm; }
.wt { background: #fff; border: .3mm solid #CED4DA; border-radius: 1.3mm; width: 6.5mm; height: 7mm;
  display: flex; align-items: center; justify-content: center; font-size: 7.6pt; }
.wt.we { color: #C92A2A; }
.af-symbole { gap: 1mm; }
.sym { background: #fff; border: .3mm solid #CED4DA; border-radius: 1.6mm; width: 8.8mm; height: 8.8mm;
  padding: .5mm; display: flex; }
.sym img { width: 100%; height: 100%; object-fit: contain; }

.gross-karte .bild { top: 9.5mm; width: 45mm; height: 45mm; }
.gross-text { position: absolute; left: 1.8mm; right: 1.8mm; top: 56.5mm; bottom: 2.5mm; display: flex;
  flex-direction: column; align-items: center; justify-content: center; text-align: center; }
.gross-text .text { font-weight: 700; font-size: 18pt; line-height: 1.1; max-height: 100%; outline: none;
  text-wrap: balance; }
.gross-text .zusatz { font-size: 9pt; color: #495057; margin-top: 1mm; }

.leer-karte .platzhalter { border: .4mm dashed #ADB5BD; border-radius: 3mm; background: var(--hell);
  display: flex; align-items: center; justify-content: center; overflow: hidden; cursor: pointer; }
.leer-karte .platzhalter input { display: none; }
.leer-karte .platzhalter .hinweis { font-size: 7pt; color: #ADB5BD; text-align: center; line-height: 1.3; }
.leer-karte .platzhalter.hat-bild { border-color: transparent; background: none; }
.leer-karte .platzhalter.hat-bild .hinweis { display: none; }
.frage.leer:empty { background:
  linear-gradient(#ADB5BD, #ADB5BD) left 3mm top 6mm / calc(100% - 6mm) .3mm no-repeat,
  linear-gradient(#ADB5BD, #ADB5BD) left 3mm top 12.5mm / calc(100% - 6mm) .3mm no-repeat; }

.deckblatt .streifen { display: flex; height: 7mm; }
.deckblatt .streifen span { flex: 1; }
.deckblatt .innen { padding: 9mm 16mm 0; font-size: 9.6pt; line-height: 1.36; }
.dachzeile { margin: 0; font-size: 9.5pt; font-weight: 700; color: #6C757D; letter-spacing: .05em;
  text-transform: uppercase; }
.deckblatt h1 { margin: 1.5mm 0 1.5mm; font-size: 27pt; line-height: 1.1; }
.unterzeile { margin: 0 0 4mm; font-size: 11.5pt; color: #343A40; }
.deckblatt h2 { margin: 4.5mm 0 1.8mm; font-size: 12pt; }
.spalten { display: grid; grid-template-columns: 1fr 1fr; gap: 9mm; }
.spalten h2:first-child { margin-top: 1mm; }
.inhalt, .tipps { margin: 0; padding-left: 5mm; }
.inhalt li, .tipps li { margin: .8mm 0; }
.bereiche { list-style: none; margin: 0; padding: 0; }
.bereiche li { display: flex; align-items: center; gap: 2.4mm; margin: 1mm 0; }
.bereiche .chip { width: 6mm; height: 4.2mm; border-radius: 1.2mm; flex: none; }
.bereiche .anzahl { margin-left: auto; color: #6C757D; }
.klein { font-size: 8.6pt; color: #495057; margin: 1.5mm 0; }
.legende-zeile { display: flex; align-items: center; gap: 3mm; margin: 1.6mm 0; font-size: 8.8pt; }
.muster { position: relative; width: 55.4mm; height: 13mm; flex: none; }
.muster .af { bottom: .8mm; }
.schritte { margin: 0; padding-left: 5mm; }
.schritte li { margin: 1mm 0; }
.lizenz { margin-top: 5mm; padding-top: 2.5mm; border-top: .3mm solid #DEE2E6; font-size: 7.4pt;
  color: #6C757D; line-height: 1.35; }

.werkzeug, .nur-bildschirm { display: none; }
@media screen {
  body { background: #DEE2E6; padding-top: 16mm; }
  .page { margin: 0 auto 8mm; box-shadow: 0 .8mm 4mm rgba(0,0,0,.2); }
  .nur-bildschirm { display: inline; }
  .werkzeug { display: flex; position: fixed; top: 0; left: 0; right: 0; z-index: 9; gap: 4mm;
    align-items: center; padding: 2.5mm 6mm; background: #1F2328; color: #fff; font-size: 10.5pt; }
  .werkzeug b { white-space: nowrap; }
  .werkzeug span { opacity: .85; }
  .werkzeug button { margin-left: auto; font: inherit; font-weight: 700; padding: 1.6mm 5mm;
    border: 0; border-radius: 1.6mm; background: #FAB005; color: #1F2328; cursor: pointer; }
  [contenteditable]:hover { box-shadow: 0 0 0 .4mm #74C0FC; border-radius: 1mm; }
  [contenteditable]:focus { box-shadow: 0 0 0 .5mm #1C7ED6; border-radius: 1mm; }
}
"""

JS = """
function passe(el) {
  if (!el.dataset.basis) el.dataset.basis = parseFloat(getComputedStyle(el).fontSize);
  var gr = parseFloat(el.dataset.basis), min = gr * 0.72;
  el.style.fontSize = gr + 'px';
  var box = el.classList.contains('text') ? el.parentElement : el;
  while (gr > min && (el.scrollHeight > box.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1)) {
    gr -= 0.5; el.style.fontSize = gr + 'px';
  }
}
function alle() { document.querySelectorAll('.fit').forEach(passe); }
document.addEventListener('input', function (e) {
  var el = e.target.closest ? e.target.closest('.fit') : null;
  if (!el) return;
  if (!el.textContent.trim()) el.innerHTML = '';
  passe(el);
});
document.addEventListener('change', function (e) {
  var inp = e.target;
  if (!inp.files || !inp.files[0]) return;
  var ziel = inp.closest('.platzhalter'), leser = new FileReader();
  leser.onload = function () {
    var img = ziel.querySelector('img') || document.createElement('img');
    img.src = leser.result; img.alt = '';
    ziel.appendChild(img); ziel.classList.add('hat-bild');
  };
  leser.readAsDataURL(inp.files[0]);
});
(document.fonts ? document.fonts.ready : Promise.resolve()).then(function () {
  alle(); document.documentElement.classList.add('bereit');
});
"""


def dokument(daten, version):
    version_name = "Du-Version" if version == "du" else "Sie-Version"
    kategorien = {k["id"]: k for k in daten["kategorien"]}
    fcss = schrift_css()
    bild = Bilder(fcss)

    fragen = [
        karte_frage(k, nr, kategorien[k["kat"]], version, daten, bild)
        for nr, k in enumerate(daten["fragekarten"], 1)
    ]
    antworten = [
        karte_gross(k, f"A{nr}", kategorien["antwort"], bild)
        for nr, k in enumerate(daten["antwortkarten"], 1)
    ]
    zeiten = [
        karte_gross(k, f"Z{nr}", kategorien["zeit"], bild)
        for nr, k in enumerate(daten["zeitkarten"], 1)
    ]
    leere = [
        karte_leer(k) for k in daten["kategorien"] if k["id"] not in ("antwort", "zeit")
    ]

    abschnitte = []
    for i in range(0, len(fragen), 9):
        abschnitte.append(("Fragekarten", fragen[i : i + 9]))
    for titel, liste in (
        ("Antwortkarten", antworten),
        ("Zeitkarten", zeiten),
        ("Leere Karten", leere),
    ):
        for i in range(0, len(liste), 9):
            abschnitte.append((titel, liste[i : i + 9]))
    seiten = len(abschnitte) + 1

    teile = [deckblatt(daten, kategorien, version, version_name, bild)]
    for nr, (titel, karten) in enumerate(abschnitte, 2):
        teile.append(kartenseite(karten, titel, nr, seiten, version_name))

    werkzeug = (
        '<div class="werkzeug"><b>' + TITEL + " · " + version_name + "</b>"
        "<span>Texte anklicken und ändern. Bei leeren Karten das Bildfeld anklicken, um ein eigenes "
        "Bild einzufügen. Drucken mit «Tatsächliche Grösse / 100 %».</span>"
        '<button onclick="window.print()">Drucken</button></div>'
    )
    return (
        f'<!DOCTYPE html>\n<html lang="de-CH"><head><meta charset="utf-8">'
        f'<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>{TITEL} ({version_name})</title>"
        f"<style>{fcss}{CSS}</style></head><body>{werkzeug}{''.join(teile)}"
        f"<script>{JS}</script></body></html>\n"
    )


# ---------------------------------------------------------------- PDF


def finde_chrome(wunsch):
    kandidaten = [
        wunsch,
        os.environ.get("CHROME"),
        "google-chrome",
        "google-chrome-stable",
        "chromium",
        "chromium-browser",
        "chrome",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    ]
    for k in kandidaten:
        if not k:
            continue
        pfad = shutil.which(k) or (k if Path(k).exists() else None)
        if pfad:
            return pfad
    return None


def drucke_pdf(chrome, html_datei, pdf_datei):
    befehl = [
        chrome,
        "--headless",
        "--disable-gpu",
        "--no-pdf-header-footer",
        "--run-all-compositor-stages-before-draw",
        "--virtual-time-budget=15000",
        f"--print-to-pdf={pdf_datei}",
        html_datei.resolve().as_uri(),
    ]
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        befehl.insert(1, "--no-sandbox")  # Chrome startet als root sonst nicht
    subprocess.run(
        befehl, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
    )


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--nur-html", action="store_true", help="keine PDFs erzeugen")
    parser.add_argument("--chrome", help="Pfad zu Chrome/Chromium für die PDFs")
    args = parser.parse_args()

    daten = json.loads((HIER / "karten.json").read_text("utf-8"))
    AUSGABE.mkdir(exist_ok=True)
    chrome = None if args.nur_html else finde_chrome(args.chrome)
    if not args.nur_html and not chrome:
        print(
            "Chrome/Chromium nicht gefunden – es werden nur HTML-Dateien erzeugt.",
            file=sys.stderr,
        )

    for version, name in (("du", "Du"), ("sie", "Sie")):
        html_datei = AUSGABE / f"Tagesplanung-Kaertchen_{name}.html"
        html_datei.write_text(dokument(daten, version), "utf-8")
        print(f"geschrieben: {html_datei.relative_to(HIER)}")
        if chrome:
            pdf_datei = AUSGABE / f"Tagesplanung-Kaertchen_{name}.pdf"
            drucke_pdf(chrome, html_datei, pdf_datei)
            print(f"geschrieben: {pdf_datei.relative_to(HIER)}")


if __name__ == "__main__":
    main()
