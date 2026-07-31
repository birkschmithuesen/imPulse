# Schärferes Panning für KlangNetz-Ambisonics — Optionen für die nächste Session

**Kontext (2026-07-31, Birk vor Ort):** Nach dem Ambisonics-Setup-Fix
(ASIO-Device, Kanal-Permutation, `~directHardness=1`) funktioniert die
Richtungszuordnung korrekt — alle vier Testpositionen (vorn/rechts/hinten/
links) kommen hörbar überwiegend aus der richtigen Box. Birks Bewertung:
"spezialisiert, aber müsste wesentlich extremer/stärker sein — wenn eine
Note nah an einem Lautsprecher ist, will ich, dass es wirklich NUR aus
diesem Lautsprecher kommt."

## Warum es aktuell nicht 100% scharf ist (keine Einstellung, sondern Physik)

Per NRT-Messung auf dem Hermes-vServer objektiv bestätigt: selbst bei
`direct = 1` (volle Richtwirkung, keine Diffusität mehr) verteilt sich die
Energie einer Quelle direkt AN einer Lautsprecherposition auf alle vier
Decoder-Kanäle:

```
Position "rechts" (7, 0), direct=1, azimuthOffset=-0.25:
RMS pro Decoder-Kanal: [2496, 4192, 2239, 810]
Anteil dominanter Kanal: 43.1% der Gesamtenergie
```

Das ist eine **mathematische Eigenschaft von Ambisonics 1. Ordnung** (nur
W/X/Y im B-Format, `PanB2`/`DecodeB2`): die räumliche Auflösung ist mit nur
3 Kanälen inhärent grob. Ein 4-Lautsprecher-Decoder erster Ordnung kann
eine Punktquelle grundsätzlich nicht schärfer abbilden als das — das ist
kein Bug in unserem Code, sondern die Grenze des Verfahrens.

## Optionen, geordnet nach Aufwand

### Option A: Higher-Order Ambisonics (2. oder 3. Ordnung)
- 2. Ordnung braucht 5 B-Format-Kanäle (W, X, Y, U, V) statt 3, 3. Ordnung
  7 Kanäle. SuperCollider hat dafür `PanB2`-Äquivalente nicht direkt im
  Core — nötig wäre entweder das **ATK (Ambisonic Toolkit) Quark**
  (`Quarks.install("ATK")`, dann `FoaEncode`/`FoaDecode` bzw. `HoaEncode`
  für höhere Ordnungen) oder eine selbstgeschriebene Encoder/Decoder-Matrix.
- Vorteil: schärfere Richtwirkung, bleibt aber weiterhin ein "weiches"
  Verfahren (kein hartes Umschalten zwischen Boxen) — bei nur 4 Lautsprechern
  ist der Ordnungsgewinn ohnehin gedeckelt (die Faustregel ist: sinnvolle
  Ordnung ≈ Anzahl Lautsprecher / 2 − 1, bei 4 Boxen also praktisch schon
  am Limit mit Ordnung 1).
- Aufwand: mittel-hoch. Quark-Installation auf dem Windows-Laptop (Internet
  nötig für `Quarks.install`, oder manuell per Git-Klon ins Extensions-
  Verzeichnis), neue Encoder/Decoder-SynthDefs, komplette Neu-Messung von
  `azimuthOffset`/Kanal-Permutation (andere Matrix-Konvention).
- **Erwartung: spürbar schärfer, aber vermutlich immer noch nicht 100%
  "nur eine Box" bei 4 Lautsprechern.**

### Option B: Direktes Amplitude-Panning statt Ambisonics (empfohlen zum Ausprobieren)
Für eine feste 4-Lautsprecher-Anordnung auf den Seitenmitten ist
Ambisonics eigentlich der falsche Hammer — es lohnt sich als Verfahren vor
allem, wenn man später mehr/andere Lautsprecher-Layouts unterstützen will
(software-seitig neu decodieren, ohne den Encoder anzufassen). Wenn das
kein Ziel ist, ist direktes Panning einfacher UND schärfer:

