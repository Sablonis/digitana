# Tagesplanung mit Bildkarten

Ein Kartenset für die Wohnassistenz: Es enthält Fragen für die Tagesplanung in der Du-Form, jeweils mit
Piktogramm, etwa «Was willst du heute essen?», «Willst du dich hinlegen?» oder «Willst du am Standing stehen?».
Das Set ist für Menschen gedacht, die selbst lesen und mündlich antworten, auch wenn sie nicht zeigen
können, zum Beispiel im Rollstuhl. Immer dieselbe Karte für dieselbe Frage: So wird das Bild vertraut, und
die Frage wird mit der Zeit schneller erkannt.

Die Piktogramme stammen aus den **Mulberry Symbols**. Das ist ein freier Symbolsatz, der für Erwachsene
gestaltet wurde: erwachsene Figuren, schlichte Formen, keine Comic-Kinder.

## Druckvorlage

| Datei | Inhalt |
| --- | --- |
| `druckvorlagen/Tagesplanung-Kaertchen.pdf` | Druckfertig |
| `druckvorlagen/Tagesplanung-Kaertchen.html` | Zum Bearbeiten im Browser |

Das PDF hat 9 A4-Seiten: ein Deckblatt mit Anleitung und Beispiel sowie 8 Seiten mit je 9 Karten
(6,3 × 8,8 cm, Spielkartenformat). Die Fragen stehen gross auf der Karte, ohne Antwortfelder: Die Antwort
kommt mündlich. Alle Bilder sind Vektorgrafiken und werden in jeder Grösse scharf gedruckt.

- **54 Fragekarten** in 8 Bereichen, jeder mit eigener Farbe:
  - Befinden (6), zum Beispiel «Ist dir kalt oder warm?»
  - Essen & Trinken (8)
  - Körperpflege (7), zum Beispiel «Musst du aufs WC?»
  - Liegen, Sitzen, Stehen (9), zum Beispiel «Willst du in den Rollstuhl?», «Willst du anders liegen?»
  - Tag planen (7)
  - Freizeit & Kontakte (8)
  - Wohnung (5), zum Beispiel «Soll ich die Storen runterlassen?»
  - Abend & Nacht (4)
- **9 Zeitkarten** für das «Wann?»: Jetzt, Später, Heute, Morgen, Am Morgen, Am Mittag, Am Nachmittag,
  Am Abend, In der Nacht.
- **9 leere Karten** für eigene Fragen, eine pro Farbe.

## Drucken und Laminieren

1. Farbig auf A4 drucken, am besten auf festerem Papier (160–250 g/m²). Im Druckfenster
   «Tatsächliche Grösse» bzw. «100 %» wählen.
2. Entlang der gestrichelten Linien ausschneiden. Die Striche am Rand zeigen, wo geschnitten wird.
3. Die Karten mit etwa 5 mm Abstand auf eine A4-Laminierfolie legen, laminieren und mit 2–3 mm Rand
   ausschneiden.
4. Nach Farben sortiert in einer Box aufbewahren, oder in einer Ecke lochen und auf einen Kartenring ziehen.

## Im Alltag

- Die Karte auf Augenhöhe halten oder auf den Rollstuhltisch legen. Die Frage wird selbst gelesen und
  mündlich beantwortet. Ein Tischkartenhalter hält die Karte aufrecht.
- Geht es um das «Wann», eine Zeitkarte dazulegen: «Willst du dich hinlegen?» – «Jetzt» oder «Später»?
- Die Bilder folgen einem System: Kreispfeile heissen «anders» («Willst du anders sitzen?»), das
  Fragezeichen fragt nach («Sitzt du bequem?»).

## Anpassen

**Ohne Programmieren:** Die HTML-Datei in Chrome, Edge oder Firefox öffnen. Man kann jeden Text
anklicken und ändern. Auf den leeren Karten lässt sich mit einem Klick auf das Bildfeld ein eigenes Bild
einfügen, etwa ein Foto der Werkstatt oder der Bezugsperson. Danach auf «Drucken» klicken und
«Tatsächliche Grösse / 100 %» wählen. Die Änderungen werden nicht gespeichert. Man kann aber
«Als PDF speichern» wählen.

**Dauerhaft:** Die Texte, Farben und Bilder stehen in `karten.json`. Nach dem Ändern

```sh
python3 build.py
```

ausführen. Das Skript erzeugt HTML und PDF neu. Es braucht nur Python 3; für das PDF wird Google Chrome
oder Chromium verwendet (Pfad notfalls mit `--chrome` oder der Umgebungsvariable `CHROME` angeben).

Ein anderes Bild trägt man als `"bild": "mulberry:<Name>"` ein. Die Namen findet man auf
[mulberrysymbols.org](https://mulberrysymbols.org), etwa `shower_1_,_to` oder `walk_dog_,_to`. Das Skript
lädt das Symbol automatisch herunter. Eigene Symbole liegen als `eigene:<Name>` in
`piktogramme/eigene/`. Sie werden mit `python3 symbole.py` erzeugt.

## Lizenz und Quellen

- **Piktogramme:** Mulberry Symbols, © 2018–2026 Steve Lee, <https://mulberrysymbols.org>, Lizenz
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/deed.de). Der Ordner `piktogramme/mulberry/`
  enthält unveränderte Kopien (Version 3.6.1).
- **Eigene Symbole** (`piktogramme/eigene/`): Einige Symbole sind aus Mulberry-Symbolen
  zusammengesetzt, etwa «Mittagessen» aus Teller und Sonne. Andere sind eigene Zeichnungen im gleichen
  Stil: Rollstuhl, Person im Rollstuhl, Standing (Stehtrainer), Thermometer, Tageszeiten, Wecker und
  Wochenende.
- **Schrift:** Atkinson Hyperlegible, Braille Institute of America, SIL Open Font License 1.1
  (`schrift/OFL.txt`).
- **Das Kartenset** steht ebenfalls unter [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/deed.de).
  Nutzen, Verändern und Weitergeben ist erlaubt, auch in Institutionen, wenn die Quellen genannt werden und
  die gleiche Lizenz gilt.
