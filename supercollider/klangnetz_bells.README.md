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

- **Skala:** Phrygisch ab A2 (`~scaleSteps`) — vorher D Kurd (natürliche Moll),
  geändert 2026-07-31 vor Ort
- **Grundton:** A2 (MIDI 45) — historisch: D3 → D2 (eine Oktave tiefer,
  2026-07-30) → A2 (eine Quinte höher, +7 Halbtöne, 2026-07-31 vor Ort)
- **4-Kanal-Ausgang** über Zoom AMS-24 (ASIO), **Ambisonics 2D erster Ordnung**:
  Encoder `PanB2` je Stimme, genau ein `DecodeB2` am Ende, Lautsprecher auf den
  Seitenmitten. Der Ort einer Glocke kommt aus den Koordinaten in
  `/net/hitNode`, der einer Drohne aus `/net/impulse`. Das ersetzt seit der
  Zusammenführung mit dem Ambisonics-Zweig (2026-07-31) die frühere feste
  Kanalzuteilung `outCh = nodeId % 4`, die keinen Ortsbezug hatte.
  **Zwei Werte darin sind ungemessen** (`~azimuthSign`, `~azimuthOffset`) —
  Ablauf der Messsitzung im Kopfblock der `.scd`
- **Drohnen** für die reisenden Impulse (`/net/impulse`), leise gehalten,
  eigener Deckel `~droneLimit` (32) und Timeout-Freigabe
- **Sound-Parameter per OSC** unter `/klangnetz/param/<name>` (Port 8002):
  masterVolume, reverbMix/Room/Damp, brightness, detune, droneLpfMult,
  directHardness — Adressliste im Kopfblock der `.scd`
- **Lautstärke:** `~minAmp`/`~maxAmp`, historisch: -10dB (2026-07-30, gegen zu
  leise Treffer) → +6dB (2026-07-31 vor Ort, Faktor ×2) → kurzzeitig -6dB
  gegenüber dem -10dB-Stand → **wieder +6dB** (2026-07-31 vor Ort, finaler
  Stand — "lass die Lautstärke wie sie ist, mit den +6dB")
- **Limiter am Ausgang** (2026-07-31 vor Ort): begrenzt hart auf 0.95, je
  Kanal, als letzte Stufe des `\masterReverb`-Synths hinter dem Decoder. Fängt
  Übersteuerung ab, wenn viele Node-Treffer gleichzeitig zusammenkommen und
  sich addieren — ohne Limiter kann das auch bei moderatem `~maxAmp`
  clippen/verzerren. Headless verifiziert (60–80 gleichzeitige Treffer bei
  voller Energie): Peak exakt bei 95.0 % Vollaussteuerung auf allen vier
  Kanälen, 0 Samples an Vollaussteuerung. Die Messung stammt vom eigenen
  `\klangLimiter` hinter dem privaten `~mixBus` der Laptop-Fassung; seit der
  Zusammenführung tut denselben Dienst der Limiter in `\masterReverb`
  (gleicher Wert, gleiche Stelle in der Kette — hinter allem, vor der
  Hardware), ein zweiter Limiter in Reihe wäre nur eine zweite Fundstelle.
- **Polyphonie-Deckel** `~maxPolyphony` (24 Glocken): Voice-Stealing der
  ältesten Stimme gegen „command FIFO full" und stillen Sound-Ausfall
  (2026-07-31, nach Live-Ausfall)
- **Reibung/Kernigkeit:** dezenter Sägezahn-Layer, bandpass-gefiltert um den
  Grundton — Hang-Drum-typischer "metallisch-kerniger" Attack
- **Hall:** FreeVerb **nach** dem Decoder, je Hardware-Kanal getrennt (vier
  dekorrelierte Hallfahnen). Vorher sass er in jeder Glocke (`mix 0.42`); ein
  Hall vor dem Encoder wird selbst räumlich codiert und vom Decoder wieder zu
  einer Punktquelle verschmiert. Damit der gewohnte Hallanteil bleibt, ist der
  Default von `~reverbMix` auf 0.35 gesetzt (statt 0.15) — live nachstellbar
  per `/klangnetz/param/reverbMix`.

Master-LED-Helligkeit (`/master/level`, separat vom Sound, betrifft Processing/
imPulse nicht SuperCollider) läuft mit Java-Boot-Default `0.1`
(Sicherheitsventil, `ArtNetOutput.java:43`) und wird für den Live-Betrieb per
OSC auf `1.0` gesetzt — siehe `scenes/hang_drum_slow/`.
