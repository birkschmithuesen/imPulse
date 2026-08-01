# Tail-Synths + Orbit — Implementierungsplan

> **Für agentische Worker:** dieser Plan wird in derselben Session inline
> ausgeführt (keine Subagenten — Birks Vorgabe für diesen Auftrag).
> Schritte als Checkbox (`- [ ]`).

**Ziel:** Die fünf akustisch abgenommenen Tail-Synths aus
`/tmp/klangnetz_bell_tails_v3.scd` additiv in `supercollider/klangnetz_bells.scd`
integrieren, mit ADS-Hüllkurven als OSC-Parameter und der in
`docs/superpowers/specs/2026-08-01-tail-synth-orbit-design.md` (Branch
`docs/tail-orbit-concept`) entschiedenen Kreisbewegung um den Auslöseort.

**Architektur:** Fünf neue SynthDefs neben `\glockenBell`, alle nach dem
Vertrag aus Abschnitt 8 des Orbit-Konzepts (mono an `~toQuad`, Hüllkurve als
`.kr`-Variable mit Scheitel 1, `doneAction: 2`). Zwei sclang-Lambdas
(`~tailEnv`, `~tailOrbitOut`) halten Hüllkurve und Orbit-Mathe an **einer**
Stelle statt fünfmal kopiert — dasselbe Muster wie `~toQuad`. Ein
`/net/hitNode` erzeugt danach 6 Synths (1 Glocke + 5 Tails), die als **eine**
Einheit in `~activeBells` geführt werden.

**Tech-Stack:** SuperCollider 3.11 (sclang/scsynth), Python-3-Flask-WebUI
ohne Fremdabhängigkeiten, NRT-/Jack-Dummy-Rendering zur Verifikation.

## Global Constraints

- **Genau EINE SC-Sound-Datei:** `supercollider/klangnetz_bells.scd`. Keine
  Kopie, kein zweites `.scd` im Repo (CLAUDE.md).
- **Alles bleibt in EINEM `(...)`-Top-Level-Block** — mehrere Blöcke hängen
  `sclang -D` auf (Kopfkommentar der Datei).
- **`~toQuad` ist der einzige Encoder.** Kein `Pan4`/`Pan2` in einer
  Tail-SynthDef.
- **Kein Push, kein Deploy, kein Merge** nach `master`/`grabicz26`.
- Adresspräfix aller Sound-Parameter: `/klangnetz/param/<name>`, Registrierung
  ausschliesslich über `~registerParam`.
- `webui/server.py`s `SC_PARAMS` ist eine handgepflegte Kopie der Registry;
  zwei Tests in `webui/test_webui.py` vergleichen beide Richtungen und schlagen
  fehl, bis sie nachgezogen ist.

---

## Entschiedene Punkte (vor der Umsetzung, kurz begründet)

1. **One-Shot statt Gate.** Ein Node-Treffer hat kein Ende-Ereignis —
   `/net/hitNode` ist ein Impuls, kein Halten. Ein Gate bräuchte eine
   Freigabelogik plus Reaper, die es für Treffer heute nicht gibt (die hat nur
   `\impulseDrone`, weil dort `/net/impulse` ein *Ausbleiben* meldet).
   Abschnitt 8 des Orbit-Konzepts empfiehlt aus demselben Grund eine
   Perc-artige Hülle. „Sustain" ist damit ein **Plateau-Pegel** im festen
   Envelope: `Env([0, 1, sustain, 0], [attack, decay, release])`.
2. **Defaults `decay = 0`, `sustain = 1`** → die Kurve ist formgleich mit dem
   abgenommenen v3-Stand (`Env([0,1,0],[attack,release])`). Der ADS-Regler ist
   damit ein Werkzeug, keine Klangänderung hinter Birks Rücken.
3. **Attack-Defaults** nur bei Shimmer/Whoosh/Sub-Glow runter (0.02/0.02/0.03),
   FM-Glide und Granular bleiben bei 0.7 — genau die Rückmeldung im Handoff.
4. **Orbit-Parameter global**, nicht je Tail (5 statt 25). Empfehlung des
   Auftrags; eine Pro-Tail-Variante bleibt später möglich, ohne dass sich der
   Vertrag ändert.