- **Nächster-Nachbar-Pärchen-Panning (2 aktive Boxen statt 4):** für jede
  Position wird berechnet, zwischen welchen ZWEI benachbarten Boxen sie
  liegt (z.B. zwischen vorn und rechts), und nur auf diese zwei wird
  klassisches Equal-Power-Panning angewendet (`sin`/`cos`-Kurve oder
  einfach linear mit Wurzel-Gesetz). Die anderen zwei Boxen bleiben bei
  einer Punktquelle exakt bei 0 — kein Ambisonics-Leck in den 3./4. Kanal.
  Bei einer Position GENAU auf einer Box (wie unseren 4 Testpunkten) kommt
  dann tatsächlich zu >95% nur aus dieser einen Box.
  ```
  // Pseudocode-Skizze:
  // 1. azimuth wie bisher berechnen (0=vorn, 0.25=rechts usw., in Halbzyklen)
  // 2. sektor = azimuth durch 0.25 teilen, floor -> welches Boxpaar (0-1, 1-2, 2-3, 3-0)
  // 3. frac = Rest innerhalb des Sektors (0..1)
  // 4. gainA = cos(frac * pi/2), gainB = sin(frac * pi/2)  // Equal-Power
  // 5. die anderen zwei Boxen bekommen 0
  ```
  Für den "Mitte des Netzes = diffus"-Charakter (aktuell über `direct` bei
  Ambisonics gelöst) bräuchte es einen separaten Crossfade: bei kleinem
  Radius zusätzlich in Richtung "alle vier gleich laut" blenden (analog zu
  `~directHardness`, nur als Radius-abhängiger Mix zwischen
  Pärchen-Panning und Gleichverteilung statt Ambisonics-`direct`).
- Aufwand: gering-mittel. Kein neues Quark, reine SC-Rechnung in
  `~toBformat` (bzw. dessen Ersatz), Kanal-Permutation/Offset-Messung
  entfällt sogar (die Boxreihenfolge wird direkt in die Sektor-Zuordnung
  eingerechnet statt über einen Decoder-Trick).
- **Erwartung: das ist der Weg, der "wirklich nur aus dieser einen Box"
  am ehesten erreicht — Ambisonics ist konzeptionell ein Diffusions-
  verfahren, direktes Panning ein Fokussierungsverfahren.**

### Option C: Hybrid — Ambisonics behalten, aber Decoder-Ausgang nachschärfen
Nach dem bestehenden `DecodeB2` einen einfachen Kanal-Kompressor/Gate
einbauen, der leise Kanäle relativ zum lautesten Kanal noch weiter
dämpft (z.B. `sig * (sig/loudest).pow(k)` für k > 1, ein "spatial
sharpening" wie es manche Ambisonics-Decoder als Option anbieten,
teils "NFC-HOA sharpening" oder simple Schwellwert-Subtraktion genannt).
- Vorteil: kein Neubau des Encoders, additive Änderung in `masterReverb`.
- Nachteil: künstlicher Klangeffekt (kann pumpig/hart klingen bei
  Übertreibung), keine "saubere" Lösung, eher ein Kompromiss.
- Aufwand: gering. Guter erster Test, bevor man sich für A oder B entscheidet.

## Empfehlung für den Einstieg in die nächste Session

1. **Zuerst Option C ausprobieren** (schneller Test, < 30 Min Umsetzung),
   um zu sehen wie viel Schärfung überhaupt gewünscht/erträglich ist, ohne
   gleich das ganze Encoder/Decoder-Konzept umzubauen.
2. **Wenn C nicht reicht:** Option B (direktes Pärchen-Panning) bauen —
   das ist der Ansatz, der am ehesten "nur eine Box" liefert, bei
   überschaubarem Aufwand, weil unser Setup exakt 4 Boxen auf festen
   Positionen hat (kein generisches N-Lautsprecher-Ambisonics-Bedürfnis).
3. **Higher-Order Ambisonics (A) nur, wenn** später wirklich mehr/variable
   Lautsprecher-Setups geplant sind — für die aktuelle feste 4-Box-
   Konfiguration ist der Zusatzaufwand (Quark, neue Matrix-Messung) im
   Verhältnis zum Ergebnis vermutlich nicht gerechtfertigt.

## Aktueller Stand (Referenz für Anschlussarbeit)

- `~toBformat` in `supercollider/klangnetz_bells.scd` ist die Stelle, an
  der Encoder-Logik sitzt (aktuell `PanB2` + radius-abhängiger `direct`
  via `~directHardness`).
- `\masterReverb` SynthDef enthält die Hardware-Kanal-Permutation
  (`permuted = [sig[1], sig[3], sig[2], sig[0]]`) — bei Umbau auf Option B
  entfällt dieser Schritt vermutlich, weil die Boxreihenfolge dann direkt
  in der neuen Panning-Funktion berücksichtigt wird.
- Gemessene Werte (aktuell gültig, Show-Rechner ZOOM AMS-24):
  `azimuthSign=1`, `azimuthOffset=-0.25`, Hardware-Kabel-Reihenfolge
  hw0=rechts, hw1=links, hw2=hinten, hw3=vorn.
- Test-Infrastruktur bleibt nützlich für jede Option: `/klangnetz/test/noise`
  und `/klangnetz/test/noiseHard` (OSC, Port 8002) sowie die
  NRT-Analyse-Methode auf dem Hermes-vServer (kein Audiogerät nötig,
  `Score.recordNRT` + RMS-Auswertung der WAV-Kanäle per Python/numpy) für
  objektive Vorab-Messung, bevor am echten Gerät gehört wird.
