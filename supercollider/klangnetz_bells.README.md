# supercollider/klangnetz_bells.scd

**Kanonische, einzige SC-Sound-Datei fürs KlangNetz** (seit 2026-07-31).

Läuft direkt aus diesem Repo-Pfad — der Windows-Task `KlangnetzBells` startet
`C:\Users\birk\Documents\imPulse\supercollider\klangnetz_bells.scd` (per
`git pull` aktuell gehalten), **keine lose Kopie mehr** im Home-Verzeichnis.

## Warum "kanonisch" extra betont wird

Bis 2026-07-31 gab es zwei parallele SC-Dateien mit unterschiedlichem Klang:

- `supercollider/klangnetz_bells.scd` (Repo) — pentatonische Skala, Stereo,
  Ambisonics-Vorarbeit (4-Kanal `PanB2`/`DecodeB2`, nie live gehört)
- `C:\Users\birk\klangnetz_bells_zoom.scd` (lose Windows-Kopie) — D-Kurd-Skala,
  4-Kanal via Zoom AMS-24, **das war die tatsächlich laufende Datei**

Das führte zu einer echten Verwechslung: eine Tonwert-Analyse der Repo-Datei
ergab falsche Ergebnisse, weil der Live-Task eine andere Datei lud. Ab jetzt
gibt es nur noch diese eine Datei — Repo-Stand und Live-Stand sind immer
identisch.

## Was drin ist

- **Skala:** D Kurd (klassische Hang-Drum/Handpan-Skala, natürliche Moll ab D)
- **Grundton:** A2 (MIDI 45) — historisch: D3 → D2 (eine Oktave tiefer,
  2026-07-30) → A2 (eine Quinte höher, +7 Halbtöne, 2026-07-31 vor Ort)
- **4-Kanal-Ausgang** über Zoom AMS-24 (ASIO), `outCh = nodeId % 4`
- **Lautstärke:** `~minAmp`/`~maxAmp`, historisch: -10dB (2026-07-30, gegen zu
  leise Treffer) → +6dB (2026-07-31 vor Ort, Faktor ×2)
- **Limiter am Summenbus** (2026-07-31 vor Ort): alle Glocken schreiben auf
  einen privaten 4-Kanal-Bus (`~mixBus`), ein dauerhaft laufender
  `\klangLimiter`-Synth liest davon, klemmt hart auf `~limiterCeiling` (0.95)
  und schreibt erst danach auf die vier Hardware-Kanäle. Fängt Übersteuerung
  ab, wenn viele Node-Treffer gleichzeitig zusammenkommen und sich addieren —
  ohne Limiter kann das auch bei moderatem `~maxAmp` clippen/verzerren.
  Headless verifiziert (60–80 gleichzeitige Treffer bei voller Energie): Peak
  exakt bei 95.0 % Vollaussteuerung auf allen vier Kanälen, 0 Samples an
  Vollaussteuerung.
- **Reibung/Kernigkeit:** dezenter Sägezahn-Layer, bandpass-gefiltert um den
  Grundton — Hang-Drum-typischer "metallisch-kerniger" Attack
- **Hall:** FreeVerb, mehr räumliche Tiefe

Master-LED-Helligkeit (`/master/level`, separat vom Sound, betrifft Processing/
imPulse nicht SuperCollider) läuft mit Java-Boot-Default `0.1`
(Sicherheitsventil, `ArtNetOutput.java:43`) und wird für den Live-Betrieb per
OSC auf `1.0` gesetzt — siehe `scenes/hang_drum_slow/`.