5. **Rotationsrichtung je Tail unabhängig gewürfelt** (`[-1,1].choose` pro
   Synth, nicht pro Treffer) — sonst rotieren alle fünf Schichten synchron und
   die additive Schichtung klingt wieder wie eine Quelle.
6. **`radiusRamp` benutzt die Attack-Zeit** des jeweiligen Tails, kein eigener
   Parameter (Abschnitt 2 des Konzepts nennt genau diese Variante). Folge: bei
   20 ms Attack ist der Radius praktisch sofort voll — die Rampe entfernt dann
   nur den Sprung im Einsatz, sie ist keine hörbare Spiralbewegung. Bei
   FM-Glide/Granular (0.7 s) ist sie eine.
7. **`FreeVerb` bleibt in Shimmer/FM-Glide/Sub-Glow drin**, obwohl die Datei
   sonst „Hall NACH dem Panning" durchhält. Grund: das ist der akustisch
   abgenommene Klang, und der Auftrag sagt ausdrücklich, die Klangerzeugung
   nicht zu verändern. Als Punkt für Birk im Bericht benennen.
   **ÜBERHOLT beim Merge nach `master` (Birk, 2026-08-01):** die drei
   `FreeVerb` sind entfernt, die Tails laufen trocken (23 % statt 65 %
   Server-CPU). Begründung und die alten Werte stehen im Kommentar über den
   Tail-SynthDefs in `klangnetz_bells.scd`.
8. **`\tail4_granular` wird mono.** `GrainSin.ar(2, ..., pan: ...)` ist ein
   zweiter Panning-Pfad und verletzt den Vertrag; `numChannels: 1`, kein `pan`.
   Die Bewegung liefert jetzt der Orbit.

---

## Dateien

- **Modify:** `supercollider/klangnetz_bells.scd` — Kopfblock-Doku, 31 neue
  `~registerParam`-Aufrufe, `~tailAmpScale`, `~tailDefs`, `~tailEnv`,
  `~tailOrbitOut`, 5 SynthDefs, Trigger-Erweiterung in `/net/hitNode`,
  `~maxPolyphony` 24 → 16.
- **Modify:** `webui/server.py` — 31 Einträge in `SC_PARAMS`.
- **Modify:** `CLAUDE.md` — Abschnitt „Klangseite" um Tails + Orbit ergänzen,
  Parameterzahl nachziehen.
- **Create (ausserhalb des Repos):** `/tmp/tail_verify.scd`,
  `/tmp/tail_analyze.py` — NRT-/Jack-Dummy-Verifikation.

## Namen und Bereiche (verbindlich für alle Tasks)

| Tail | SynthDef | Präfix | Attack | Decay | Sustain | Release | Amp |
|---|---|---|---|---|---|---|---|
| 1 | `\tail1_shimmer` | `tailShimmer` | **0.02** | 0.0 | 1.0 | 4.5 | 1.0 |
| 2 | `\tail2_whoosh` | `tailWhoosh` | **0.02** | 0.0 | 1.0 | 3.2 | 1.0 |
| 3 | `\tail3_fmglide` | `tailFmglide` | 0.7 | 0.0 | 1.0 | 3.8 | 1.0 |
| 4 | `\tail4_granular` | `tailGranular` | 0.7 | 0.0 | 1.0 | 3.5 | 1.0 |
| 5 | `\tail5_subglow` | `tailSubglow` | **0.03** | 0.0 | 1.0 | 4.2 | 1.0 |

Ranges: Attack `0.001..2.0`, Decay `0.0..4.0`, Sustain `0.0..1.0`,
Release `0.2..12.0`, Amp `0.0..2.0`.

Global: `tailVolume` 0.5 (0..1.5), `tailOrbitRadius` 0.25 (0..2),
`tailOrbitSpeed` 0.5 (0..4), `tailOrbitEnvExp` 1.0 (0.25..4),
`tailOrbitDirLock` 0.0 (−1..1), `tailOrbitMinRadius` 0.0 (0..0.5).

