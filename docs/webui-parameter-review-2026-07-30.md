# Web-Interface Parameter-Review (2026-07-30)

Erstellt während laufender Live-Show — **keine Änderung an der Installation**,
reine Repo-Arbeit. Ranges wurden aus der Code-Semantik hergeleitet (Overflow-/
Divide-by-Zero-/physikalisch-sinnlose Grenzen), NICHT visuell getestet. Vor
Deploy: Birk-Review + Live-Verifikation nach Show-Ende.

> **Nachtrag 2026-07-31:** Die unten genannte Adresse
> `/net/impulse/energyDecayfactor` heisst seit dem Aufräumen des
> Energiezerfalls `/net/impulse/lifetime` (nur umbenannt, Formel und Range
> unverändert). `/net/impulse/energyDecay` gibt es nicht mehr — der Parameter
> war im Sketch wirkungslos (nur in auskommentiertem Code benutzt) und wurde
> ersatzlos entfernt; die Tabellenzeile unten beschreibt den Stand vom
> 2026-07-30.

## 1. Range-Vorschläge (bestehende ~50 Parameter)

Legende: ✅ = Range passt so, kein Handlungsbedarf · ⚠️ = Vorschlag zur engeren
Fassung (aktuelle Range technisch zu weit, führt zu unsinnigen/gefährlichen
Werten) · 🆕 = fehlt komplett im UI trotz Code-Parameter

