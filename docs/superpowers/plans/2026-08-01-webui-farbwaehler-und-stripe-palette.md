# Farbwähler, Stripe-Palette und Nachleucht-Zielfarbe — Umsetzungsplan

> **Fuer agentische Bearbeiter:** Task fuer Task, Tests nach jedem Task.

**Ziel:** Farben im Web-UI werden ausschliesslich ueber native Farbwaehler
eingestellt; die acht bisher im Java-Code verdrahteten Stripe-Farben werden
fernsteuerbar; das Nachleuchten wird ueber Zielfarbe plus ein Zerfallstempo
bedient statt ueber drei kanalweise Zerfallsraten.

**Architektur:** Die OSC-Adressen und das Datenmodell bleiben, was sie sind
(r/g/b bzw. Hue/Sat/Bright als Einzelparameter). Neu ist nur, **wie** das UI
sie einsammelt. Die Nachleucht-Umrechnung sitzt in `webui/server.py`, nicht in
`app.js` — dort ist sie ohne jsdom pruefbar, und das UI braucht sie nur beim
Senden und beim Laden, nicht in jedem Frame.

**Tech-Stack:** Java (Processing-Sketch), Python 3 (stdlib + Flask), Vanilla JS.

## Global Constraints

- Kein Push, kein Deploy, kein Merge. Nur dieser Worktree.
- `python3 -m pytest webui/test_webui.py -q` **und** `test/run.sh` muessen gruen
  bleiben.
- Keine neue Abhaengigkeit; `webui/` bleibt ohne Node/npm.
- Die drei/24 OSC-Adressen tragen weiterhin `float 0..1` je Kanal. Was an
  imPulse geht, aendert sich in Form und Wertebereich **nicht**.

---

## Vorab geklaerte Entwurfsentscheidungen