`~tailAmpScale = 0.6` (Konstante, kein Parameter): das Verhältnis
Tail-Amp/Bell-Amp aus dem abgenommenen v3-Render (0.18 zu 0.3).
`tailVolume` Default 0.5, weil fünf unkorrelierte Schichten sich in der
Leistung addieren (≈ √5 × Einzelpegel) — 1.0 wäre lauter als die Glocke selbst.

---

### Task 1: Fünf Tail-SynthDefs mit ADS-Parametern

**Files:** Modify `supercollider/klangnetz_bells.scd`

**Produces:** SynthDefs `\tail1_shimmer`, `\tail2_whoosh`, `\tail3_fmglide`,
`\tail4_granular`, `\tail5_subglow` mit den Argumenten
`freq, amp, out, x0, y0, attack, decay, sustain, release, tailVolume`
(Orbit-Argumente kommen in Task 2 dazu). Lambda `~tailEnv.(attack, decay,
sustain, release, curve)` → `EnvGen.kr(..., doneAction: 2)`, Scheitel 1.
Registry-Tabelle `~tailDefs` = Liste `[defName, prefixString]`.

- [ ] **Schritt 1:** `~tailAmpScale`, `~tailDefs` und die 26 `~registerParam`
  (25 ADS/Amp + `tailVolume`) neben den bestehenden Registrierungen anlegen.
- [ ] **Schritt 2:** `~tailEnv` direkt nach `~toQuad` definieren.
- [ ] **Schritt 3:** Die fünf SynthDef-Bodies aus
  `/tmp/klangnetz_bell_tails_v3.scd` übernehmen, `! 2` entfernen,
  `GrainSin.ar(1, ...)` ohne `pan`, `dur` → `release`, feste Env → `~tailEnv`,
  vorläufig `Out.ar(out, ~toQuad.(sig, x0, y0))`.
- [ ] **Schritt 4:** Syntaxprüfung —
  `QT_QPA_PLATFORM=offscreen sclang -d /tmp -e 'thisProcess.interpreter.compileFile("…/klangnetz_bells.scd"); "PARSE_OK".postln; 0.exit'`
  Erwartet: `PARSE_OK`, kein `ERROR`.
- [ ] **Schritt 5:** Commit `sound: fuenf additive Tail-Synths mit ADS-Parametern`.

### Task 2: Orbit-Mathematik

**Files:** Modify `supercollider/klangnetz_bells.scd`

**Consumes:** `~tailEnv`, die fünf SynthDefs aus Task 1.
**Produces:** Lambda
`~tailOrbitOut.(sig, env, x0, y0, attack, orbitRadius, orbitSpeed, orbitEnvExp, orbitDir, orbitMinRadius)`
→ `Out`-taugliches Vierkanalsignal; fünf zusätzliche `~registerParam`.

- [ ] **Schritt 1:** Fünf `~registerParam` (`tailOrbit*`).
- [ ] **Schritt 2:** `~tailOrbitOut` nach `~tailEnv` definieren:

```supercollider
~tailOrbitOut = { |sig, env, x0, y0, attack, orbitRadius, orbitSpeed,
        orbitEnvExp, orbitDir, orbitMinRadius|
    var angle, radiusRamp, rEff, xn, yn, k, scale;
    angle = Sweep.kr(0, orbitDir * orbitSpeed * env.pow(orbitEnvExp)) * 2pi;
    radiusRamp = EnvGen.kr(Env([0, 1], [attack], \sin));
    rEff = orbitRadius.max(orbitMinRadius) * radiusRamp;
    xn = (x0 / ~maxX) + (rEff * angle.sin);
    yn = (y0 / ~maxY) + (rEff * angle.cos);
    k = xn.abs + yn.abs;
    scale = 1 / k.max(1);
    ~toQuad.(sig, xn * scale * ~maxX, yn * scale * ~maxY)
};
```

- [ ] **Schritt 3:** In allen fünf SynthDefs die Orbit-Argumente ergänzen und
  `Out.ar(out, ~toQuad.(sig, x0, y0))` durch `~tailOrbitOut.(...)` ersetzen.
- [ ] **Schritt 4:** Syntaxprüfung wie Task 1 Schritt 4.
- [ ] **Schritt 5:** Commit `sound: Tail-Orbit (Rotation um den Ausloeseort)`.

### Task 3: Trigger, Paar-Buchführung, Polyphonie

