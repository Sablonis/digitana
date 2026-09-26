# Tagesplanung mit Bildkarten

Ein Kartenset für die Wohnassistenz: Es enthält die wichtigsten Fragen für die Tagesplanung, jeweils mit
Piktogramm. Die Karten sind zum Ausdrucken, Laminieren und Wiederverwenden gedacht. Immer dieselbe Karte
für dieselbe Frage: So wird das Bild vertraut, und die Frage wird mit der Zeit schneller erkannt.

## Druckvorlagen

| Datei | Inhalt |
| --- | --- |
| `druckvorlagen/Tagesplanung-Kaertchen_Du.pdf` | Druckfertig, per Du |
| `druckvorlagen/Tagesplanung-Kaertchen_Sie.pdf` | Druckfertig, per Sie |
| `druckvorlagen/Tagesplanung-Kaertchen_Du.html` / `_Sie.html` | Zum Bearbeiten im Browser |

Jede PDF-Datei hat 10 A4-Seiten: ein Deckblatt mit Anleitung und 9 Seiten mit je 9 Karten
(6,3 × 8,8 cm, Spielkartenformat).

- **54 Fragekarten** in 9 Bereichen: Befinden, Tag planen, Körperpflege, Essen & Trinken, Haushalt,
  Einkaufen & Geld, Gesundheit, Freizeit & Kontakte sowie Abend & Nacht. Jeder Bereich hat eine eigene
  Farbe.
- **Antwortfelder** unten auf jeder Fragekarte: Ja/Nein, drei Gesichter, Uhrzeit, Schreiblinie,
  Wochentage, Verkehrsmittel, Wetter oder Geldbetrag. Man füllt sie mit einem abwischbaren Stift aus.
- **9 Antwortkarten**: Ja, Nein, Vielleicht, Ich weiss es nicht, Später, Fertig, Ich brauche Hilfe,
  Ich brauche eine Pause, Ich verstehe das nicht.
- **9 Zeitkarten**: Am Morgen, Am Mittag, Am Nachmittag, Am Abend, In der Nacht, Heute, Morgen, Zuerst,
  Dann.
- **9 leere Karten** für eigene Fragen, eine pro Bereich.

## Drucken und Laminieren

1. Farbig auf A4 drucken, am besten auf festerem Papier (160–250 g/m²). Im Druckfenster
   «Tatsächliche Grösse» bzw. «100 %» wählen.
2. Entlang der gestrichelten Linien ausschneiden. Die Striche am Rand zeigen, wo geschnitten wird.
3. Die Karten mit etwa 5 mm Abstand auf eine A4-Laminierfolie legen, laminieren und mit 2–3 mm Rand
   ausschneiden.
4. Antworten mit einem abwischbaren Folienstift oder Whiteboard-Marker eintragen und später mit einem
   feuchten Tuch wieder abwischen.

## Anpassen

**Ohne Programmieren:** Eine HTML-Datei in Chrome, Edge oder Firefox öffnen. Man kann jeden Text
anklicken und ändern. Auf den leeren Karten lässt sich mit einem Klick auf das Bildfeld ein eigenes Bild
einfügen, etwa ein Foto der Werkstatt oder der Bezugsperson. Danach auf «Drucken» klicken und
«Tatsächliche Grösse / 100 %» wählen. Die Änderungen werden nicht gespeichert. Man kann aber
«Als PDF speichern» wählen.

**Dauerhaft:** Die Texte, Farben und Bilder stehen in `karten.json`. Nach dem Ändern

```sh
python3 build.py
```

ausführen. Das Skript erzeugt HTML und PDF für beide Versionen neu. Es braucht nur Python 3; für die PDFs
wird Google Chrome oder Chromium verwendet (Pfad notfalls mit `--chrome` oder der
Umgebungsvariable `CHROME` angeben). Neue ARASAAC-Bilder
trägt man als `"bild": "arasaac:<Nummer>"` ein. Die Nummer findet man auf [arasaac.org](https://arasaac.org)
bei jedem Piktogramm. Das Skript lädt die Datei automatisch herunter.

## Lizenz und Quellen

- **Piktogramme:** Die verwendeten piktografischen Symbole sind Eigentum der Regierung von Aragón und
  wurden von Sergio Palao für ARASAAC (<https://arasaac.org>) erstellt, die sie unter der
  Creative-Commons-Lizenz BY-NC-SA 4.0 verbreitet. Der Ordner `piktogramme/arasaac/` enthält
  unveränderte Kopien.
- **Eigene Symbole** (`piktogramme/eigene/`): Tageszeiten, Wetter, Rechnung, Wochenende.
- **Schrift:** Atkinson Hyperlegible, Braille Institute of America, SIL Open Font License 1.1
  (`schrift/OFL.txt`).
- **Das Kartenset** steht wegen der Piktogramme ebenfalls unter
  [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.de). Verändern und Weitergeben
  ist erlaubt, wenn die Quellen genannt werden und die gleiche Lizenz gilt. Kommerzielle Nutzung, etwa ein
  Verkauf, ist nicht erlaubt.