1. **Der Farbwähler ersetzt die Kanalregler, aber nicht ihre Eintraege in der
   `controls`-Map.** Wer sie ersatzlos streicht, bricht das Preset-Laden: der
   Ruecklauf laeuft ueber genau diese Map (CLAUDE.md, „buildTabs() baut ALLE
   Panels"). Loesung: `headlessControl(param)` legt ein Handle mit
   `set/get/flash` an, dessen Element **nicht** in den DOM haengt. Die Karte
   liest daraus weiterhin ihren Hex-Wert.

2. **Die acht Stripe-Farben werden 24 `RemoteControlledFloatParameter`**
   (`/net/impulse/stripeColor/<0..7>/{r,g,b}`) und **keine eigene Datei.**
   Abweichung vom Vorschlag im Auftrag, mit Grund: eine `data/stripeColors.txt`
   waere ein **dritter** Ort, an dem Stripe-Farben stehen (Code-Default, Datei,
   Preset) — und ein geladenes Preset wuerde ihr widersprechen, ohne dass das
   auffaellt. Als normale Parameter erben sie alles, was es schon gibt:
   Preset-Persistenz, `remoteSettings.txt`, Web-UI, Live-Aenderung ohne
   Neustart. Die Rueckwaertskompatibilitaet, um die es im Auftrag geht, liefert
   der Compile-Time-Default — er ist bitgleich die bisherige Tabelle.

3. **Die Defaults wandern in eine eigene, processing-freie Klasse**
   (`StripeColorDefaults.java`), damit `test/run.sh` sie pruefen kann.
   `LedNetworkTransportEffect` haengt an oscP5 und ist dort nicht uebersetzbar.

4. **Die Nachleucht-Umrechnung steht in `server.py`**, mit einem eigenen
   Endpoint `POST /api/fadeout`. `app.js` rechnet **nichts** — es kennt nur
   Zielfarbe und Tempo, also genau die zwei Dinge, die es anzeigt. Eine zweite
   Kopie der Formel in JS waeren zwei Wahrheiten.

5. **Formel** (Herleitung und Kalibrierung in Task 4):
   ```
   w_c      = MIN_WEIGHT + (1 - MIN_WEIGHT) * (ziel_c / max(ziel_r,ziel_g,ziel_b))
   fadeOut_c = baseDecay ** (1 / w_c)
   ```
   `MIN_WEIGHT = 0.05`, aus dem Auslieferungswert 0.97/0.96/0.56
   zurueckgerechnet. Grenzfaelle: `baseDecay = 1` → alle Kanaele 1 (weil
   `1**x == 1`), schwarze Zielfarbe → `max == 0` → alle Gewichte 1, also alle
   Kanaele `baseDecay` (neutraler Grauzerfall statt Division durch Null).

---

## Task 1: Farbwähler statt Kanalregler (Knoten- und Impulsfarbe)

**Dateien:**
- Aendern: `webui/server.py` (`build_colors()`, `color_addresses()`, Snapshot)
- Aendern: `webui/static/app.js` (`buildColorCard`, neue `buildRgbCard`,
  `headlessControl`)
- Aendern: `webui/static/style.css`
- Test: `webui/test_webui.py`

- [ ] Test: `build_colors()` liefert fuer den vollen Parametersatz eine
      `impulse`-Karte mit den drei Adressen `/net/impulse/color/{r,g,b}` und
      `color_addresses()` enthaelt sie, damit sie aus dem generischen
      Rendering fallen.
- [ ] Test laufen lassen — schlaegt fehl.
- [ ] `build_colors()` implementieren, in `snapshot()` einhaengen, Adressen aus
      `generic` herausnehmen.
- [ ] `app.js`: `headlessControl(param, initial)` + `buildRgbCard(...)`; in
      `buildColorCard` die `components`-Schleife durch drei `headlessControl`
      ersetzen.
- [ ] Tests gruen, commit.

## Task 2: `useRemoteCol` → `useSpecificColor` (Java + UI)

**Dateien:**
- Aendern: `LedNetworkTransportEffect.java:133,249,452,517`
- Aendern: `webui/server.py` (`ADDRESS_LABELS`)
- Aendern: `webui/static/app.js` (Zwei-Zustands-Schalter mit Klartext)

- [ ] `impulseUseRemoteCol` → `impulseUseSpecificColor`, Adresse
      `/net/impulse/color/useSpecificColor`, Kommentare nachziehen.
- [ ] `test/build.sh` (Uebersetzungsprüfung) laufen lassen.
- [ ] UI: Schalter mit den Beschriftungen „Spezifische Farbe" (1) und
      „Stripe-Farben" (0) statt „an (1)/aus (0)".
- [ ] Commit.

## Task 3: Die acht Stripe-Farben fernsteuerbar

**Dateien:**
- Erstellen: `StripeColorDefaults.java`
- Aendern: `LedNetworkTransportEffect.java`
- Erstellen: `test/StripeColorDefaultsTest.java`, eintragen in `test/run.sh`
- Aendern: `webui/server.py`, `webui/static/app.js`, `webui/test_webui.py`

- [ ] `test/StripeColorDefaultsTest.java`: acht Eintraege, exakte Kanalwerte
      der historischen Tabelle (68/0/62 … 234/147/44).
- [ ] `test/run.sh` erweitern, laufen lassen — schlaegt fehl.
- [ ] `StripeColorDefaults.java` schreiben, Test gruen.
- [ ] `LedNetworkTransportEffect`: 24 Parameter registrieren, `drawMe()` liest
      sie statt `stripeColorMapping`.
- [ ] `server.py`: `build_colors()` um `stripes` (8 Karten) erweitern.
- [ ] `app.js`: acht Farbwähler in einer Reihe, mit Hinweis, dass sie nur im
      Modus „Stripe-Farben" wirken.
- [ ] Tests gruen, commit.

## Task 4: Nachleuchten als Zielfarbe + Zerfallstempo

**Dateien:**
- Aendern: `webui/server.py` (`fade_from_target`, `fade_to_target`,
  `/api/fadeout`, Snapshot, Preset-Antwort)
- Aendern: `webui/static/app.js`, `webui/static/style.css`
- Aendern: `webui/test_webui.py`

- [ ] Tests fuer die Grenzfaelle: `baseDecay = 1` → (1,1,1) fuer jede
      Zielfarbe; schwarze Zielfarbe → alle drei gleich `baseDecay`; der
      staerkste Kanal der Zielfarbe bekommt genau `baseDecay`; Hin- und
      Rueckrechnung des Auslieferungswerts 0.97/0.96/0.56 ist stabil.
- [ ] Tests laufen lassen — schlagen fehl.
- [ ] Formel implementieren, Endpoint, Snapshot-Block.
- [ ] `app.js`: Sektion „Nachleuchten" mit Farbwähler und Tempo-Fader; die drei
      rohen Regler fallen aus dem generischen Rendering.
- [ ] Tests gruen, commit.

## Task 5: Gesamtdurchlauf

- [ ] `test/run.sh`
- [ ] `python3 -m pytest webui/test_webui.py -q`
- [ ] CLAUDE.md nachziehen (Farbwähler, Stripe-Farben, Nachleucht-Formel).
- [ ] Commit.