| Adresse | Ist-Range | Code-Bedeutung | Vorschlag | Begründung |
|---|---|---|---|---|
| `/net/impulse/speed` | 1–1500 int | LEDs/Sekunde | ⚠️ **1–400** | Bei aktuellem Arbeitspunkt (Default 16) sind Werte >400 optisch nicht mehr als "wandernder Impuls" wahrnehmbar (40Hz Framerate × Stripe-Länge 600 LEDs → bei 1500 läuft ein Stripe in <0.4s durch, das ist ein Blitz, kein Puls). 1500 als Notausgang/Erweiterung im Code lassen, aber UI-Standardregler enger fassen |
| `/net/impulse/energyDecay` | 0.0001–0.5 | Energieverlust/Sek (linear) | ✅ | Gekoppelt an speed via `tune_speed.py`/webui-Speed-Kopplung — Range selbst ok, wird eh mitskaliert |
| `/net/impulse/energyDecayfactor` | 0.0001–1.0 | dito, faktoriell | ✅ | s.o. |
| `/net/impulse/nodeDeadTime` | 0–10s | Totzeit bis Node erneut feuern darf | ✅ | Bei 0 feuert ein Node theoretisch jeden Frame — das ist im Code erlaubt und als Extremfall (max. Netz-Aktivität) sinnvoll |
| `/net/impulse/energyExponent` | 1–10 int | Exponent auf Trigger-Energie (`for i in 1..exp: energy*=energy`) | ⚠️ **1–5** | Bei exp=10 wird jede Energie <1 durch wiederholtes Quadrieren binnen Nanosekunden auf ~0 kollabiert (0.9^1024 ≈ 0), jeder Trigger unter Vollausschlag wird unsichtbar — technisch gültig, praktisch nur 1–4 sinnvoll nutzbar |
| `/net/impulse/color/gamma` | 0.1–5 | Fade-Kurve (`energy^gamma`) | ✅ | Bei 0.1 fast Rechteck-Fade, bei 5 sehr weich — beide Enden sind gültige Looks |
| `/net/impulse/color/r,g,b` | 0–1 float | RGB des Impulses | ✅ | Physikalisch korrekt begrenzt (LedColor 0..1) |
| `/net/impulse/fadeOut/r,g,b` | 0–1 float | Pro-Frame-Fade-Multiplikator (Trail-Länge) | ✅ | 1.0=kein Fade (endlos), 0=sofort — beide sind gültige Extremzustände |
| `/net/impulse/oscRate` | 0–40 float | Sende-Hz an SC | ✅ | 0=Notaus, 40=Framerate-Limit (mehr als 1×/Frame ist sinnlos, da `drawMe()` bei 40Hz läuft) — Obergrenze ist bereits korrekt an die Framerate gekoppelt |
| `/net/impulse/oscMaxCount` | 0–256 int | Obergrenze gleichzeitiger Impuls-Meldungen/Takt | 🆕 **Advanced-Sektion** | Setup-Parameter (Rechnerlast-Schutz), kein gestalterischer Regler — sollte nicht im Haupt-UI neben Farbe/Speed stehen |
| `/net/randomSpawn/enabled` | 0/1 | Ambient-Spawns an/aus | ✅ | Toggle korrekt erkannt (webui zeigt Schalter) |
| `/net/randomSpawn/count` | 1–nStripes(30) int | Stripes pro Spawn-Event | ⚠️ **1–8** | Bei 30 (alle Stripes gleichzeitig) verliert "Ambient" seinen Sinn — wird zum Flächenblitz. Code-Grenze bleibt bei nStripes, UI-Vorschlag enger |
| `/net/randomSpawn/interval` | 0.05–40s | Sekunden zwischen Spawn-Events | ✅ | Gekoppelt an speed, Range selbst sinnvoll |
| `/net/randomSpawn/energy` | 0–1 float | Energie je Ambient-Impuls | ✅ | Korrekt an 0..1-Konvention gebunden |
| `/net/randomSpawn/directionBias` | 0–1 float | P(vorwärts) | ✅ | Wahrscheinlichkeit, korrekt begrenzt |
| `/net/randomSpawn/jitter` | 0–1 float | 0=periodisch, 1=stark verjittert | ✅ | Code klemmt effectiveInterval ohnehin gegen 0 ab (Mindestabstand 0.02s) |
| `/master/level` | 0–1 float | Show-Fader (Gesamthelligkeit ArtNet-Ausgang) | ✅ | Seit 2026-07-30 bewusst bis 1.0 freigegeben (Birk) — TestPatterns haben eigenen Fixpegel `CALIBRATION_MASTER_LEVEL=0.1`, unabhängig |
| `Master/trace` | 0–1 float | Globaler Feedback-/Nachleucht-Faktor (Mixer) | ✅ | 0=kein Trace, 1=Vollbild-Feedback-Loop — beide Enden sind Looks |
| `Master/0/opacity/0.Impulse`, `Master/1/opacity/1.Nodes` | 0–1 float | Pro-Effekt-Deckkraft im Mixer | ✅ | Standard-Opacity, korrekt begrenzt |
| `/nodes/times/fire` | 0–10s | Zeit "gerade gefeuert"→"inaktiv" | ✅ | |
| `/nodes/times/recover` | 0–10s | Zeit "inaktiv"→"wartend" | ✅ | |
| `/nodes/radius/waiting,fired,inactive` | 0–10 int | Spot-Radius um Node-LEDs | ✅ | Sinnvoll an Stripe-Länge (600 LEDs) gebunden, 10 ist klein genug um nicht zu überlappen |
| `/nodes/colors/*/Hue,Sat,Bright` (6 Tripel) | 0–1 float | Node-Farbzustände | ✅ | Korrekt als Color-Picker-Tripel erkannt (siehe Abschnitt 2) |
| `/nodes/fadeOutGamma` | 0.1–10 | Fade-Kurve am Node-Rand | ✅ | |
| `/nodes/pulseFrequency` | 0.1–10 Hz | Puls-Modulation im "wartend"-Zustand | ✅ | 10Hz ist bei 40Hz Framerate noch sauber abtastbar (Nyquist) |
| `/nodes/pulseFreqRandFrac` | 0–1 float | Zufallsanteil der Puls-Frequenz pro Node | ✅ | |
| `/net/impulse/color/useRemoteCol` | 0/1 int | 1=Remote-RGB, 0=feste Stripe-Farbtabelle | ✅ | Toggle korrekt |
| `/net/activateNode`, `/net/activateStripe` | 0–84 / 0–29 int | Direkt-Trigger (kein Setting, sondern Aktion) | 🆕 **Aus dem regulären Parameter-Grid raus** | Das sind keine Regler, sondern Einmal-Trigger (Botton-artig) — im aktuellen UI werden sie wie Slider dargestellt, was verwirrt. Vorschlag: eigene "Trigger"-Sektion mit Buttons statt Slidern |

## 2. Sections / Gruppierung

Bestehende Auto-Gruppierung (`server.py: group_key()`) funktioniert nach
Adress-Präfix, GROUP_ORDER sortiert sinnvoll. Geprüfte Lücken:

- **Color-Picker-Erkennung funktioniert korrekt** für alle 6 Node-Farb-Tripel
  (`/nodes/colors/{central,outer}/{waiting,fired,inactive}/{Hue,Sat,Bright}`) —
  kein Fix nötig, `build_groups()` erkennt sie automatisch.
