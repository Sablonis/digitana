#!/usr/bin/env python3
"""Tagesplanungs-Kärtchen: erzeugt die Druckvorlage als HTML und PDF.

Aufruf:
    python3 build.py              HTML und PDF
    python3 build.py --nur-html   nur die HTML-Datei

Braucht nur Python 3 ohne Zusatzpakete. Für das PDF wird Google Chrome
oder Chromium verwendet; den Pfad kann man mit --chrome oder der
Umgebungsvariable CHROME angeben. Fehlende Mulberry-Symbole werden
automatisch heruntergeladen.
"""

import argparse
import base64
import html
import json
import os
import shutil
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path

HIER = Path(__file__).resolve().parent
PIKTOGRAMME = HIER / "piktogramme"
AUSGABE = HIER / "druckvorlagen"
TITEL = "Tagesplanung mit Bildkarten"
MULBERRY_URL = (
    "https://cdn.jsdelivr.net/gh/mulberrysymbols/mulberry-symbols@3.6.1/EN/{}.svg"
)
LIZENZ_KURZ = (
    "Piktogramme: Mulberry Symbols von Steve Lee (mulberrysymbols.org), teils ergänzt · "
    "Lizenz: CC BY-SA 4.0"
)
LIZENZ_LANG = (
    "Die Piktogramme stammen aus den Mulberry Symbols von Steve Lee (https://mulberrysymbols.org), "
    "einem freien Symbolsatz für Erwachsene, Lizenz CC BY-SA 4.0. Einige Symbole sind aus "
    "Mulberry-Symbolen zusammengesetzt oder eigene Zeichnungen im gleichen Stil."
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


def lade_mulberry(name):
    """Pfad zu einem Mulberry-Symbol; lädt es beim ersten Mal herunter."""
    ziel = PIKTOGRAMME / "mulberry" / f"{name}.svg"
    if not ziel.exists():
        ziel.parent.mkdir(parents=True, exist_ok=True)
        url = MULBERRY_URL.format(urllib.parse.quote(name))
        print(f"  lade Mulberry-Symbol {name} …")
        anfrage = urllib.request.Request(
            url, headers={"User-Agent": "tagesplanung-kaertchen"}
        )
        with urllib.request.urlopen(anfrage, timeout=30) as antwort:
            ziel.write_bytes(antwort.read())
    return ziel


class Bilder:
    """Wandelt Bild-Verweise ('mulberry:name', 'eigene:name') in data-URIs um."""

    def __init__(self, schrift_css):
        self.schrift_css = schrift_css
        self.cache = {}

    def __call__(self, verweis):
        if verweis not in self.cache:
            quelle, name = verweis.split(":", 1)
            if quelle == "mulberry":
                svg = lade_mulberry(name).read_text("utf-8")
            elif quelle == "eigene":
                svg = (PIKTOGRAMME / "eigene" / f"{name}.svg").read_text("utf-8")
            else:
                raise ValueError(f"Unbekannte Bildquelle: {verweis}")
            if "<text" in svg:  # Schrift einbetten, damit SVG-Texte gleich aussehen
                stil = f"<style>{self.schrift_css}</style>"
                svg = re.sub(r"<svg\b[^>]*>", lambda m: m.group(0) + stil, svg, count=1)
            self.cache[verweis] = "data:image/svg+xml;base64," + b64(
                svg.encode("utf-8")
            )
        return self.cache[verweis]


# ---------------------------------------------------------------- Karten-Bausteine


def farben_css(kat):
    farbe = kat["farbe"]
    return (
        f"--c:{farbe};--t:{kat.get('schrift', '#fff')};"
        f"--tint:{mische(farbe, 0.13)};--hell:{mische(farbe, 0.06)}"
    )


def karte_frage(karte, nr, kat, bild):
    return (
        f'<div class="karte frage-karte" style="{farben_css(kat)}">'
        f'<div class="band"><span>{esc(kat["name"])}</span><span class="nr">{nr}</span></div>'
        f'<div class="bild"><img src="{bild(karte["bild"])}" alt=""></div>'
        f'<div class="frage fit" contenteditable="true" spellcheck="false">{esc(karte["text"])}</div>'
        "</div>"
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
        '<div class="frage fit leer" contenteditable="true" spellcheck="false"></div></div>'
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


def kartenseite(karten, ueberschrift, seite, seiten):
    zellen = "".join(f'<div class="zelle">{k}</div>' for k in karten)
    zellen += '<div class="zelle"></div>' * (9 - len(karten))
    return (
        f'<section class="page">'
        f'<div class="kopf"><span>{TITEL} · {esc(ueberschrift)}</span>'
        f"<span>Seite {seite} von {seiten}</span></div>"
        f'<div class="raster">{zellen}</div>{schnittmarken()}'
        f'<div class="fuss">{LIZENZ_KURZ}</div></section>'
    )


def beispiel(daten, kategorien, bild):
    """Frage- und Zeitkarten verkleinert nebeneinander, als Beispiel fürs Deckblatt."""
    fragen = {k["id"]: (nr, k) for nr, k in enumerate(daten["fragekarten"], 1)}
    zeiten = {k["id"]: (nr, k) for nr, k in enumerate(daten["zeitkarten"], 1)}
    if "hinlegen" not in fragen or not {"jetzt", "spaeter"} <= zeiten.keys():
        return ""
    nr, frage = fragen["hinlegen"]
    karten = [karte_frage(frage, nr, kategorien[frage["kat"]], bild)]
    for zid in ("jetzt", "spaeter"):
        nr, zeit = zeiten[zid]
        karten.append(karte_gross(zeit, f"Z{nr}", kategorien["zeit"], bild))
    mini = [f'<div class="mini"><div class="zelle">{k}</div></div>' for k in karten]
    return (
        '<h2>Beispiel</h2><div class="beispiel">'
        f'{mini[0]}<span class="zeichen">+</span>{mini[1]}<span class="zeichen oder">oder</span>{mini[2]}'
        '</div><p class="klein">Zuerst die Frage zeigen. Geht es um das «Wann», eine oder zwei Zeitkarten'
        " dazulegen. Die Antwort kommt mündlich.</p>"
    )


def deckblatt(daten, kategorien, bild):
    fragen = daten["fragekarten"]
    bereiche_liste = [k for k in kategorien.values() if k["id"] != "zeit"]
    bereiche = "".join(
        f'<li><span class="chip" style="background:{k["farbe"]}"></span>{esc(k["name"])}'
        f'<span class="anzahl">{sum(1 for f in fragen if f["kat"] == k["id"])}</span></li>'
        for k in bereiche_liste
    )
    streifen = "".join(
        f'<span style="background:{k["farbe"]}"></span>' for k in kategorien.values()
    )
    return f"""<section class="page deckblatt">
<div class="streifen">{streifen}</div>
<div class="innen">
  <p class="dachzeile">Bausatz für die Wohnassistenz</p>
  <h1>{TITEL}</h1>
  <p class="unterzeile">Fragen für den Tag – gross geschrieben und mit Piktogramm. Zum Ausdrucken, Laminieren
  und Wiederverwenden.</p>
  <div class="spalten">
    <div>
      <h2>Was ist drin?</h2>
      <ul class="inhalt">
        <li><b>{len(fragen)} Fragekarten</b> in {len(bereiche_liste)} Bereichen</li>
        <li><b>{len(daten["zeitkarten"])} Zeitkarten</b> für das «Wann?», z. B. Jetzt, Später, Am Abend</li>
        <li><b>{len(kategorien)} leere Karten</b> für eigene Fragen</li>
      </ul>
      <h2>Die Bereiche</h2>
      <ul class="bereiche">{bereiche}</ul>
      <p class="klein">Jeder Bereich hat eine eigene Farbe. So findet man die Karten schnell, und die Farbe
      hilft beim Wiedererkennen.</p>
    </div>
    <div>
      <h2>So werden die Karten benutzt</h2>
      <ul class="tipps">
        <li><b>Zeigen und lesen lassen.</b> Die Karte auf Augenhöhe halten oder auf den Rollstuhltisch legen.
        Die Frage wird selbst gelesen und mündlich beantwortet.</li>
        <li><b>Immer dieselbe Karte</b> für dieselbe Frage. So wird das Bild vertraut, und die Frage wird mit
        der Zeit schneller erkannt.</li>
        <li><b>Das «Wann» klären.</b> Zeitkarten dazulegen: «Willst du dich hinlegen?» – «Jetzt» oder
        «Später»?</li>
        <li><b>Bilder mit System.</b> Kreispfeile heissen «anders» («Willst du anders sitzen?»), das
        Fragezeichen fragt nach («Sitzt du bequem?»).</li>
        <li><b>Halter nutzen.</b> Ein Tischkartenhalter oder ein kleiner Bilderständer hält die Karte
        aufrecht.</li>
        <li><b>Eigene Fragen</b> auf die leeren Karten schreiben, z. B. «Willst du heute ins Atelier?», und ein
        Bild aufkleben oder zeichnen.</li>
      </ul>
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
    <li><b>Aufbewahren.</b> Nach Farben sortiert in einer Box, oder in einer Ecke lochen und auf einen
    Kartenring ziehen.</li>
  </ol>
  {beispiel(daten, kategorien, bild)}
  <p class="lizenz">{LIZENZ_LANG} Das Kartenset steht ebenfalls unter CC BY-SA 4.0: Nutzen, Verändern und
  Weitergeben ist erlaubt, wenn die Quellen genannt werden und die gleiche Lizenz gilt. Schrift: Atkinson Hyperlegible
  (Braille Institute, SIL Open Font License).</p>
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
.bild { position: absolute; left: 50%; transform: translateX(-50%); top: 8.4mm; width: 44mm; height: 44mm; }
.bild img { width: 100%; height: 100%; object-fit: contain; display: block; }
.frage { position: absolute; left: 1.8mm; right: 1.8mm; top: 54mm; bottom: 2.4mm; display: flex;
  align-items: center; justify-content: center; text-align: center; font-weight: 700; font-size: 16.5pt;
  line-height: 1.1; outline: none; text-wrap: balance; }

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
  linear-gradient(#ADB5BD, #ADB5BD) left 3mm top 8mm / calc(100% - 6mm) .3mm no-repeat,
  linear-gradient(#ADB5BD, #ADB5BD) left 3mm top 16mm / calc(100% - 6mm) .3mm no-repeat; }

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
.inhalt li, .tipps li { margin: .9mm 0; }
.bereiche { list-style: none; margin: 0; padding: 0; }
.bereiche li { display: flex; align-items: center; gap: 2.4mm; margin: 1mm 0; }
.bereiche .chip { width: 6mm; height: 4.2mm; border-radius: 1.2mm; flex: none; }
.bereiche .anzahl { margin-left: auto; color: #6C757D; }
.klein { font-size: 8.6pt; color: #495057; margin: 1.5mm 0; }
.schritte { margin: 0; padding-left: 5mm; }
.schritte li { margin: 1mm 0; }
.beispiel { display: flex; align-items: center; gap: 4mm; }
.beispiel .mini { width: 44.1mm; height: 61.6mm; flex: none; }
.beispiel .zelle { width: 63mm; height: 88mm; transform: scale(.7); transform-origin: 0 0; }
.beispiel .zeichen { font-size: 20pt; font-weight: 700; color: #495057; }
.beispiel .zeichen.oder { font-size: 11pt; }
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


def dokument(daten):
    kategorien = {k["id"]: k for k in daten["kategorien"]}
    fcss = schrift_css()
    bild = Bilder(fcss)

    fragen = [
        karte_frage(k, nr, kategorien[k["kat"]], bild)
        for nr, k in enumerate(daten["fragekarten"], 1)
    ]
    zeiten = [
        karte_gross(k, f"Z{nr}", kategorien["zeit"], bild)
        for nr, k in enumerate(daten["zeitkarten"], 1)
    ]
    leere = [karte_leer(k) for k in daten["kategorien"]]

    abschnitte = []
    for i in range(0, len(fragen), 9):
        abschnitte.append(("Fragekarten", fragen[i : i + 9]))
    for titel, liste in (("Zeitkarten", zeiten), ("Leere Karten", leere)):
        for i in range(0, len(liste), 9):
            abschnitte.append((titel, liste[i : i + 9]))
    seiten = len(abschnitte) + 1

    teile = [deckblatt(daten, kategorien, bild)]
    for nr, (titel, karten) in enumerate(abschnitte, 2):
        teile.append(kartenseite(karten, titel, nr, seiten))

    werkzeug = (
        '<div class="werkzeug"><b>' + TITEL + "</b>"
        "<span>Texte anklicken und ändern. Bei leeren Karten das Bildfeld anklicken, um ein eigenes "
        "Bild einzufügen. Drucken mit «Tatsächliche Grösse / 100 %».</span>"
        '<button onclick="window.print()">Drucken</button></div>'
    )
    return (
        f'<!DOCTYPE html>\n<html lang="de-CH"><head><meta charset="utf-8">'
        f'<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>{TITEL}</title>"
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
    parser.add_argument("--nur-html", action="store_true", help="kein PDF erzeugen")
    parser.add_argument("--chrome", help="Pfad zu Chrome/Chromium für das PDF")
    args = parser.parse_args()

    daten = json.loads((HIER / "karten.json").read_text("utf-8"))
    AUSGABE.mkdir(exist_ok=True)
    chrome = None if args.nur_html else finde_chrome(args.chrome)
    if not args.nur_html and not chrome:
        print(
            "Chrome/Chromium nicht gefunden – es wird nur die HTML-Datei erzeugt.",
            file=sys.stderr,
        )

    html_datei = AUSGABE / "Tagesplanung-Kaertchen.html"
    html_datei.write_text(dokument(daten), "utf-8")
    print(f"geschrieben: {html_datei.relative_to(HIER)}")
    if chrome:
        pdf_datei = AUSGABE / "Tagesplanung-Kaertchen.pdf"
        drucke_pdf(chrome, html_datei, pdf_datei)
        print(f"geschrieben: {pdf_datei.relative_to(HIER)}")


if __name__ == "__main__":
    main()
