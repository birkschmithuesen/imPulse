# Tail-Synth: Kreisbewegung um den Auslöseort

Konzept für einen **zweiten Synth je Note** (Tail, zusätzlich zum bestehenden
`\glockenBell`), der nach dem Trigger im Kreis um seine Auslöseposition
wandert — zufällig rechts oder links herum, schnell am Anfang, langsamer zum
Ausklingen hin.

Branch: `docs/tail-orbit-concept`. **Kein Code, kein Merge, keine Änderung an
`supercollider/klangnetz_bells.scd`** — die Datei wurde für dieses Konzept nur
gelesen.

**Was dieses Dokument NICHT behandelt:** den Klang des Tail-Synths selbst.
Birk entwickelt das Sounddesign gerade in einer anderen Session. Hier steht
ausschließlich die **räumliche Rotation** und der **Vertrag**, den die
Klangerzeugung dafür erfüllen muss (Abschnitt 8). Die sechs Zeilen Orbit-Mathe
lassen sich an eine fertige SynthDef anhängen, ohne in die Klangerzeugung
einzugreifen.

---

## 1. Geprüfter Ausgangsstand

Alles Folgende ist am Code nachgelesen, nicht erinnert
(`supercollider/klangnetz_bells.scd`, Stand `a620445`):

| Baustein | Zeile | Was davon gebraucht wird |
|---|---|---|
| `~maxX = 7.0`, `~maxY = 4.0` | 903–904 | Normierung je Achse (halbe Grundfläche) |
| `~toQuad` | 905–915 | **der einzige Encoder**, wird wiederverwendet |
| `\glockenBell` | 932–984 | Vorbild für Hüllkurve, Start-Argumente, `doneAction: 2` |
| `\impulseDrone` | 997–1096 | Vorbild für bewegte Position — aber per OSC, nicht im Graphen |
| `~registerParam` | 438–443 | Registry für neue OSC-Parameter |
| `~activeBells` / `~maxPolyphony` | 353, 1401–1422 | vorhandene Stimmenbuchführung, hier wiederverwendbar |
| `~testSweep` | 1573–1604 | vorhandene Umlauf-Konvention: `x = sin`, `y = cos` |

Zwei Punkte, die den Entwurf bestimmen:

- **`~toQuad` normiert selbst.** Es nimmt Meter und teilt intern durch
  `~maxX`/`~maxY`. Ein neuer Encoder-Pfad wäre eine zweite Wahrheit — das
  Konzept rechnet deshalb normiert, multipliziert am Ende zurück auf Meter und
  ruft `~toQuad` unverändert auf. Der Rundweg kostet zwei Multiplikationen und
  spart eine Kopie der Rotation, der `panSharpness`-Verzerrung und der
  Pan4-Konvention.
- **`\impulseDrone` bewegt sich, aber anders.** Seine Position kommt mit 10 Hz
  per OSC herein und wird mit `Lag.kr(x, 0.1)` geglättet (Zeilen 1006–1007) —
  die Glättung repariert eine grobe Melderate. Der Tail-Orbit wird dagegen
  **im Graphen** gerechnet und ist dadurch von Haus aus stetig: **kein Lag
  nötig, keine OSC-Last je Note, keine sclang-Routine**. Eine naive Umsetzung
  (Routine, die alle 50 ms `.set(\x, ...)` schickt) würde bei jedem Node-Treffer
  einen eigenen Nachrichtenstrom erzeugen — genau der Weg, der bei hoher
  Trefferrate schon einmal zu „command FIFO full" geführt hat (Kommentar bei
  `~maxPolyphony`, Zeile 341–352).

---

## 2. Die Annahme, die alles trägt — Startpunkt (OFFENE FRAGE)

> **Nicht von Birk bestätigt.** Dieses Dokument rechnet durchgehend mit
> Interpretation (a); die Entscheidung ist aber billig und spät zu treffen,
> weil Formel, Clamping und Parameter davon unberührt bleiben.

Birks Wortlaut: *„Von dem Ort starten, wo er ausgelöst wurde, und dann im
Kreis wandern."*

**(a) Auslöseort = KREISMITTELPUNKT** (die getroffene Annahme). Der Tail
rotiert um seinen Entstehungsort. Musikalische Begründung: der Klang gehört
hörbar zu *diesem* Knoten und dehnt sich von dort aus, statt an einer
entfernten Position aufzutauchen, die mit dem Anschlag nichts zu tun hat.

**Der ehrliche Einwand dagegen:** bei festem Radius sitzt der Tail zum
Zeitpunkt t=0 **nicht** am Auslöseort, sondern bereits einen Radius daneben
(bei Startwinkel 0 also `~maxY · R` Meter davor). Das ist ein Sprung im
Einsatz und widerspricht dem Wortlaut „von dem Ort starten, wo er ausgelöst
wurde" streng genommen.

