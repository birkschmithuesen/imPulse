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

## Vierkanal-Mitschnitt auf Platte (seit 2026-08-01)

Für Videodrehs schneidet die Datei den **fertigen Vierkanal-Ausgang** als WAV
mit — alles nach Panning, Hall, `masterVolume`, Limiter und der
Hardware-Kanal-Permutation. Aufgenommen wird deshalb **Bus 0** (der
Hardware-Ausgang, auf den `\masterReverb` schreibt), nicht `~quadBus`: dort
stehen die Stimmen *vor* dem Master, ein Mitschnitt davon klänge anders als
das, was im Raum zu hören ist.

Der Recorder-Synth muss dafür **hinter** `\masterReverb` im Node-Baum hängen.
`s.record(path, 0, chans, s.defaultGroup)` hängt ihn an den Schwanz von
`s.defaultGroup`, in der `~voices` und `~masterReverbSyn` beide liegen — damit
läuft er nach beiden. Ohne das läse er den Ausgangsbus, bevor der Master ihn
beschrieben hat, und die Datei bliebe still.

**Vier OSC-Adressen**, alle auf `~oscListenPort` (**8002**, derselbe Port wie
die Sound-Parameter), alle **ohne Argument**:

| Adresse | Wirkung |
| --- | --- |
| `/klangnetz/record/start` | startet einen Mitschnitt |
| `/klangnetz/record/stop`  | schliesst ihn ab |
| `/klangnetz/record/toggle`| start bzw. stop, je nach Zustand |
| `/klangnetz/record/query` | ändert **nichts**, meldet nur den Zustand |

`start`/`stop` sind der normale Weg: ein verlorenes Datagramm kann den Zustand
dann nicht umdrehen. Der Toggle ist der Rückfall für eine Gegenstelle, die
ihren Zustand *nicht* kennt (im Web-UI genau dann, wenn kein Kontakt zu sclang
besteht).

**Rückmeldung:** bei jedem Start, jedem Stop, jedem `query` **und auch bei
einem ignorierten Doppel-Start** geht

```
/klangnetz/record/status <0|1:int> <pfad:string>
```

an `127.0.0.1:~recordStatusPort` (**8003**) — ein eigener Port, weder 8001
(imPulse) noch 8002 (sclang selbst). Dort hört das Web-UI, damit sein
Aufnahme-Knopf den richtigen Zustand zeigt, auch wenn Start/Stop woanders
ausgelöst wurde (Skript, IDE, zweiter Browser). Fire-and-forget wie alles
andere hier: läuft das Web-UI nicht, geht das Datagramm ins Leere.

**Datei:** `recordings/klangnetz_YYYY-MM-DD_HH-MM-SS.wav` im **Repo-Root**
(`thisProcess.nowExecutingPath.dirname.dirname`, also neben `data/` und
`webui/` — anders als `~presetDir`, der bewusst neben der `.scd` bleibt:
Sound-Presets gehören zum Patch, Mitschnitte nicht). Der Ordner wird beim
ersten Start angelegt, falls er fehlt, und ist in `.gitignore` — die Dateien
gehören nicht ins Repo. Zeitstempel in **lokaler** Zeit, von Hand
zusammengesetzt statt `Date.stamp` (dessen `YYMMDD_HHMMSS` ist ohne
Trennzeichen im Dateimanager schlechter zu lesen).

**Format:** WAV/int24 statt der SC-Vorgabe AIFF/float32 — unkomprimiert, aber
so, dass jeder Videoschnitt es ohne Nachfragen öffnet. Die **Samplerate steht
nirgends im Code**: der Puffer wird mit der Rate des laufenden Servers
angelegt. Die **Kanalzahl** kommt aus `s.options.numOutputBusChannels`, keine
`4` als Literal.

**Verhalten in den Randfällen:**

- **Doppelter Start:** kein zweiter Recorder, kein zweites File — eine
  Log-Zeile (`"Aufnahme laeuft bereits, Start ignoriert: <pfad>"`) und
  trotzdem eine Status-Meldung mit der Wahrheit.
- **Stop ohne laufende Aufnahme:** Log-Zeile, kein Fehler, kein Absturz.
- **`~recordActive` ist die einzige Quelle der Wahrheit.** `s.isRecording`
  wäre eine zweite und könnte davon abweichen (etwa nach `s.reboot`) — zwei
  Zustände, in denen Start und Stop sich gegenseitig blockieren, wären
  schlimmer als einer, der einmal falsch steht.
- **Re-Evaluieren der Datei** (Live-Coding) schliesst eine laufende Aufnahme
  sauber ab: der Abräumblock in `waitForBoot` ruft `~recordStop` **vor**
  `~voices.free`, damit der letzte Klang noch in der Datei landet. Der
  Zustandsblock oben legt `~recordActive` nur an, wenn es ihn noch nicht gibt
  (`?? { }`) — ein bedingungsloses `= false` würde die laufende Aufnahme hier
  vergessen und die Datei bliebe ohne fertigen Header liegen.
- **Hart abgeschossenes sclang** hinterlässt eine unfertige WAV-Datei. Dagegen
  hilft nur der Stop; auch der Aufräumblock am Dateiende ruft ihn zuerst.

**Manuell verifizieren** (ohne Web-UI, Blöcke am Ende der `.scd` stehen dafür
schon fertig auskommentiert bereit):

```supercollider
NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/record/start");
// -> Konsole: "Aufnahme laeuft: <pfad> (4 Kanaele, 48000 Hz)"
// -> recordings/ enthaelt eine wachsende klangnetz_<zeitstempel>.wav
NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/record/start");
// -> "Aufnahme laeuft bereits", KEINE zweite Datei
NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/record/stop");
// -> Datei ist geschlossen und mit jedem Player abspielbar, 4 Kanaele
```

Ein SC-Testgerüst gibt es im Repo nicht; geprüft ist von der Python-Seite aus
nur, dass die vier Kommandoadressen, die Statusadresse und die zwei Ports in
`.scd` und `webui/server.py` **übereinstimmen** (`RecordScdTest` in
`webui/test_webui.py`) — nicht, dass eine Datei entsteht. Das bleibt der
Handgriff oben.

Master-LED-Helligkeit (`/master/level`, separat vom Sound, betrifft Processing/
imPulse nicht SuperCollider) läuft mit Java-Boot-Default `0.1`
(Sicherheitsventil, `ArtNetOutput.java:43`) und wird für den Live-Betrieb per
OSC auf `1.0` gesetzt — siehe `scenes/hang_drum_slow/`.