- **`Master/0/opacity/0.Impulse`** und **`Master/1/opacity/1.Nodes`**:
  KORREKTUR nach Code-Test — `group_key()` filtert numerische Segmente
  tatsächlich schon korrekt raus (`[s for s in ... if not s.isdigit()]`),
  beide landen bereits in derselben Gruppe `Master/opacity`. Ursprünglicher
  Verdacht auf einen Gruppierungs-Bug war falsch, verifiziert per Testlauf
  der Funktion — **kein Fix nötig hier.**
- **Trigger-Parameter** (`/net/activateNode`, `/net/activateStripe`) gehören
  wie oben beschrieben in eine eigene Sektion mit anderem Widget-Typ.
- **Neue Vorschlags-Struktur (Sections im UI):**
  1. **Master** (Show-Fader, Trace, Mixer-Deckkraft)
  2. **Impuls** (`/net/impulse/*` außer Farbe) — Speed, Decay, Gamma, DeadTime
  3. **Impuls-Farbe** (RGB + FadeOut als eigener Sub-Block, ggf. später als
     Color-Picker wenn RGB→HSB migriert wird — aktuell sind `/net/impulse/color/r,g,b`
     KEINE Hue/Sat/Bright-Tripel, sondern rohes RGB, die automatische
     Color-Picker-Erkennung greift hier nicht. Für UI-Konsistenz wäre eine
     Migration auf HSB sinnvoll, ist aber ein Code-Change im Java, kein reiner
     UI-Fix — separat mit Birk absprechen.)
  4. **Ambient-Spawns** (`/net/randomSpawn/*`)
  5. **Nodes** (Zeiten, Radien, Farben, Puls)
  6. **Trigger** (activateNode/Stripe) — eigenes Widget
  7. **Advanced** (oscRate, oscMaxCount, energyExponent) — eingeklappt per Default

## 3. Sound: Ist-Zustand SuperCollider

`supercollider/klangnetz_bells.scd` hat **kein OSC-Parametersystem** wie
imPulse (`AbstractParameter.java`/`RemoteControlled*Parameter`). Alle
Klangparameter sind Konstanten im Code:

| Was | Wo im Code | Aktueller Wert |
|---|---|---|
| Master-Lautstärke | — | **fehlt komplett**, nur pro-Synth `amp`-Argumente (`~minAmp=0.08`, `~maxAmp=0.5`, `~droneAmpScale=0.06`) |
| Hall/Reverb | — | **fehlt komplett**, keine Reverb-UGen im Signalpfad |
| Timbre (Klangfarbe der Synths) | `SynthDef(\glockenBell...)`, `SynthDef(\impulseDrone...)` | Fest verdrahtet: Partialtöne/-Amps/-Decays für Glocke, LFTri+SinOsc+LPF für Drohne |
| Tonleiter/Grundton | `~pentatonicSteps`, `~rootMidiNote`, `~numOctaves` | Fest verdrahtet |

Das bestätigt Birks Verdacht: Sound-Parameter fehlen im Web-Interface, weil
sie im SC-Patch selbst noch nicht als steuerbare Parameter existieren — das
ist kein UI-Bug, sondern eine fehlende Schicht.

## 4. Nächste Schritte (nicht in diesem Durchlauf)

- [ ] SC-Parameterbrücke bauen: OSC-steuerbare Bus-Controls für Master-Level,
      Reverb (Mix/Time/Damp), Timbre (Bright/Detune) je SynthDef — kein
      Auto-Dump wie bei imPulse, eigene kleine Registry nötig
- [ ] Reverb-UGen in die Signalkette einfügen (Send/Return am B-Format-Bus
      oder am Decoder-Ausgang — Architekturentscheidung, siehe SC-Skill)
- [ ] Hörproben lokal rendern (vServer SC-Dummy-Audio-Umgebung, kein Zugriff
      auf Windows-Laptop nötig) und Birk zuschicken
- [ ] `server.py`/webui um zweiten OSC-Zielport (8002, SC) erweitern —
      aktuell single-target auf Port 8001 (imPulse)
- [ ] Group_key()-Fix für `Master/<N>/opacity` (siehe Abschnitt 2)
- [ ] Alles als PR vorlegen, NICHT deployen bis Birk freigibt UND Show vorbei ist