Zwei Varianten, die das auflösen, beide ohne Änderung an der übrigen Rechnung:

**(c) Auslöseort liegt AUF dem Kreis**, Mittelpunkt um einen Radius versetzt
(Richtung z. B. Raummitte oder zufällig). Der Tail startet exakt am
Auslöseort. Kosten: zwei Zeilen für den versetzten Mittelpunkt.

**(d) Radius wächst von 0 auf `orbitRadius`** — Mittelpunkt bleibt der
Auslöseort, der Tail startet exakt dort und schraubt sich heraus. Kosten:
**eine Zeile** (`rEff = orbitRadius * radiusRamp`, z. B.
`Line.kr(0, 1, rampTime)` oder die Attack-Flanke der Hüllkurve selbst). Das
ist die Variante, die Birks Formulierung *und* das Bild „der Klang explodiert
von seinem Ursprung nach außen" beide wörtlich erfüllt — und die Empfehlung
dieses Dokuments, falls der Einsatzsprung bei (a) beim Hören stört.

Nicht empfohlen: **(e) Mittelpunkt = Raummitte**, Auslöseort auf dem Kreis
(eine Lesart von „im Kreis wandern von den Lautsprechern her"). Dann wäre der
Radius keine freie Größe mehr, sondern der Abstand des Knotens zur Mitte — ein
Radius-Parameter, wie Birk ihn ausdrücklich verlangt hat, hätte darin keinen
Platz.

---

## 3. In welchem Raum ist der Kreis ein Kreis?

Die Grundfläche ist ein Rechteck 14 × 8 m, `~toQuad` normiert **je Achse
getrennt** (`px/7`, `py/4`). Ein Kreis in Metern ist deshalb im Pan4-Raum eine
Ellipse und umgekehrt — der Kommentar bei `~testSweep` hält genau das schon
fest.

**Entscheidung: der Kreis wird im NORMIERTEN Raum gerechnet.** Drei Gründe:

1. **Gehört wird im Lautsprecherraum, nicht auf dem Grundriss.** Ein
   normierter Kreis läuft die vier Boxen gleichmäßig ab; ein Meter-Kreis
   verbrächte 7/4 = 1,75-mal mehr Weg in der Links-Rechts-Achse und klänge
   nach einer schwankenden, nicht nach einer runden Bewegung.
2. **Der Radius bekommt eine ablesbare Einheit.** Normiert liegen die vier
   Boxen exakt bei (±1, 0) und (0, ±1) — `orbitRadius = 1.0` heißt also „bis
   auf Boxhöhe hinaus", `0.25` heißt „ein Viertel des Wegs zur Box". Ein
   Meterwert wäre in x und y verschieden weit.
3. **Das Clamping lebt ohnehin dort** (Abschnitt 5), also gibt es nur eine
   Koordinatenwelt statt zwei.

Nebenwirkung, die man kennen muss: `orbitRadius = 0.5` bedeutet auf dem
Grundriss 3,5 m Auslenkung in x, aber nur 2 m in y. Der Weg ist auf dem Papier
eine Ellipse — im Klangbild ein Kreis. Das ist gewollt.

---

## 4. Mathematisches Modell

Gegeben: Auslöseposition `(px0, py0)` in Metern (aus `/net/hitNode`, wie
`\glockenBell` sie schon bekommt), Zeit `t` seit Trigger, Hüllkurve `env(t)`
des Tail-Synths (0..1, Scheitel 1), Radius `R` (normiert), Richtung
`dir ∈ {−1, +1}`.

### 4.1 Winkel als Integral der Hüllkurve

```
ω(t) = 2π · orbitSpeed · dir · env(t)^p          Winkelgeschwindigkeit
θ(t) = θ₀ + ∫₀ᵗ ω(τ) dτ                          Winkel
```

`orbitSpeed` ist in **Umdrehungen pro Sekunde bei voller Hüllkurve**
(env = 1). `p = orbitEnvExp` verbiegt die Kopplung (Abschnitt 7), Default 1.

Warum die Amplitudenhüllkurve als Ratenfaktor und nicht eine eigene
Zeitfunktion:

- Sie ist **schon da** — Birk baut sie für die Lautstärke ohnehin. Eine zweite
  Zerfallskurve wäre eine zweite Zahl, die dasselbe meint, und die zwei
  könnten auseinanderlaufen (dieselbe Überlegung wie bei
  `/net/impulse/splitLifetimeJitter`, das bewusst ein *Faktor* auf `lifetime`
  ist und kein Ersatzwert).
- Sie erfüllt die Anforderung wörtlich: laut = schnell, leise = langsam,
  stumm = steht. **Die Rotation hört von selbst auf, wenn nichts mehr zu hören
  ist** — ohne Sonderfall, ohne Verzweigung (siehe offene Frage 4).
- Sie ist **stetig**. Ein Ansatz „Winkel = baseSpeed · t · decay(t)" (also
  Decay als Faktor auf den *Winkel* statt auf die *Rate*) würde den Winkel bei
  starkem Zerfall wieder **zurücklaufen** lassen — die Bewegung kehrte gegen
  Ende um. Das Integral der Rate kann das strukturell nicht.