**Files:** Modify `supercollider/klangnetz_bells.scd` (`/net/hitNode`-Handler,
`~maxPolyphony`)

**Consumes:** `~tailDefs`, alle Parameter aus Task 1+2.

- [ ] **Schritt 1:** `~maxPolyphony` 24 → 16, Kommentar auf 6 Synths je Treffer.
- [ ] **Schritt 2:** Voice-Stealing auf Einträge umstellen
  (`~activeBells.removeAt(0).do({ |syn| syn.free })`).
- [ ] **Schritt 3:** Nach dem Bell-`Synth` die fünf Tails erzeugen,
  Richtung je Tail unabhängig, Eintrag `[newBell] ++ newTails` in
  `~activeBells`, `newBell.onFree` räumt den Eintrag.
- [ ] **Schritt 4:** Verifikation (siehe Task 5), zumindest Syntaxprüfung.
- [ ] **Schritt 5:** Commit `sound: alle fuenf Tails je Node-Treffer, Paar-Buchfuehrung`.

### Task 4: WebUI und Dokumentation

**Files:** Modify `webui/server.py`, `CLAUDE.md`,
Kopfblock von `supercollider/klangnetz_bells.scd`

- [ ] **Schritt 1:** 31 Einträge in `SC_PARAMS` (`tailVolume` in `TAB_MIXER`,
  Gruppe „Master"; Rest `TAB_SOUND`, Gruppen „Bell-Tails", „Tail 1 Shimmer" …
  „Tail-Orbit").
- [ ] **Schritt 2:** `python3 webui/test_webui.py` — erwartet `OK`, insbesondere
  `test_table_has_not_drifted_from_the_scd` und die Gegenrichtung.
- [ ] **Schritt 3:** Kopfblock der `.scd` und `CLAUDE.md` nachziehen
  (Parameterzahl, Tails, Orbit, One-Shot-Entscheidung).
- [ ] **Schritt 4:** Commit `webui+docs: Tail- und Orbit-Parameter`.

### Task 5: NRT-/Jack-Dummy-Verifikation

**Files:** Create `/tmp/tail_verify.scd`, `/tmp/tail_analyze.py` (ausserhalb
des Repos — CLAUDE.md: kein SC-Testgerüst im Repo)

- [ ] **Schritt 1:** `jackd -r -d dummy -r 48000 -p 512` im Hintergrund.
- [ ] **Schritt 2:** `/tmp/tail_verify.scd` lädt die **echte** Repo-Datei,
  wartet auf den Boot, startet `s.record(numChannels: 4)`, schickt per
  `NetAddr("127.0.0.1", 8002)` einen `/net/hitNode`, wartet, stoppt.
- [ ] **Schritt 3:** `/tmp/tail_analyze.py` liest die 4-Kanal-WAV mit `wave` +
  `numpy` und gibt je 100-ms-Fenster die vier Kanal-RMS und den daraus
  rekonstruierten Schwerpunktwinkel aus.
  Erwartet: Signal vorhanden (RMS > 0), und der Winkel **wandert monoton** in
  eine Richtung — das ist der Nachweis, dass die Rotation eine sich ändernde
  Pan4-Position erzeugt und nicht nur im Code steht.
- [ ] **Schritt 4:** Gegenprobe mit `tailOrbitRadius = 0` per OSC: Winkel
  konstant. Damit ist ausgeschlossen, dass die Wanderung aus dem Klang statt
  aus dem Orbit kommt.
- [ ] **Schritt 5:** Ergebnis in den Bericht, keine Repo-Datei.

---

## Self-Review

- **Spec-Abdeckung:** Handoff 1 (ADS) → Task 1; 2 (Attack-Defaults) → Task 1
  Tabelle; 3 (alle fünf additiv + Polyphonie) → Task 3; 4 (WebUI) → Task 4.
  Orbit-Konzept 2/4.1/4.3/5/6.2/7/8 → Task 2, Abschnitt 10 → Task 3.
- **Offen und bewusst nicht gebaut:** Pro-Tail-Orbit-Parameter,
  Tail-Auswahl/Umschaltung statt additiv, Verlagerung der drei `FreeVerb`
  hinter den Encoder.