### 4.2 Was das für den Gesamtweg bedeutet (wichtig beim Einstellen)

Der insgesamt überstrichene Winkel hängt nicht an `orbitSpeed` allein, sondern
am **Flächeninhalt unter der Hüllkurve**:

```
Umdrehungen gesamt = orbitSpeed · ∫₀^∞ env(t) dt
```

Für die gebräuchlichen Hüllkurven (Decay-Dauer `T`):

| Hüllkurve | ∫ env dt | Umdrehungen bei orbitSpeed = s |
|---|---|---|
| `Env.perc(a, T, curve: -4)` (wie `\glockenBell`) | **0,2313 · T** | 0,231 · s · T |
| `Env.perc(a, T, curve: \lin)` | 0,5 · T | 0,5 · s · T |
| gehaltene Phase (env = 1) der Dauer `T` | T | s · T |

Umgekehrt, zum Einstellen: **`orbitSpeed = Wunsch-Umdrehungen / (0,2313 · T)`**.
Eine volle Umdrehung über einen 4-Sekunden-Tail mit `curve: -4` braucht also
`orbitSpeed ≈ 1,08`.

Diese Zahl gehört in die Parameterdokumentation, weil sie sonst überrascht:
mit `orbitSpeed = 0.5` und einem 4-s-Tail kommt **weniger als eine halbe
Umdrehung** heraus (Abschnitt 9 rechnet das durch). Das ist kein Fehler,
sondern die Folge davon, dass die Bewegung mit dem Klang ausklingt.

### 4.3 Position

Konvention wie `~testSweep` (`x = sin`, `y = cos`): `θ = 0` zeigt nach **vorn**
(+y), wachsendes `θ` läuft **vorn → rechts → hinten → links**, also im
Uhrzeigersinn von oben. `dir = +1` ist damit „rechts herum".

```
xn(t) = px0/~maxX + R · sin(θ(t))
yn(t) = py0/~maxY + R · cos(θ(t))
```

Danach das Clamping (Abschnitt 5), dann zurück in Meter und durch den
vorhandenen Encoder:

```
~toQuad.(sig, xn' · ~maxX, yn' · ~maxY)
```

---

## 5. Clamping

### 5.1 Die Regel (Birks Entscheidung)

Der Betrag wird gekappt, **der Winkel läuft weiter**:

```
r     = hypot(xn, yn)
scale = 1 / max(r, 1)
xn'   = xn · scale
yn'   = yn · scale
```

Branchfrei, division­sicher (`max(r, 1) ≥ 1`, also nie durch 0), und
`r ≤ 1` ⇒ `scale = 1` ⇒ bitgleich unverändert. Die Rotation stoppt nicht: der
Punkt gleitet über den Teil des Umlaufs, der hinausliefe, am Rand entlang und
kehrt danach von selbst auf die Kreisbahn zurück.

**Der Auslöseort selbst wird davon miterfasst** — auch das ist wichtig: eine
Kante der Grundfläche liegt normiert bei `hypot(7/7, 4/4) = 1,41`, also
außerhalb. Ein Knoten dort erzeugt einen Orbit, der komplett außerhalb läge;
die Formel projiziert ihn geschlossen auf den Rand, statt einen Sonderfall zu
brauchen. Kein Kollaps, keine Stille, kein `if`.

Ausdrücklich **nicht** gewählt: den Radius vorab begrenzen
(`rEff = min(R, 1 − hypot(xn0, yn0))`). Das klingt sauberer, hat aber genau
dort keine Bewegung mehr, wo sie am deutlichsten wäre — am Rand des Netzes —
und wird bei einem Auslöseort außerhalb des Einheitskreises negativ.

### 5.2 Befund: `r ≤ 1` genügt Pan4 auf den Diagonalen nicht

> Das ist eine **Beobachtung am Code**, nicht am Gerät gemessen — nach der
> Hausregel dieses Projekts („maßgeblich ist die Messung, nicht die
> Herleitung") gehört sie vor einer Entscheidung per NRT gegengeprüft.

`~toQuad` übergibt Pan4 nicht `xn`/`yn`, sondern die 45-Grad-Rotation
`(xw − yw, xw + yw)`. Beide Argumente bleiben genau dann in ±1, wenn

```
|xn| + |yn| ≤ 1
```

gilt — also innerhalb der **Raute** mit den Ecken (±1, 0), (0, ±1). Diese Raute
ist genau die konvexe Hülle der vier Boxen, physikalisch also „innerhalb des
Lautsprecherquadrats".

Der Einheits**kreis** ist größer als diese Raute: auf der Diagonale
(`xn = yn = 0,707`, `r = 1`) ergibt sich `|xn| + |yn| = 1,41`, ein Pan4-Argument
also 41 % über dem Wertebereich. **`r ≤ 1` allein verhindert das nicht.**

Zwei Beobachtungen, die das einordnen:

1. **Der laufende Betrieb tut das schon heute.** Ein Knoten bei (5, 3) m ergibt
   `|xn| + |yn| = 1,46` — die Installation läuft damit seit Monaten ohne
   berichteten Ausfall. Das spricht stark dafür, dass Pan4 intern klemmt
   (verifizierbar per NRT), also **kein Phasen- oder Pegelunfall** droht.
2. **Für dieses Feature ist die Folge trotzdem spürbar.** Klemmt Pan4 intern,
   dann *steht* die Quelle in der Ecke des Lautsprecherquadrats still, während
   der Winkel weiterläuft — die Rotation wird für diesen Teil des Umlaufs
   **unhörbar**. Bei einer Glocke, die einmalig an einer festen Position
   klingt, fällt das nicht auf; bei einem Feature, dessen ganzer Zweck die
   Bewegung ist, schon.

**Vorschlag (offene Frage 5):** dieselbe Formel, ein Zeichen anders —

```
k     = xn.abs + yn.abs          statt   hypot(xn, yn)
scale = 1 / max(k, 1)
```

Das ist strikt schärfer als Birks Regel (auf den Achsen identisch, auf den
Diagonalen enger), garantiert Pan4-Argumente in ±1 und lässt den Klang am
**Rand des Lautsprecherquadrats entlanggleiten**, statt in einer Ecke
festzuhängen. Da Birk `r ≤ 1` explizit entschieden hat, steht es hier als
Befund und Empfehlung, nicht als stille Änderung — beide Fassungen sind eine
Zeile, die Entscheidung ist nach einer Hörprobe zu treffen.

---

## 6. SC-technische Umsetzung (Konzept, NICHT eingebaut)

### 6.1 Der Winkel im Graphen

Gesucht ist ein Integrator mit modulierbarer Rate. `Sweep.kr` ist genau das
und nimmt die Rate **in Einheiten pro Sekunde** — damit steht `orbitSpeed`
direkt in Umdrehungen/s im Graphen:

```supercollider
turns = Sweep.kr(0, orbitDir * orbitSpeed * env.pow(orbitEnvExp));
angle = turns * 2pi;
```

`Sweep` startet bei Synth-Beginn bei 0 und akkumuliert stetig — eine Änderung
der Rate ändert nie die Position, nur die Steigung. Genau dieselbe Eigenschaft,
aus der `MusicalClock` auf der Java-Seite akkumuliert statt neu zu rechnen.

Alternative mit Umlauf-Wrap (numerisch unbegrenzt lange stabil, hier
unnötig — ein Tail lebt Sekunden):

```supercollider
phase = Phasor.kr(0, orbitDir * orbitSpeed * env * ControlDur.ir, 0, 1, 0);
angle = phase * 2pi;
```

**Falle, die man dabei kennen muss:** `Phasor` erwartet den Zuwachs **pro
Sample** (bei `.kr`: pro Control-Sample), `Sweep` dagegen **pro Sekunde**. Wer
`ControlDur.ir` bei `Phasor` vergisst, bekommt eine um den Faktor
Control-Rate (typisch 689) zu schnelle Rotation — hörbar als Zirpen, nicht als
Bewegung. Deshalb hier `Sweep` als Empfehlung.

Nicht empfohlen: `LFSaw.kr(freq)` mit negativer Frequenz für die Gegenrichtung.
Läuft zwar rückwärts, hängt aber an einer Implementierungseigenschaft, während
`Sweep`/`Phasor` ein Vorzeichen in der Rate ausdrücklich vorsehen.

### 6.2 Skizze der SynthDef (Klangerzeugung bleibt Birks Teil)

```supercollider
// KONZEPT -- nicht einbauen. Der Klangteil ist Platzhalter.
SynthDef(\bellTail, { |freq = 440, amp = 0.2, out = 0,
        x0 = 0, y0 = 0,              // Ausloeseort in METERN
        orbitRadius = 0.25,          // NORMIERT: 1.0 = Boxabstand
        orbitSpeed = 0.5,            // Umdrehungen/s bei env = 1
        orbitEnvExp = 1.0,
        orbitDir = 1|                // +1 = im Uhrzeigersinn
    var sig, env, angle, xn, yn, k, scale;

    // ---- Birks Klangerzeugung: hier bewusst NICHT vorweggenommen ----
    env = EnvGen.kr(Env.perc(0.01, 4.0, curve: -4), doneAction: 2);
    sig = /* ... Tail-Klang ... */ * amp * env;

    // ---- Raeumliche Rotation: diese sechs Zeilen sind das Feature ----
    angle = Sweep.kr(0, orbitDir * orbitSpeed * env.pow(orbitEnvExp)) * 2pi;
    xn = (x0 / ~maxX) + (orbitRadius * angle.sin);
    yn = (y0 / ~maxY) + (orbitRadius * angle.cos);
    k = xn.hypot(yn);                 // bzw. xn.abs + yn.abs, siehe 5.2
    scale = 1 / max(k, 1);            // Betrag gekappt, Winkel laeuft weiter
    Out.ar(out, ~toQuad.(sig, xn * scale * ~maxX, yn * scale * ~maxY));
}).add;
```

`~maxX`/`~maxY`/`~toQuad` sind sclang-Werte und werden beim `.add` in den
Graphen eingerechnet — dasselbe Muster, das `\glockenBell` und `\impulseDrone`
schon benutzen.

### 6.3 sclang-Seite: der Trigger

Im vorhandenen `/net/hitNode`-Handler, direkt neben dem `Synth(\glockenBell, ...)`:

```supercollider
// Richtung je Trigger neu, 50/50. Bewusst in sclang statt per Rand/TIRand
// im Graphen: hier ist sie sichtbar, loggbar und per DirLock feststellbar --
// dieselbe Stelle, an der auch travelFreq gerechnet wird (Zeile 1305 ff.).
tailDir = if (~tailOrbitDirLock.abs > 0.5) {
    ~tailOrbitDirLock.sign
} {
    [-1, 1].choose
};
newTail = Synth(\bellTail, [
    \freq, freq, \amp, amp, \x0, px, \y0, py, \out, ~quadBus.index,
    \orbitRadius, ~tailOrbitRadius, \orbitSpeed, ~tailOrbitSpeed,
    \orbitEnvExp, ~tailOrbitEnvExp, \orbitDir, tailDir
], ~voices);
```

---

## 7. Parametrisierung

Alle über den vorhandenen `~registerParam`-Mechanismus, Adresspräfix
`/klangnetz/param/`, Namenskonvention wie `travelFreqBase`/`travelSpeedRef`
(gemeinsames Präfix `tailOrbit`, damit sie im Web-UI beieinanderstehen):

| Adresse | Bereich | Default | Bedeutung |
|---|---|---|---|
| `tailOrbitRadius` | 0.0 .. 2.0 | **0.25** | Radius, **normiert** (1.0 = Boxabstand). > 1 ist erlaubt, das Clamping fängt es ab |
| `tailOrbitSpeed` | 0.0 .. 4.0 | **0.5** | Umdrehungen/s bei voller Hüllkurve. Gesamtumdrehungen siehe 4.2 |
| `tailOrbitEnvExp` | 0.25 .. 4.0 | **1.0** | Exponent auf die Hüllkurve. < 1 = Rotation hält länger in den Ausklang hinein, > 1 = bremst früher ab |
| `tailOrbitDirLock` | −1 .. 1 | **0** | 0 = Zufall (Betrieb), ±1 = Richtung erzwungen (nur zum Prüfen) |
| `tailOrbitMinRadius` | 0.0 .. 0.5 | **0.0** | Untergrenze des wirksamen Radius, 0 = aus (siehe offene Frage 3) |

**Defaults bewusst zurückhaltend:** `0.25 / 0.5` ergibt bei einem
4-Sekunden-Tail eine knappe halbe Umdrehung mit ±1,75 m Auslenkung in x —
deutlich hörbar, aber nichts, was beim ersten Einschalten durch den Raum
schießt. Hochdrehen ist danach ein Regler, kein Neustart.

Vier Folgen, die zum Muster dieser Datei gehören:

- **`radius = 0` ist der Aus-Schalter** und klingt bitgleich wie ein Tail ohne
  dieses Feature — dieselbe Idiomatik wie `travelMix = 0` und
  `regionBiasAmount = 0`. Ein eigener `enabled`-Schalter entfällt deshalb.
- **Kein `onSet`-Callback**, wie bei `brightness`/`bellVolume`: der Tail ist
  ein One-Shot mit `doneAction: 2`, die Werte werden als Start-Argumente
  gelesen, ein Regler wirkt auf den **nächsten** Ton. Bei der Trefferrate der
  Installation ist das praktisch sofort. Soll es doch live an laufenden Tails
  wirken, ist der billigste Weg die schon vorhandene Buchführung: `~activeBells`
  hält Synths in Erzeugungsreihenfolge und räumt per `onFree` auf (Zeile 1422)
  — ein `~activeTails` nach demselben Muster plus `onSet`-Schleife, kein neuer
  Reaper.
- **Presets bekommen sie geschenkt.** `~presetSnapshot` liest `~params`, nicht
  eine gepflegte Liste — die fünf Adressen stehen ab dem ersten
  `/sc/preset/save` in jedem SC-Preset.
- **`SC_PARAMS` in `webui/server.py` muss nachgezogen werden.** Die
  handgepflegte Kopie der Registry driftet sonst ab; zwei Tests in
  `webui/test_webui.py` vergleichen sie in beide Richtungen mit der `.scd` und
  schlagen fehl, bis das passiert. Das ist Absicht und die richtige
  Fehlermeldung.

---

## 8. Vertrag zum Tail-Sounddesign

Das ist der Abschnitt, der Birk beim eigenen Sounddesign betrifft. Alles
andere kann danach angehängt werden.

**Was die SynthDef mitbringen muss:**

1. **Argumente `x0`, `y0`** (Auslöseort in **Metern**, wie
   `/net/hitNode` sie liefert) sowie `orbitRadius`, `orbitSpeed`,
   `orbitEnvExp`, `orbitDir`. Namen frei, aber einheitlich.
2. **Eine Hüllkurve als `.kr`-Signal in einer Variablen**, nicht nur inline in
   die Amplitude multipliziert:
   `env = EnvGen.kr(...); sig = ... * env;` — die Rotation braucht denselben
   Verlauf als **Steuersignal**. Es muss `EnvGen.kr` sein, nicht `.ar`
   (`Sweep.kr` bekommt sonst ein Audio-Signal als Rate).
3. **Scheitelwert 1.** `orbitSpeed` ist als „Umdrehungen/s bei env = 1"
   definiert; hat die Hüllkurve einen anderen Scheitel, skaliert die
   Rotationsgeschwindigkeit stillschweigend mit.
4. **Dieselbe Hüllkurve für Lautstärke und Rotation.** Zwei getrennte Kurven
   sind erlaubt, aber dann läuft die Bewegung gegen den Klang — der Tail
   könnte weiterkreisen, wenn nichts mehr zu hören ist, oder stehenbleiben,
   während er noch klingt.
5. **Ein MONO-Signal an `~toQuad`.** Der Synth darf nicht selbst `Pan4`,
   `Pan2` oder ein Vierkanal-Signal ausgeben — `~toQuad` ist der einzige
   Encoder, dort sitzen 45-Grad-Rotation und `panSharpness`. Ein zweiter
   Panning-Pfad wäre eine zweite Wahrheit, die bei der nächsten
   Verkabelungsmessung auseinanderfällt.
6. **`doneAction: 2` auf der Amplitudenhüllkurve.** Der Orbit fügt keine
   eigene Lebensdauer hinzu und beendet nichts.
7. **`out` auf `~quadBus.index`, Gruppe `~voices`.** Sonst greift der
   Abräumblock (Zeile 853) beim Neuladen der Datei nicht.

**Was das Sounddesign NICHT beachten muss:** Rotation, Clamping, Normierung,
Zufallsrichtung, Parameterregistrierung. Das sind die sechs Zeilen aus 6.2 plus
fünf `~registerParam`-Aufrufe und lässt sich an eine fertige, rein klanglich
entwickelte SynthDef anhängen, solange 1–7 stimmen.

**Eine Empfehlung zur Hüllkurvenform, rein aus der Bewegung heraus:** eine
Perc-artige Hülle mit `doneAction: 2` braucht keinen `gate`, keinen Timeout und
keinen Reaper. Eine gehaltene `Env.asr` (wie `\impulseDrone`) rotierte mit
konstanter Geschwindigkeit durch die Sustain-Phase — musikalisch legitim, aber
sie braucht dann eine Freigabelogik, die es für Node-Treffer heute nicht gibt.

---

## 9. Beispielrechnung

Trigger bei **px0 = 2 m, py0 = −1 m** (rechts der Mitte, leicht hinten).
Normiert: `xn0 = 2/7 = 0,2857`, `yn0 = −1/4 = −0,25`.
Parameter: `orbitRadius = 0,5`, `orbitSpeed = 0,5`, `orbitEnvExp = 1`,
`orbitDir = +1`.
Hüllkurve `Env.perc(0.01, 4.0, curve: -4)`, also
`env(t) = −0,0187 + 1,0187 · e^(−t)` und
`∫₀ᵗ env = −0,0187·t + 1,0187·(1 − e^(−t))`.

| t [s] | env | Θ [Umdr.] | θ [°] | xn | yn | r | px [m] | py [m] | Clamp (r ≤ 1) |
|---|---|---|---|---|---|---|---|---|---|
| 0.0 | 1.000 | 0.000 | 0.0 | 0.286 | 0.250 | 0.379 | 2.00 | 1.00 | — |
| 0.5 | 0.599 | 0.196 | 70.5 | 0.757 | −0.083 | 0.761 | 5.30 | −0.33 | — |
| 1.5 | 0.209 | 0.382 | 137.4 | 0.624 | −0.618 | 0.878 | 4.37 | −2.47 | — |
| 3.0 | 0.032 | 0.456 | 164.2 | 0.422 | −0.731 | 0.844 | 2.96 | −2.92 | — |
| 4.0 | 0.000 | 0.463 | 166.6 | 0.402 | −0.736 | 0.839 | 2.81 | −2.95 | — |

Zu lesen:

- **Die Bewegung ist vorn schnell und hinten zäh.** In der ersten halben
  Sekunde überstreicht der Tail 70°, in den letzten 2,5 Sekunden noch 29°.
  Genau das war die Anforderung.
- **Gesamt 167°**, also nicht ganz eine halbe Umdrehung — die Folge von
  Abschnitt 4.2. Für eine volle Umdrehung müsste `orbitSpeed` auf
  `1 / (0,2313 · 4) = 1,08` (siehe 4.2).
- **Der Einsatzpunkt (2,00 | 1,00) liegt 2 m vor dem Auslöseort** (0,5 · ~maxY).
  Das ist der Sprung aus offener Frage 1 — unter Variante (d) startete die
  Zeile stattdessen bei (2,00 | −1,00).
- **Das Clamping greift nie**, `r` bleibt ≤ 0,88. Mit der schärferen Regel aus
  5.2 dagegen sehr wohl: bei t = 1,5 s ist `|xn| + |yn| = 1,24`, der Punkt läge
  also außerhalb des Lautsprecherquadrats und würde auf (3,52 | −1,99)
  zurückgeholt.

**Zweite Tabelle, Clamping aktiv:** derselbe Trigger, aber `orbitRadius = 1,2`
(Regler bewusst hochgedreht), vier feste Winkel:

| θ [°] | xn roh | yn roh | r | scale | px' [m] | py' [m] | gekappt? |
|---|---|---|---|---|---|---|---|
| 0 | 0.286 | 0.950 | 0.992 | 1.000 | 2.00 | 3.80 | nein |
| 90 | 1.486 | −0.250 | 1.507 | 0.664 | 6.90 | −0.66 | **ja** |
| 180 | 0.286 | −1.450 | 1.478 | 0.677 | 1.35 | −3.92 | **ja** |
| 270 | −0.914 | −0.250 | 0.948 | 1.000 | −6.40 | −1.00 | nein |

Bei 90° und 180° wird der Punkt auf den Einheitskreis zurückgeholt — er landet
praktisch an der rechten bzw. hinteren Box statt dahinter. Der Winkel läuft
dabei ungebremst weiter; hörbar ist eine Bahn, die sich am Rand abflacht,
keine stockende Bewegung. (Anmerkung zu 5.2: bei 90° ist selbst nach diesem
Clamp `|xn'| + |yn'| = 1,15` — Pan4 bekäme dort weiterhin ein Argument über 1.)

---

## 10. Polyphonie und Aufräumen

Der Tail **verdoppelt die Synth-Erzeugungsrate je Node-Treffer**. Das berührt
zwei bestehende Grenzen:

- **`~maxPolyphony = 24`** zählt heute nur Glocken (`~activeBells`, Zeile 1401).
  Mit Tails laufen bei voller Auslastung 48 Stimmen statt 24 — genau die Last,
  gegen die dieser Deckel nach einem Live-Ausfall eingezogen wurde („command
  FIFO full", Ausfallmodus: **Stille ohne Absturz**).
- **Verwaiste Tails.** Wird beim Voice-Stealing `~activeBells.removeAt(0).free`
  ausgeführt, verschwindet die Glocke — ihr Tail rotiert weiter, ohne dass ihn
  jemand kennt oder zählt. Das ist der unangenehme Fall: er wächst still.

**Empfehlung:** Glocke und Tail als **Paar** in `~activeBells` führen
(`[bell, tail]` statt `bell`), beim Stehlen beide freigeben, `onFree` an der
Glocke räumt das Paar auf. Kein zweites Dictionary, kein zweiter Reaper, kein
zweiter Deckel — dieselbe Überlegung, aus der die Windschicht **in**
`\impulseDrone` sitzt statt als eigener Synth. Zusätzlich `~maxPolyphony`
konservativer setzen (Vorschlag 16), solange die reale Last des Tail-Klangs
nicht gemessen ist.

---

## 11. Offene Fragen für Birk

1. **Startpunkt — die Annahme dieses Dokuments (Abschnitt 2).** Gerechnet ist
   durchgehend mit *Auslöseort = Kreismittelpunkt*. Das ist **nicht bestätigt**
   und hat einen sichtbaren Haken: der Tail setzt dann einen Radius **neben**
   dem Auslöseort ein, nicht an ihm. Variante (d) — Radius wächst von 0 heraus
   — erfüllt „von dem Ort starten, wo er ausgelöst wurde" wörtlich, kostet eine
   Zeile und ändert an Formel, Clamping und Parametern nichts. Welche?
2. **Mindestradius?** Empfehlung: **nein**, `radius = 0` soll der Aus-Schalter
   bleiben und bitgleich wie ein statischer Tail klingen — dieselbe Idiomatik
   wie `travelMix = 0`. Ein erzwungenes Minimum nähme genau die Einstellung
   weg, mit der man das Feature ohne Neustart abschaltet. `tailOrbitMinRadius`
   ist deshalb als Regler mit Default 0 vorgeschlagen und nicht als Konstante:
   falls sich beim Hören zeigt, dass kleine Radien nur verschmieren statt zu
   bewegen, ist die Untergrenze eine Zahl und keine Codeänderung.
3. **Rotation bei env ≈ 0 stoppen oder weiterlaufen?** In der vorgeschlagenen
   Kopplung erledigt sich die Frage: `ω ∝ env` heißt, die Rotation kommt mit
   dem Ausklang von selbst zum Stehen, und `doneAction: 2` räumt den Synth ab —
   kein Sonderfall, keine Verzweigung. Relevant wird sie nur, falls der Tail
   eine **eigene**, von der Amplitude entkoppelte Orbit-Hüllkurve bekommt. Ist
   das geplant?
4. **Obergrenze gleichzeitig rotierender Tails** (Abschnitt 10). Empfehlung:
   Paar-Buchführung in `~activeBells` plus `~maxPolyphony` vorerst auf 16 statt
   24 — kein eigenes `~tailLimit`. Einverstanden, oder sollen Glocken und
   Tails wie Glocken und Drohnen zwei getrennte Deckel bekommen?
5. **Clamp-Geometrie: Kreis oder Raute** (Abschnitt 5.2). Entschieden ist
   `r ≤ 1`. Der Befund ist, dass Pan4 auf den Diagonalen erst bei
   `|xn| + |yn| ≤ 1` im spezifizierten Bereich liegt und die Bewegung sonst in
   den Ecken des Lautsprecherquadrats hängenbleiben kann. Beides ist dieselbe
   Zeile. Vor der Entscheidung wäre eine NRT-Messung fällig — der laufende
   Betrieb verletzt die Raute heute schon und klingt unauffällig, was für ein
   internes Klemmen in Pan4 spricht, aber gemessen ist es nicht.

---

## 12. Bewusst nicht vorgeschlagen

- **Ein eigener Positions-Reaper/Routine je Tail.** Die Bahn wird im Graphen
  gerechnet; eine sclang-Routine mit `.set` je Frame wäre pro Note ein eigener
  Nachrichtenstrom an den Server — dieselbe Lastquelle, gegen die
  `~maxPolyphony` existiert.
- **Ein zweiter Encoder-Pfad neben `~toQuad`** (etwa eine Variante, die
  normierte Koordinaten direkt nimmt und die Rückmultiplikation spart). Zwei
  Multiplikationen sind billiger als eine zweite Stelle, an der die
  45-Grad-Rotation, die Kanalkonvention und `panSharpness` gepflegt werden
  müssten.
- **Eine Kopplung an den Origin-Sequencer oder den Preset-Scheduler.** Der
  Orbit hängt an der Hüllkurve eines einzelnen Tons, nicht an einem BPM-Raster;
  eine Synchronisierung wäre ein drittes Zeitsystem neben Beat-Raster und
  Preset-Sekunden.
- **Radius oder Geschwindigkeit aus der Netzregion ableiten** (analog
  `~regionBias`). Naheliegend, aber ungefragt — und es würde die zwei hörbaren
  Merkmale der Zonen (Tonhöhe, Klangfarbe) um ein drittes ergänzen, bevor
  überhaupt feststeht, wie die Rotation für sich klingt.
