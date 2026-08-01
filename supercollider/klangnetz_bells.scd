// =====================================================================
// KlangNetz / imPulse — Vierkanal-Klang: Glocken an Knoten, Drohnen für
// reisende Impulse, Pan4 (SC-Core-UGen) auf vier feste Lautsprecher
// =====================================================================
//
// KANONISCHE DATEI (2026-07-31): dies ist die EINZIGE SC-Sound-Datei fürs
// KlangNetz. Vorher gab es zwei parallele Versionen — eine im Repo und eine
// lose Kopie auf dem Windows-Laptop (`C:\Users\birk\klangnetz_bells_zoom.scd`),
// die tatsächlich lief. Das führte zu einer echten Verwechslung: eine Analyse
// der Repo-Datei ergab falsche Tonwerte, weil der Live-Task eine andere Datei
// lud. Der Windows-Task läuft jetzt direkt aus diesem Repo-Pfad.
// Details/Änderungshistorie: klangnetz_bells.README.md.
//
// ---- UMBAU Ambisonics -> Pan4 (2026-07-31, Birk vor Ort) ---------------
// Nach der erfolgreichen Azimuth-Testabnahme der Ambisonics-Kette
// (PanB2-Encoder + DecodeB2-Decoder, siehe Git-Historie dieser Datei fuer
// die vollstaendige Messdokumentation) stellte sich vor Ort heraus: die
// gewuenschte Praezision ("bei einer Note NAH an einem Lautsprecher soll
// wirklich NUR dieser Lautsprecher klingen") ist mit Ambisonics 1. Ordnung
// PRINZIPIELL nicht erreichbar -- gemessen selbst bei voller Richtwirkung
// nur ~43% Energieanteil im dominanten Kanal (Verfahrensgrenze, kein Bug,
// siehe docs/ambisonics-sharper-panning-optionen.md fuer die Optionsanalyse).
//
// Ersetzt durch SCs Core-UGen Pan4 (SIEHE ~toQuad WEITER UNTEN fuer die
// vollstaendige Begruendung + NRT-Verifikation): kein B-Format-Zwischen-
// schritt mehr, kein Decoder-Synth, direkter X/Y-Panner fuer genau 4 feste
// Lautsprecher. Verifiziert per NRT-Messung (Hermes-vServer,
// Score.recordNRT, Methodik siehe Skill supercollider-sound-design):
//   Boxposition exakt in einer Ecke -> 100% auf genau dieser Box
//   Netzmitte -> exakt 25/25/25/25% (automatisch, kein Diffus-Crossfade)
// Die alte Ambisonics-Messdokumentation (Azimut-Vorzeichen, 45-Grad-
// Offset, DecodeB2-orientation-Bug) ist NICHT mehr relevant fuer den
// aktuellen Code -- bei Bedarf per `git log`/`git show` auf einen Commit
// vor 2026-07-31 einsehbar. Die Kernlektion bleibt gueltig: MASSGEBLICH
// IST DIE MESSUNG, NICHT DIE HERLEITUNG (galt fuer Ambisonics genauso wie
// fuer die neue Pan4-Rotation+Permutation, beide wurden frisch per NRT
// verifiziert statt aus der alten Messung uebernommen).
//
// ---- Zusammenführung der zwei Entwicklungslinien (2026-07-31) --------
// Diese Datei vereint die Spatialisierungsarbeit (urspruenglich Ambisonics,
// seit UMBAU oben Pan4; Drohnen am Impulsstrom, Sound-Parameter-Layer,
// Messhilfen) mit dem Live-Tuning-Stand des Laptops (Skala, Grundton,
// Lautstärken, Sägezahn-Layer, Polyphonie-Obergrenze). Drei Punkte, an
// denen die zwei Linien dasselbe verschieden lösten, und wie entschieden
// wurde:
//
//  * KANALVERTEILUNG: die Laptop-Fassung schickte jeden Knoten per
//    `outCh = nodeId % 4` auf einen festen Hardware-Ausgang — ein
//    Platzhalter ohne Ortsbezug. Das ersetzt die Spatialisierungs-Kette
//    vollständig: der Ort kommt jetzt aus den Koordinaten in
//    /net/hitNode bzw. /net/impulse.
//  * HALL: die Laptop-Fassung hatte einen FreeVerb IN jeder Glocke
//    (mix 0.42). Ein Hall VOR dem Panning wird selbst räumlich positioniert
//    und zu einer Punktquelle verschmiert (Begründung ausführlich beim
//    \masterReverb-SynthDef). Der Hall sitzt deshalb NACH dem Panning, je
//    Hardware-Kanal getrennt. Damit der von Birk gewünschte Hallanteil
//    erhalten bleibt, ist der Default von ~reverbMix von 0.15 auf 0.35
//    angehoben — live nachstellbar über /klangnetz/param/reverbMix, ohne
//    die Datei anzufassen.
//  * SUMMEN-LIMITER: die Laptop-Fassung hatte dafür einen eigenen
//    \klangLimiter hinter einem privaten 4-Kanal-Bus. Denselben Dienst
//    tut hier der Limiter am Ende von \masterReverb (ebenfalls 0.95, je
//    Kanal) — ein zweiter Limiter in Reihe brächte nichts ausser einer
//    zweiten Stelle, an der man ihn suchen muss.
//
// Empfängt OSC von imPulse (Processing-Sketch) auf Port 8002:
//   /net/hitNode <nodeId:int>    <energy:float> <x:float> <y:float>
//   /net/impulse <impulseId:int> <x:float>      <y:float> <energy:float> <speed:float>
//   /sc/preset/load <name:string>   Sound-Preset laden (siehe ~presetLoad)
//   /sc/preset/save <name:string>   Sound-Preset schreiben (siehe ~presetSave)
// (siehe LedNetworkTransportEffect.java sendOscMessage() und
// sendImpulseStream(), Adresse in imPulse.pde:
// NetAddress oscOutput = new NetAddress("127.0.0.1", 8002))
//
// x und y sind Meter in der Draufsicht, Ursprung Netzmitte, X nach
// rechts, Y nach vorn. Grundfläche 14 × 8 m.
//
// Für jeden Node-Treffer wird ein Ton auf der eingestellten Tonleiter
// (aktuell Phrygisch ab A2, siehe ~scaleSteps) gespielt: kurzer Attack,
// langer Decay, mehrere leicht unharmonische Sinus-Teiltöne plus ein
// dezenter Sägezahn-Layer — klingt wie ein Hang-Drum-Glockenspiel. Jeder
// reisende Impuls bekommt zusätzlich eine leise gehaltene Drohne, die mit
// ihm wandert.
//
// Update-Historie des Klangs (Laptop-Linie, alle vor Ort von Birk):
//  2026-07-30: Skala D Kurd (Hang-Drum/Handpan), Grundton eine Oktave
//    tiefer (D3->D2), mehr Obertöne/Sägezahn-Anteil ("Reibung"), mehr Hall.
//  2026-07-30: Mindestlautstärke -10dB gegen zu leise Treffer.
//  2026-07-31: +6dB Gesamtlautstärke (Faktor x2), Limiter gegen
//    Übersteuerung bei vielen gleichzeitigen Treffern, Skala eine Quinte
//    höher (+7 Halbtöne, D2->A2 als Grundton).
//  2026-07-31: Lautstärke kurzzeitig -6dB, dann wieder auf +6dB zurück
//    (dritte Korrektur).
//  2026-07-31: Skala von natürlicher Moll (D Kurd) auf Phrygisch geändert
//    (kleine statt große Sekund als zweite Stufe), Grundton A2 unverändert.
//  2026-07-31: Polyphonie-Obergrenze gegen Server-Überlast (~maxPolyphony).
//  2026-07-31: Panning von Ambisonics (PanB2/DecodeB2) auf Pan4 umgebaut
//    (~panSharpness ersetzt ~directHardness), siehe UMBAU-Abschnitt oben.
//
// Setup: sclang Instanz muss auf demselben Rechner laufen wie imPulse
// (oder oscOutput in imPulse.pde entsprechend auf die SC-Rechner-IP
// zeigen, Port bleibt 8002). Kein separater sclang-Empfangsport nötig,
// SC empfängt hier direkt über einen eigenen OSC-Listener auf 8002 statt
// über den sclang-Default-Port 57120.
//
// Hinweis headless/Task-Scheduler-Betrieb (Windows): mehrere
// aufeinanderfolgende Top-Level-(...)-Blöcke in derselben Datei können
// den Parser unter `sclang -D` hängen lassen (beobachtet 2026-07-29 beim
// Testen auf dem KlangNetz-Windows-Laptop) — deshalb hier bewusst ALLES
// in einem einzigen (...)-Block. Aus demselben Grund sind die Messhilfen
// unten als Funktionen (~testChannels, ~testAzimuth, ~testSweep) abgelegt
// und nicht als eigene Top-Level-Blöcke: sie laufen headless nicht von
// selbst los und sind trotzdem mit einem Aufruf startbar.
//
// Die Messsitzung für eine neue/geaenderte Hardware-Verkabelung ist
// dagegen eine INTERAKTIVE: ~testChannels.(), ~testAzimuth.() und
// ~testSweep.() werden von Hand aufgerufen, also aus der SuperCollider-IDE
// oder aus einer sclang-REPL. Im headless-Betrieb (`sclang -D`) gibt es
// keine Eingabezeile, aus der man sie aufrufen könnte — dort läuft die
// Datei nur als Empfänger.
// =====================================================================
// OSC-STEUERBARER SOUND-PARAMETER-LAYER  (Stand 2026-07-30)
// =====================================================================
//
// Die Klangparameter sind zur Laufzeit per OSC einstellbar, unabhängig
// vom imPulse-Netz-Traffic (eigener Port, eigenes Adress-Präfix). Jeder
// Parameter klemmt eingehende Werte auf einen festen Bereich (siehe unten)
// und quittiert den Empfang mit einer postln-Zeile — das ist bewusst KEIN
// generisches Framework wie `AbstractParameter.java`/`remoteSettings.txt`
// auf der imPulse-Seite, sondern eine kleine, für genau diese Datei
// gebaute Registry (~params, ~registerParam), siehe deren Definition
// weiter unten.
//
// WEBUI: die Anbindung EXISTIERT seit dem Umbau auf die fünf Themen-Tabs —
// `webui/server.py` hält in `SC_PARAMS` eine handgepflegte Kopie dieser
// Registry und schickt sie über einen ZWEITEN OscSender an Port 8002 (der
// erste geht an imPulse auf 8001). Zwei Tests in `webui/test_webui.py`
// vergleichen Tabelle und Datei in BEIDE Richtungen, damit sie nicht
// abdriften; wer hier einen ~registerParam ergänzt, bekommt dort eine rote
// Prüfung, bis die Kopie nachgezogen ist. Das ist Absicht. Was es weiterhin
// nicht gibt, ist ein RÜCKKANAL: die im Browser angezeigten Werte sind diese
// Defaults, nicht der Live-Zustand.
//
// Alle Adressen empfangen EINEN Float als erstes Argument, Port 8002
// (~oscListenPort, derselbe wie /net/hitNode und /net/impulse):
//
//   /klangnetz/param/masterVolume   0.0 .. 1.5   Default 1.0
//     Globaler Gain NACH dem Panning (Pan4), VOR dem Limiter — sitzt
//     im neuen \masterReverb-Synth (siehe unten), der genau an dieser
//     Stelle in der Kette hängt. 1.5 statt 1.0 als Obergrenze, damit sich
//     eine bewusst leise gemischte Szene noch anheben lässt; der Limiter
//     dahinter fängt das ab, bevor es hart clippt.
//
//   /klangnetz/param/bellVolume     0.0 .. 1.5   Default 1.0
//   /klangnetz/param/droneVolume    0.0 .. 1.5   Default 0.0
//   /klangnetz/param/tailVolume     0.0 .. 1.5   Default 0.5
//     Layer-Fader je Klangschicht, multiplikativ VOR masterVolume:
//     bellVolume in \glockenBell (Node-Treffer), droneVolume in
//     \impulseDrone (reisende Impulse), tailVolume in den fuenf Bell-Tails
//     (siehe unten). Damit laesst sich das Verhaeltnis der Schichten vor Ort
//     einstellen, ohne ~maxAmp/~droneAmpScale/~tailAmpScale im Code
//     anzufassen. droneVolume wirkt SOFORT auf laufende Drohnen, bellVolume
//     und tailVolume erst auf den naechsten Ton (One-Shots, wie brightness).
//
//   /klangnetz/param/reverbMix      0.0 .. 1.0   Default 0.15
//   /klangnetz/param/reverbRoom     0.0 .. 1.0   Default 0.5
//   /klangnetz/param/reverbDamp     0.0 .. 1.0   Default 0.5
//     Hall als FreeVerb NACH dem Panning, auf den vier fertigen
//     Hardware-Kanälen (Begründung: siehe Kommentar bei \masterReverb
//     weiter unten — kurz zusammengefasst, ein Hall VOR dem Panning
//     würde selbst räumlich positioniert und zu einer Phantomquelle
//     verschmiert statt zu einem diffusen Raumklang; FreeVerb je
//     Hardware-Kanal — durch SCs Multichannel-Expansion automatisch
//     vier UNABHÄNGIGE Hallfahnen — ergibt eine echte, dekorrelierte
//     Umhüllung, wie man sie von einem realen Raum erwartet). reverbMix
//     ist der trocken/nass-Regler von FreeVerb selbst (0 = trocken, 1 =
//     nur Hall), reverbRoom die gefühlte Raumgrösse/Nachhallzeit,
//     reverbDamp die Höhendämpfung im Hallschweif (0 = hell/lang
//     nachklingend, 1 = dumpf/kurz).
//
//   /klangnetz/param/panSharpness   0.1 .. 8.0   Default 1.0
//     Schaerfe des Panning fuer Nodes/Drohnen (~toQuad): 1.0 = reines
//     Pan4-Verhalten (Referenzwert), > 1.0 = schaerfer/fokussierter
//     (Boxen jenseits der Mitte werden frueher voll erreicht), < 1.0 =
//     weicher/diffuser. Netzmitte bleibt bei JEDEM Wert exakt
//     25/25/25/25% (siehe ~toQuad-Kommentar). Wirkt nur auf NEU
//     getriggerte Toene/Drohnen, aus demselben Grund wie brightness.
//
//   /klangnetz/param/regionBiasAmount  0.0 .. 1.0   Default 0.6
//     Staerke des Klangbias nach Netzregion (vier Quadranten, siehe
//     ~regionZone/~regionBias im Code). 0 = aus, der Klang ist dann
//     bitgleich dem ohne dieses Feature. Verschiebt je Zone die Notenwahl
//     (in SKALENSTUFEN, bleibt also in Phrygisch) sowie brightness und
//     detune. Wirkt auf NEUE Toene, wie brightness/detune selbst.
//
//   /klangnetz/param/brightness     0.0 .. 2.0   Default 1.0
//     \glockenBell: Amp-Faktor auf die VIER oberen Partialtöne (Grundton
//     bleibt unverändert). 1.0 = aktueller Klang, 0 = reiner Sinuston,
//     2.0 = deutlich obertonreicher/heller. Wirkt nur auf NEU getriggerte
//     Töne (Glocken sind kurze One-Shots, kein Sinn in einem Live-.set
//     auf eine schon abklingende Hülle).
//
//   /klangnetz/param/detune         0.0 .. 1.0   Default 1.0
//     \glockenBell: wieviel von der unharmonischen Verstimmung der
//     Teiltöne (siehe ~partials) tatsächlich zum Tragen kommt. 1.0 = die
//     bisherigen, leicht schwebenden Verhältnisse (metallischer
//     Glockenklang), 0.0 = exakt ganzzahlige Verhältnisse (1:2:3:4:5,
//     harmonisch/rein, klingt eher wie eine Orgelpfeife als eine Glocke).
//     Interpoliert linear zwischen beiden Zuständen. Ebenfalls nur bei
//     NEUEN Tönen wirksam, aus demselben Grund wie brightness.
//
//   /klangnetz/param/droneLpfMult   1.0 .. 12.0  Default 6.0
//     \impulseDrone: Multiplikator auf die LPF-Grenzfrequenz (bisher fest
//     `freq*6`, jetzt `freq*droneLpfMult`). Klein = dumpfer/dunkler,
//     gross = heller/obertonreicher. Wirkt SOFORT auf alle gerade
//     laufenden Drohnen (per .set in einer Schleife über ~drones, siehe
//     ~registerParam-Aufruf unten) — anders als brightness/detune, weil
//     eine Drohne (anders als eine Glocke) über Sekunden gehalten wird
//     und ein Live-Regler an ihr hörbar Sinn ergibt.
//
//   ---- Travel-Sound (Wind-/Rauschschicht der reisenden Impulse) ----
//   Alle sechs sitzen in \impulseDrone, es gibt KEINEN zweiten Synth je
//   Impuls -- Begruendung bei den ~registerParam-Aufrufen im Code.
//
//   /klangnetz/param/travelMix       0.0 .. 1.0    Default 0.0
//     Crossfade Tondrohne <-> Koernerschicht. 0 = bisheriger Klang,
//     bitgleich. Wirkt SOFORT auf laufende Drohnen.
//   /klangnetz/param/travelRq        0.02 .. 1.0   Default 0.35
//     Koernerdauer als Anteil von 20 ms. Klein = kurze, harte Koerner
//     (sandig, einzeln hoerbar), gross = ineinanderlaufend. UMGEWIDMET
//     2026-08-01: hiess frueher Filterguete eines Bandpasses, den es
//     nicht mehr gibt. Wirkt SOFORT auf laufende Drohnen.
//   /klangnetz/param/travelGrainRatio 0.01 .. 2.0  Default 0.125
//     Koerner je Sekunde als Vielfaches von travelFreq -- die Dichte
//     traegt damit dieselbe Speed-Klasse wie die Grenzfrequenz.
//     Wirkt SOFORT auf laufende Drohnen.
//   /klangnetz/param/travelAmpScale  0.0 .. 2.0    Default 1.0
//     Trimm nur der Rauschschicht. Wirkt SOFORT.
//   /klangnetz/param/travelFreqBase       50 .. 4000    Default 400
//   /klangnetz/param/travelSpeedRef        1 .. 1500    Default 16
//   /klangnetz/param/travelOctavesPerStep  0.25 .. 3    Default 1.0
//   /klangnetz/param/travelSnap            0 / 1        Default 1
//   /klangnetz/param/travelFreqMin        20 .. 2000    Default 80
//   /klangnetz/param/travelFreqMax       200 .. 16000   Default 6000
//     Kennfrequenz der Windschicht (Low-Cut UND, ueber travelGrainRatio,
//     Koernerdichte), OKTAVBASIERT ueber die gemeldete
//     Geschwindigkeit (LEDs/s, fuenftes Argument von /net/impulse):
//       freq = travelFreqBase * 2^(log2(speed/travelSpeedRef) * OctavesPerStep)
//     Jede Verdopplung der Geschwindigkeit ist damit ein fester
//     Intervallsprung -- bei den Defaults 200/400/800/1600/3200 Hz fuer die
//     Speed-Klassen 0.5x/1x/2x/4x/8x. travelSnap rundet auf die Klasse,
//     damit der Jitter die Zuordenbarkeit nicht verschmiert. Wirken erst
//     auf NEUE Drohnen -- die Umrechnung passiert in sclang beim Anlegen
//     des Synths.
//
//   ---- Bell-Tails: fuenf additive Schweife je Node-Treffer ----------
//   Der Glockenanschlag bleibt unveraendert; ZUSAETZLICH klingen bei jedem
//   Treffer fuenf digitale Schweife mit (Glass Shimmer, Digital Whoosh,
//   FM-Glide, Granularer Zerfall, Sub-Glow -- akustisch abgenommen am
//   2026-08-01). ALLE FUENF gleichzeitig, nicht einer nach Auswahl. Ein
//   Treffer kostet damit sechs Synths statt einem; ~maxPolyphony steht
//   deshalb auf 16 statt 24.
//
//   ONE-SHOT, KEIN GATE: /net/hitNode ist ein Impuls ohne Ende-Ereignis.
//   "Sustain" ist folglich ein PLATEAU-PEGEL im festen Envelope
//   (Env([0, 1, sustain, 0], [attack, decay, release])), kein gehaltenes
//   Segment. Die Defaults decay = 0 und sustain = 1 halten die Kurve
//   formgleich mit dem abgenommenen Stand.
//
//   Je Tail fuenf Adressen, <Tail> = Shimmer | Whoosh | Fmglide |
//   Granular | Subglow:
//
//   /klangnetz/param/tail<Tail>Attack    0.001 .. 2.0
//     Einschwingzeit in s. Defaults 0.02 (Shimmer), 0.02 (Whoosh),
//     0.03 (Sub-Glow) -- diese drei starten auf Birks Wunsch sofort mit
//     voller Kraft. FM-Glide und Granular behalten ihre 0.7 s, der lange
//     Einschwinger ist dort Teil des abgenommenen Klangs.
//     NEBENWIRKUNG: dieselbe Zeit ist die Rampe, ueber die der Orbit-Radius
//     von 0 auf seinen Wert waechst (siehe ~tailOrbitOut).
//   /klangnetz/param/tail<Tail>Decay     0.0 .. 4.0     Default 0.0
//     Abfall vom Scheitel auf das Sustain-Plateau. 0 = kein Knick.
//   /klangnetz/param/tail<Tail>Sustain   0.0 .. 1.0     Default 1.0
//     Plateau-Pegel nach dem Decay.
//   /klangnetz/param/tail<Tail>Release   0.2 .. 12.0
//     Ausklingzeit in s -- Defaults 4.5 / 3.2 / 3.8 / 3.5 / 4.2. Diese Zahl
//     ist bei Whoosh, FM-Glide und Granular ZUGLEICH die Laufzeit der
//     internen Sweeps (Bandpass, Tonhoehen-Glide, Koernerdichte): der Regler
//     dehnt die ganze Geste, nicht nur ihr Ende.
//   /klangnetz/param/tail<Tail>Amp       0.0 .. 4.0     Default 1.0
//     Pegel dieser Schicht. 0 heisst: der Synth entsteht beim Treffer gar
//     nicht erst -- der Regler spart Stimmen, nicht nur Pegel. AUSNAHME
//     tailWhooshAmp mit Default 3.0: gefiltertes Rauschen traegt wenig
//     Energie und liegt gemessen rund 10 dB unter den anderen vier
//     (NRT, 2026-08-01).
//
//   ---- Tail-Orbit: Kreisbewegung um den Ausloeseort ------------------
//   Fuenf GLOBALE Regler fuer alle fuenf Tails (nicht fuenf mal fuenf).
//   Entwurf und Herleitung:
//   docs/superpowers/specs/2026-08-01-tail-synth-orbit-design.md.
//
//   /klangnetz/param/tailOrbitRadius     0.0 .. 2.0     Default 0.25
//     Radius, NORMIERT: 1.0 = Boxabstand, nicht Meter. 0 = keine Rotation
//     (der Aus-Schalter, bitgleich mit einem Tail ohne dieses Feature).
//   /klangnetz/param/tailOrbitSpeed      0.0 .. 4.0     Default 0.5
//     Umdrehungen/s BEI VOLLER HUELLKURVE. Der Gesamtwinkel haengt am
//     Integral der Huellkurve: eine volle Umdrehung ueber einen
//     4-Sekunden-Tail braucht rund 1.08.
//   /klangnetz/param/tailOrbitEnvExp     0.25 .. 4.0    Default 1.0
//   /klangnetz/param/tailOrbitDirLock    -1.0 .. 1.0    Default 0.0
//     0 = Richtung je Tail-Synth zufaellig, +-1 = erzwungen (zum Pruefen).
//   /klangnetz/param/tailOrbitMinRadius  0.0 .. 0.5     Default 0.0
//     Alle fuenf wirken auf NEUE Tails, wie brightness/detune.
// =====================================================================

(
// ---- Konfiguration ---------------------------------------------------
~oscListenPort = 8002;

// ---- Vierkanal-Ausgang -----------------------------------------------
// numOutputBusChannels wirkt erst beim BOOTEN des Servers. Läuft schon
// einer, muss er neu gestartet werden — sonst bleiben Kanal 2 und 3 stumm,
// ohne Fehlermeldung.
s.options.numOutputBusChannels = 4;

// GEMESSEN 2026-07-31 (Show-Rechner, Birk vor Ort, per Hardware-Kanaltest
// ohne Ambisonics-Encoder/Decoder isoliert): ohne explizite Device-Wahl
// bootet scsynth per Windows-Default-Geraeteauswahl auf den MME-Treiber
// ("MME : Line (ZOOM AMS-24 Audio)"). Symptom: Server bootet fehlerfrei,
// alle vier Kanaele erscheinen im Boot-Log, Glockentoene sind klar
// hoerbar — aber alle vier Testpositionen (vorn/rechts/hinten/links)
// kamen aus nur ZWEI Boxen (Kanal 0/2 -> eine Box, Kanal 1/3 -> die
// andere). Root cause: MME dupliziert/faltet die 4 Kanaele auf ein
// 2-Kanal-Ausgabegeraet, statt echte 4-Kanal-Hardware anzusprechen. Nach
// Umstellung auf den ASIO-Treiber (zweiter Eintrag im selben Boot-Log)
// wurden alle vier Kanaele eindeutig unterschiedlichen Boxen zugeordnet:
//   "ASIO : ZOOM AMS-24 ASIO Driver   (device #18 with 2 ins 4 outs)"
// Deshalb hier fest auf ASIO verdrahtet statt der Windows-Default-Wahl zu
// vertrauen. Bricht das Booten mit "device not found", exakten Namen aus
// dem eigenen Boot-Log ("Device options:") neu eintragen — der Name ist
// vom genauen Windows-Treiber-String abhaengig, kann bei Interface-
// Wechsel oder Treiber-Update abweichen.
s.options.device = "ASIO : ZOOM AMS-24 ASIO Driver";
// Das ZOOM AMS-24 meldet sich als "2 ins 4 outs" — die Eingänge werden nicht
// gebraucht, aber explizit gesetzt, damit die Kanalzahl beim Booten nicht von
// einer Treiber-Vorgabe abhängt (aus der Laptop-Fassung übernommen).
s.options.numInputBusChannels = 2;

// Halbe Diagonale der Grundfläche 14 × 8 m. Nur noch fuer ~testSweep
// (Umlauftest) als Radius des Testkreises genutzt -- die eigentliche
// Normierung fuer ~toQuad macht ~maxX/~maxY (siehe dort), weil Pan4 pro
// Achse eine eigene Skalierung braucht (Rechteck, nicht Quadrat).
~maxRadiusM = 8.062;

// Lautsprecher auf den SEITENMITTEN, nicht in den Ecken:
//   (0, +4) vorn   (+7, 0) rechts   (0, -4) hinten   (-7, 0) links
// Die Zuordnung zu den Hardware-Kanälen 0..3 macht die Permutation in
// \masterReverb und ist mit ~testChannels/~testAzimuth zu messen, nicht
// hier zu behaupten.

// Obergrenze gleichzeitiger Drohnen, als Netz unter dem oscMaxCount der
// Processing-Seite: kommt der Strom aus einer falsch konfigurierten Quelle,
// soll der Klangrechner nicht in die Knie gehen.
~droneLimit = 32;
// Ohne Meldung für diese Zeit wird eine Drohne freigegeben. Bei 10 Hz
// Melderate also nach vier ausgefallenen Meldungen. Das ist der EINZIGE
// Freigabemechanismus — Processing schickt kein Todes-Signal, weil der Strom
// durch oscMaxCount ohnehin lückenhaft ist.
~droneTimeout = 0.4;

// ---- Ehemals "die zwei gemessenen Werte" (~azimuthSign/~azimuthOffset)
// UND ~decoderOrientation ENTFALLEN KOMPLETT (UMGEBAUT 2026-07-31) --------
// Waren Ambisonics-Encoder/Decoder-Parameter (PanB2-Azimut-Vorzeichen/
// -Offset, DecodeB2-orientation). Mit dem Umbau auf Pan4 (siehe ~toQuad
// weiter unten) gibt es keinen Azimut mehr -- die frueher per
// ~azimuthOffset=-0.25 gemessene 45-Grad-Drehung UND die Hardware-Kabel-
// Permutation sind jetzt als FESTE Formel in ~toQuad (Rotation) bzw. als
// feste Kanal-Reihenfolge in \masterReverb (Permutation, siehe dort)
// eingerechnet -- beide wurden nach dem Umbau per NRT-Messung NEU
// verifiziert (nicht aus den alten Ambisonics-Werten uebernommen, weil
// Pan4 eine andere Kanalkonvention hat als DecodeB2, siehe Skill
// supercollider-sound-design).


// Phrygisch (Halbtonabstaende von der Grundnote): kleine Sekund statt
// grosser Sekund als zweite Stufe -- der markanteste Unterschied zur
// vorherigen natuerlichen Moll (D Kurd). Birk vor Ort 2026-07-31.
~scaleSteps = [0, 1, 3, 5, 7, 8, 10];

// Grundton A2 (MIDI 45), Birk vor Ort 2026-07-31 -- historisch C4 (60) in der
// pentatonischen Fassung, dann D3/D2, dann eine Quinte hoeher auf A2.
~rootMidiNote = 45;
~numOctaves = 3;
~notesPerOctaveSet = ~scaleSteps.size * ~numOctaves;

// Lautstärke-Mapping: eingehende "energy" (aus imPulse, kann > 1 sein, weil
// jeder Zweig an einer Kreuzung die volle Energie des Elternimpulses erbt,
// siehe CLAUDE.md) auf einen sinnvollen Amp-Bereich clampen.
//
// Mindestlautstärke deutlich angehoben (Birk 2026-07-30): manche Node-Treffer
// kamen mit so niedriger "energy" rein, dass sie unterhalb der Hoerschwelle
// landeten. -10dB gegenueber dem urspruenglichen Pegel (0.22/0.55), linear
// *0.316228, DANACH +6dB (Faktor x2, Birk vor Ort 2026-07-31). Kurzzeitig auf
// -6dB reduziert, dann auf ausdruecklichen Wunsch wieder zurueck auf +6dB
// (dritte Korrektur: "lass die Lautstaerke wie sie ist, mit den +6dB").
~minAmp = 0.13914;
~maxAmp = 0.34786;

// Polyphonie-Obergrenze (Birk 2026-07-31, nach Live-Ausfall): bei sehr hoher
// Trefferrate (viele Ambient-Spawns + hohe Speed gleichzeitig) kann die
// interne scsynth-Befehlswarteschlange ueberlaufen ("command FIFO full" im
// Log) -- der Server verwirft dann die meisten neuen Synth-Erzeugungen und es
// kommt kein Sound mehr raus, OHNE dass der Prozess abstuerzt (schwer zu
// erkennen: sclang/scsynth laufen weiter). ~maxPolyphony deckelt die Anzahl
// gleichzeitig aktiver GLOCKEN hart: kommt eine neue dazu und das Limit ist
// erreicht, wird die AELTESTE per Voice-Stealing sofort abgewuergt, bevor die
// neue startet. Die Drohnen haben ihren eigenen Deckel (~droneLimit oben) --
// zwei getrennte Grenzen, weil die zwei Stimmenarten aus verschiedenen
// Quellen kommen und sich nicht gegenseitig verdraengen sollen.
//
// VON 24 AUF 16 GESENKT (2026-08-01, mit den Bell-Tails): ein Node-Treffer
// erzeugt jetzt SECHS Synths statt einem -- die Glocke plus fuenf additive
// Tails (siehe ~tailDefs). Bei 24 liefen an der Grenze 144 Stimmen statt 24,
// also genau die Last, gegen die dieser Deckel nach dem Live-Ausfall
// eingezogen wurde. 16 sind 96 Stimmen; die Zahl selbst ist konservativ
// gewaehlt und kein Messergebnis vom Geraet.
//
// Was gemessen IST (Linux-vServer, jack-Dummy, OHNE Echtzeitpriorisierung,
// 2026-08-01, 40 Treffer mit 10/s): ohne Tails 5 % Server-CPU, mit allen
// fuenf Tails 65 %, davon rund zwei Drittel die drei FreeVerbs in den Tails
// (ohne sie 23 %). Die absoluten Zahlen sagen fuer den Windows-Laptop nichts,
// das VERHAELTNIS schon: diese Schicht ist rund eine Groessenordnung teurer
// als die Glocke allein. Wer den Deckel wieder anhebt, misst vorher am
// Geraet, statt zu schaetzen. Die Buchfuehrung selbst ist geprueft: nach dem
// Ausklang eines solchen Dauerfeuers steht ~activeBells wieder auf 0 und auf
// dem Server laeuft nur noch \masterReverb.
//
// ~activeBells haelt seither nicht mehr einzelne Synths, sondern je Treffer
// ein ARRAY [glocke, tail1..tail5]. Beim Voice-Stealing wird der ganze
// Eintrag freigegeben. Ohne das ueberlebte der Tail seine gestohlene Glocke:
// er rotierte weiter, ohne dass ihn jemand kennt oder zaehlt -- der
// unangenehme Fall, weil er still waechst.
~maxPolyphony = 16;
~activeBells = List.new;

// Amp einer Drohne = geclampte Energie mal diesem Faktor. Deutlich leiser als
// eine Glocke: bis zu ~droneLimit Drohnen klingen gleichzeitig, eine Glocke
// steht meist allein.
~droneAmpScale = 0.06;

// Schaerfe des Panning fuer Nodes/Drohnen (Nachfolger von ~directHardness,
// UMGEBAUT 2026-07-31 von Ambisonics/PanB2+DecodeB2 auf Pan4, siehe
// ~toQuad unten fuer die Begruendung). 1.0 = reines Pan4-Verhalten
// (Referenzwert, per NRT verifiziert), > 1.0 = schaerfer/fokussierter,
// < 1.0 = weicher/diffuser. Wirkt ueber eine Radius-Verzerrung VOR Pan4
// (r' = r^(1/panSharpness)) -- die Mitte bleibt bei JEDEM Wert exakt
// 25/25/25/25% auf alle vier Boxen, weil 0^x = 0 fuer jeden Exponenten.
~panSharpness = 1.0;

// ---- Klangbias nach Netzregion ----------------------------------------
// Ziel: ein Knoten soll nicht nur rhythmisch, sondern auch klanglich
// verraten, aus welchem Teil des Netzes er kommt.
//
// ZONIERUNG: vier Quadranten, Zone = (x>=0) + 2*(y>=0). Der Grund ist die
// Lautsprecher-Geometrie: die vier Boxen stehen auf den SEITENMITTEN
// (0,+4) (+7,0) (0,-4) (-7,0), jeder Quadrant liegt also genau zwischen
// zwei Boxen und hat eine eindeutige Richtung im Raum. Ortung und
// Klangfarbe stuetzen sich damit gegenseitig -- man hoert "vorne rechts"
// UND "die helle Farbe", und beides meint dieselbe Netzregion.
//
// Radiale Ringe (Zentrum/Rand) waeren die naheliegende Alternative und
// scheiden genau daran aus: sie korrelieren mit keiner
// Lautsprecherrichtung, und in der Mitte pannt ~toQuad ohnehin auf alle
// vier Boxen gleich (25/25/25/25%) -- die Zone mit dem eigensten Charakter
// laege dort, wo die Ortung am schwaechsten ist.
~regionZone = { |px, py|
    var xi = if (px >= 0) { 1 } { 0 };
    var yi = if (py >= 0) { 1 } { 0 };
    xi + (2 * yi)
};

// Bias je Zone. Der Notenoffset zaehlt in SKALENSTUFEN, nicht Halbtoenen:
// er wird auf noteIndex addiert, bevor ~scaleSteps nachgeschlagen wird. Ein
// Halbtonoffset erzeugte Toene ausserhalb von Phrygisch und zerstoerte den
// vor Ort gewaehlten Modus.
//
// brightness und detune sind die vorhandenen Timbre-Regler (siehe
// ~registerParam unten): brightness skaliert die oberen vier Teiltoene
// (hell/dumpf), detune blendet zwischen unharmonischen und rein
// harmonischen Verhaeltnissen (metallisch/orgelartig). Die in aelteren
// Notizen genannten ~tilt/~decayScale gibt es in dieser Datei nicht.
//
//   Zone 0 = hinten links   tief,   dumpf,     metallisch
//   Zone 1 = hinten rechts  mitte,  neutral,   harmonischer
//   Zone 2 = vorn links     hoeher, heller,    metallisch
//   Zone 3 = vorn rechts    hoch,   brillant,  fast rein harmonisch
~regionNoteOffsets = [-2, 0, 2, 4];
~regionBrightness  = [0.65, 1.0, 1.35, 1.7];
~regionDetune      = [1.0, 0.7, 1.0, 0.4];

// Liefert den wirksamen Bias, linear zwischen neutral und vollem Zonenwert
// interpoliert. Bei ~regionBiasAmount = 0 ist das Ergebnis exakt neutral,
// der Klang also bitgleich dem ohne dieses Feature.
~regionBias = { |px, py|
    var zone = ~regionZone.(px, py);
    var amt = ~regionBiasAmount ? 0;
    (
        zone: zone,
        noteOffset: (~regionNoteOffsets[zone] * amt).round.asInteger,
        brightness: 1.0 + ((~regionBrightness[zone] - 1.0) * amt),
        detune: 1.0 + ((~regionDetune[zone] - 1.0) * amt)
    )
};

// ---- OSC-Sound-Parameter-Registry --------------------------------------
// Kleine, für DIESE Datei gebaute Registry — kein Anspruch auf
// Wiederverwendbarkeit in anderen Patches. Jeder Eintrag: Environment-
// Variable, Default, Range, optionaler onSet-Callback (für Parameter, die
// SOFORT auf laufende Synths wirken sollen statt nur beim nächsten
// Trigger, siehe droneLpfMult). Der eigentliche Wert liegt als einfache
// Environment-Variable (~masterVolume etc.) — das reicht hier, weil die
// SynthDefs unten entweder (a) den Wert bei jedem NEUEN Synth als
// Start-Argument lesen (brightness, detune) oder (b) ihn über einen
// eigenen, dauerhaft laufenden Effekt-Synth als Control-Bus-Wert
// bekommen (masterVolume, reverb*, siehe \masterReverb). Ein
// Control-Bus für ALLE Parameter wäre hier Overhead ohne Nutzen.
~params = IdentityDictionary.new;

~registerParam = { |name, default, lo, hi, onSet|
    ~params[name] = (default: default, lo: lo, hi: hi, onSet: onSet);
    // Environment-Variable mit Default vorbelegen, BEVOR OSC etwas
    // schickt — sonst lesen Synths vor der ersten Nachricht nil.
    currentEnvironment[name] = default;
};

~registerParam.(\masterVolume, 1.0, 0.0, 1.5, nil);

// ---- Zwei Layer-Fader unter dem globalen Show-Fader --------------------
// masterVolume bleibt der GLOBALE Fader (sitzt in \masterReverb, nach dem
// Panning, vor dem Limiter). Diese zwei sitzen davor, je Klangschicht, und
// wirken multiplikativ: eine Glocke geht mit bellVolume * masterVolume
// heraus, eine Drohne mit droneVolume * masterVolume. Default 1.0 --
// unveraendertes Verhalten.
//
// Warum getrennt: Glocken (Node-Treffer) und Drohnen (reisende Impulse) sind
// zwei voellig verschiedene Klangrollen mit voellig verschiedenen Pegeln
// (~maxAmp 0.35 gegen ~droneAmpScale 0.06). Das Verhaeltnis der zwei
// zueinander ist eine gestalterische Entscheidung, die man vor Ort trifft --
// bisher ging das nur ueber ~maxAmp/~droneAmpScale im Code.
//
// bellVolume bekommt bewusst KEINEN onSet-Callback und KEIN Lag: eine Glocke
// ist ein One-Shot mit doneAction 2, der Wert wird als Start-Argument
// gelesen (wie brightness/detune, siehe deren Kommentar bei \glockenBell).
// Ein Lag waere hier sogar schaedlich -- er wuerde die Lautstaerke ueber die
// Lag-Zeit hochfahren und damit genau den Anschlag weichzeichnen, der den
// Glockenklang ausmacht.
~registerParam.(\bellVolume, 1.0, 0.0, 1.5, nil);
// droneVolume dagegen MIT Callback und Lag: eine Drohne wird ueber Sekunden
// gehalten, ein Regler daran soll sofort und ohne Knacken wirken -- dieselbe
// Ueberlegung wie bei droneLpfMult und travelMix.
// Default 0.0 (Birk, 2026-08-01, Live-Betrieb): der Travel-Sound-Layer
// (granulare Windschicht, siehe travelMix/travelGrainRatio) ist ein neues,
// noch nicht abgenommenes Klangelement -- soll beim Sketch-Start NICHT
// automatisch hoerbar sein, sondern bewusst live zugeschaltet werden. Die
// Drohnen-Tonschicht selbst (die bestehende, unveraenderte Tondrohne, siehe
// \\impulseDrone tone-Variable) klingt bei droneVolume=0 komplett stumm --
// das betrifft also die GESAMTE Drohnen-Stimme, nicht nur die Windschicht.
// Falls die bisherige Tondrohne (travelMix=0-Verhalten) unabhaengig vom
// neuen Wind-Feature weiter hoerbar sein soll, droneVolume separat wieder
// hochziehen -- als Startpunkt fuer den ersten Live-Test bewusst beides aus.
~registerParam.(\droneVolume, 0.0, 0.0, 1.5, { |val|
    ~drones !? { |d| d.keysValuesDo({ |id, entry|
        entry[\synth].set(\droneVolume, val);
    }) };
});
// Default 0.35 statt 0.15: die Laptop-Fassung hatte einen FreeVerb mit
// mix 0.42 in jeder Glocke ("mehr Hall", Birk 2026-07-30). Der Hall sitzt
// jetzt nach dem Decoder (Begruendung bei \masterReverb), der Default haelt
// den gewohnten Hallanteil -- live nachstellbar per OSC.
~registerParam.(\reverbMix,    0.35, 0.0, 1.0, nil);
~registerParam.(\reverbRoom,   0.5,  0.0, 1.0, nil);
~registerParam.(\reverbDamp,   0.5,  0.0, 1.0, nil);
~registerParam.(\brightness,   1.0,  0.0, 2.0, nil);
~registerParam.(\detune,       1.0,  0.0, 1.0, nil);
// droneLpfMult wirkt auf alle GERADE LAUFENDEN Drohnen, nicht nur auf
// künftige — deshalb der onSet-Callback, der über ~drones iteriert.
~registerParam.(\droneLpfMult, 6.0, 1.0, 12.0, { |val|
    ~drones !? { |d| d.keysValuesDo({ |id, entry|
        entry[\synth].set(\lpfMult, val);
    }) };
});
// panSharpness wirkt nur auf NEU gestartete Synths (das Encoder-Lambda
// ~toQuad liest die Environment-Variable bei jedem Aufruf neu, aber ein
// bereits laufender Drohnen-Synth hat den Wert schon in seinen SC-Graphen
// eingerechnet) -- kein onSet-Callback noetig/moeglich, wirkt wie
// brightness/detune erst beim naechsten Treffer/neuer Drohne. Range 0.1..8:
// unter 0.1 wird die Radius-Verzerrung numerisch instabil (r^10 bei r nahe
// 0), ueber 8 ist am realen Setup kaum noch ein Unterschied hoerbar
// (Ecke ist ohnehin schon bei jedem Wert 100% scharf, siehe ~toQuad).
~registerParam.(\panSharpness, 1.0, 0.1, 8.0, nil);

// Staerke des Regionen-Klangbias (siehe ~regionBias oben). 0 = aus, der
// Klang ist dann bitgleich dem ohne dieses Feature; 1 = voller Zonenwert.
//
// Ein eigener enabled-Schalter entfaellt bewusst: amount = 0 IST aus, und
// ein stufenloser Regler ist live wertvoller als ein harter Schalter, weil
// sich der Effekt einblenden laesst statt zu springen. Default 0.6 -- der
// Bias ist rein additiv zur bestehenden Klanglogik und kann nichts stumm
// schalten.
//
// Wirkt auf NEUE Toene: brightness/detune sind Start-Argumente der
// Glocken-SynthDef (siehe dort), ein spaeteres .set kaeme nie mehr an.
~registerParam.(\regionBiasAmount, 0.6, 0.0, 1.0, nil);

// ---- Travel-Sound: Wind-/Rauschschicht der reisenden Impulse ----------
// Setzt auf dem VORHANDENEN gedrosselten Positionsstrom /net/impulse auf
// und wird eine zweite Klangschicht INNERHALB von \impulseDrone -- kein
// zweiter Synth je Impuls. Begruendung: \impulseDrone ist schon ein Synth
// pro Impuls-ID mit Position, Huellkurve, Timeout und ~droneLimit; ein
// zweiter braeuchte ein zweites Dictionary, einen zweiten Reaper und ein
// zweites Limit und verdoppelte die Stimmenzahl -- fuer ein Feature, dessen
// Ziel es ist, Klangbrei zu VERMEIDEN.
//
// Der geforderte Lebenszyklus (neue Stimme bei jedem Split) ergibt sich
// dadurch ohne eine Zeile Code: ein Split-Kind ist ein neuer Impuls mit
// neuer id, bekommt also einen neuen Eintrag und einen neuen Synth, und die
// Stimme des Elternimpulses laeuft nach ~droneTimeout aus.
//
// Kein eigener Throttle auf der Processing-Seite: oscMaxCount bestimmt, was
// ueberhaupt ueber den Draht geht, und der Deckel, der wirklich gebraucht
// wird, sitzt dort, wo die Rechenlast entsteht -- ~droneLimit, direkt
// darunter.
//
// travelMix ist Schalter und Staerkeregler in einem. Default 0 = reine
// Tondrohne wie bisher, bitgleich.
~registerParam.(\travelMix, 0.0, 0.0, 1.0, { |val|
    ~drones !? { |d| d.keysValuesDo({ |id, entry|
        entry[\synth].set(\travelMix, val);
    }) };
});
// Koernerdauer der granularen Windschicht, als Anteil von 20 ms.
//
// UMGEWIDMET 2026-08-01 (Birk nach mehreren Hoerproben): hiess frueher
// "Bandbreite/Filterguete" und war das rq-Argument eines BPF. Die
// Windschicht ist jetzt granular (siehe \impulseDrone), einen Bandpass gibt
// es nicht mehr -- der Regler steuert stattdessen, wie lang ein einzelnes
// Rauschkorn klingt. Name und Registrierung bleiben, damit vorhandene
// Presets und die Web-UI-Tabelle gueltig bleiben.
//
// Klein = kurze, harte Koerner (sandig, kleinteilig, einzeln hoerbar),
// gross = lange, ineinanderlaufende Koerner (wieder eher ein Rauschteppich).
// Wirkt wie travelMix sofort auf laufende Drohnen -- eine Drohne wird ueber
// Sekunden gehalten, ein Live-Regler daran ergibt hoerbar Sinn.
~registerParam.(\travelRq, 0.35, 0.02, 1.0, { |val|
    ~drones !? { |d| d.keysValuesDo({ |id, entry|
        entry[\synth].set(\travelRq, val);
    }) };
});
// Koerner je Sekunde als Vielfaches von travelFreq.
//
// Die Dichte haengt bewusst an travelFreq, nicht an einem eigenen absoluten
// Wert: travelFreq traegt die Speed-Klasse (siehe unten), und damit wird die
// Klasse ZWEIFACH hoerbar -- ein schnellerer Impuls rieselt dichter UND
// heller. Zwei gleichgerichtete Merkmale sind deutlich leichter zuzuordnen
// als eines.
//
// Default 0.125 = ein Korn je acht Schwingungen der Grenzfrequenz. Mit den
// Klassen-Frequenzen (200/400/800/1600/3200 Hz) ergibt das
// 25/50/100/200/400 Koerner je Sekunde: von einzeln abzaehlbaren Tropfen bis
// zu dichtem Rieseln. Der Wert ist ein Hoer-Kompromiss und deshalb ein
// Regler, keine Konstante im Graphen.
~registerParam.(\travelGrainRatio, 0.125, 0.01, 2.0, { |val|
    ~drones !? { |d| d.keysValuesDo({ |id, entry|
        entry[\synth].set(\travelGrainRatio, val);
    }) };
});
// Trimm NUR der Rauschschicht. Es gibt ihn, weil Rauschen bei gleichem
// Pegel lauter wirkt als ein Ton und der Crossfade sonst einen Sprung
// machte.
~registerParam.(\travelAmpScale, 1.0, 0.0, 2.0, { |val|
    ~drones !? { |d| d.keysValuesDo({ |id, entry|
        entry[\synth].set(\travelAmpScale, val);
    }) };
});
// Speed -> Kennfrequenz der Windschicht, OKTAVBASIERT statt linear.
//
// Diese eine Zahl steuert BEIDE hoerbaren Merkmale der Windschicht: die
// Grenzfrequenz des Low-Cut UND (ueber travelGrainRatio) die Koernerdichte.
// Sie heisst weiter travelFreq, weil sie primaer eine Frequenz ist.
//
// Linear (die erste Fassung) lagen die Speed-Klassen 1x und 2x dicht
// beieinander -- genau der "sanfte Gradient", der die Klassen NICHT
// unterscheidbar macht. Oktavbasiert ist jede Verdopplung der
// Geschwindigkeit ein fester Intervallsprung:
//
//   octaves = log2(speed / travelSpeedRef)
//   freq    = travelFreqBase * 2^(octaves * travelOctavesPerStep)
//
// Mit den Defaults unten (Basis 400 Hz bei Speed 16):
//   0.5x -> 200 Hz   1x -> 400   2x -> 800   4x -> 1600   8x -> 3200
// und damit zugleich 25/50/100/200/400 Koerner je Sekunde.
// Das ist unverwechselbar, und genau das ist der Zweck: beim Hoeren soll
// klar sein, welcher Speed-Klasse (siehe SpeedQuantizer auf der
// Processing-Seite) ein Impuls angehoert.
//
// Alle sechs wirken erst auf NEUE Drohnen: die Umrechnung passiert in
// sclang beim Anlegen des Synths (siehe OSCdef \impulseStream), nicht im
// SC-Graphen.
~registerParam.(\travelFreqBase, 400.0, 50.0, 4000.0, nil);
// Speed, die auf travelFreqBase abgebildet wird. 16 ist der Arbeitspunkt der
// Installation (/net/impulse/speed) und damit die 1x-Klasse.
~registerParam.(\travelSpeedRef, 16.0, 1.0, 1500.0, nil);
// Oktaven je Verdopplung der Geschwindigkeit. 1.0 = eine Oktave, also
// "doppelt so schnell klingt eine Oktave hoeher". Groesser = noch
// deutlicher getrennt, kleiner = subtiler.
~registerParam.(\travelOctavesPerStep, 1.0, 0.25, 3.0, nil);
// Rasterung auf die Speed-KLASSE (1 = an). Rundet den Oktavabstand vor der
// Umrechnung auf eine ganze Zahl, damit jeder Impuls derselben Klasse auf
// exakt derselben Tonhoehe zischt.
//
// Ohne die Rasterung verschmieren die Klassen, sobald
// /net/impulse/speedQuantize/jitter oder splitSpeedJitter > 0 stehen -- die
// Zuordenbarkeit haenge dann daran, wie hoch der Jitter gerade steht.
//
// GRENZE, die man kennen muss: die Rundung greift auf halbe Oktaven, also
// bis zu einem Jitter-Faktor von 2^0.5 = 1.41 nach oben und 2^-0.5 = 0.71
// nach unten. Ein Jitter-Regler ueber etwa 0.29 laesst einzelne Impulse
// deshalb in die NACHBARKLASSE rutschen (0.7 * 1x liegt naeher an 0.5x als
// an 1x). Das ist kein Fehler, sondern ehrlich -- der Impuls reist dann ja
// wirklich mit dieser Geschwindigkeit --, aber wer maximale
// Zuordenbarkeit will, laesst /net/impulse/speedQuantize/jitter unter 0.29.
~registerParam.(\travelSnap, 1.0, 0.0, 1.0, nil);
// Harte Grenzen, damit ein extremer Regler nichts Unhoerbares oder
// Schmerzhaftes erzeugt. Weit genug fuer 0.5x .. 8x bei den Defaults.
~registerParam.(\travelFreqMin,   80.0,  20.0,  2000.0, nil);
~registerParam.(\travelFreqMax, 6000.0, 200.0, 16000.0, nil);

// ---- Bell-Tails: fuenf additive Schweife je Node-Treffer ---------------
// Der Glockenanschlag (\glockenBell) bleibt unveraendert; ZUSAETZLICH klingen
// bei JEDEM Treffer fuenf digitale Schweife mit ("ein Wusch", Birk). Die
// Klangerzeugung stammt 1:1 aus der abgenommenen Hoerprobe (v3, 2026-08-01):
// Glass Shimmer, Digital Whoosh, FM-Glide, Granularer Zerfall, Sub-Glow.
//
// ALLE FUENF GLEICHZEITIG, nicht einer nach Auswahl -- das ist die
// Entscheidung, nicht ein Zwischenstand. Wer einen davon nicht will, zieht
// seinen Amp-Regler auf 0; das ist bitgleich mit "nicht getriggert", nur ohne
// Neustart. Deshalb gibt es auch keinen Auswahl-Parameter.
//
// ONE-SHOT, KEIN GATE (Entscheidung 2026-08-01): ein Node-Treffer hat kein
// Ende-Ereignis -- /net/hitNode ist ein Impuls, kein Halten. Ein echtes Gate
// wie bei \impulseDrone braeuchte eine Freigabelogik und einen Reaper; die hat
// die Drohne nur, weil /net/impulse das AUSBLEIBEN eines Impulses meldet. Fuer
// Treffer gibt es diese Meldung nicht, ein gehaltener Tail liefe also entweder
// ewig oder an einem erfundenen Timeout. "Sustain" ist hier folglich ein
// PLATEAU-PEGEL im festen Envelope, kein gehaltenes Segment:
//   Env([0, 1, sustain, 0], [attack, decay, release])
//
// Defaults decay = 0 und sustain = 1 machen diese Kurve FORMGLEICH mit dem
// abgenommenen Stand (dort Env([0,1,0],[attack,release])) -- der ADS-Regler
// ist ein Werkzeug, keine Klangaenderung hinter Birks Ruecken. Wer Decay
// aufdreht, bekommt den Knick zum Plateau dazu.
//
// ATTACK-DEFAULTS: Shimmer, Whoosh und Sub-Glow starten sofort (0.02-0.03 s
// statt 0.6-0.9 s im Hoerprobenstand) -- Birks Rueckmeldung: "soll nicht so'n
// langes Attack-Intro haben, soll sofort mit voller Power losgehen und dann
// nur noch abschwellen." FM-Glide und Granular waren nicht Teil dieser
// Rueckmeldung und behalten ihre 0.7 s; der Parameter existiert fuer alle
// fuenf gleichermassen.
//
// RELEASE ist die Zeit, die im Hoerprobenstand "dur" hiess: sie ist nicht nur
// die Ausklingdauer, sondern auch die Laufzeit der internen Sweeps (Whoosh-
// Bandpass, FM-Glide-Tonhoehe, Koernerdichte des Granulars). Ein Regler daran
// dehnt also die ganze Geste, nicht nur ihr Ende -- gewollt, und der Grund,
// warum Release ueberhaupt ein Parameter ist und nicht eine Konstante je
// SynthDef.
~tailDefs = [
    [\tail1_shimmer,  "tailShimmer"],
    [\tail2_whoosh,   "tailWhoosh"],
    [\tail3_fmglide,  "tailFmglide"],
    [\tail4_granular, "tailGranular"],
    [\tail5_subglow,  "tailSubglow"]
];

// Verhaeltnis Tail-Amp zu Bell-Amp aus dem abgenommenen Render (dort 0.18 zu
// 0.3). Konstante, kein Parameter: die Balance ZWISCHEN den Schichten macht
// tailVolume, die Balance INNERHALB der Tails die fuenf *Amp-Regler -- ein
// dritter Regler mit derselben Bedeutung waere eine Zahl zum Suchen.
~tailAmpScale = 0.6;

// Layer-Fader der gesamten Tail-Schicht, neben bellVolume und droneVolume.
// Default 0.5 und nicht 1.0: fuenf unkorrelierte Schichten addieren sich in
// der LEISTUNG, nicht in der Amplitude (~sqrt(5) = 2.24-facher Einzelpegel).
// Bei 1.0 waere der Schweif lauter als der Anschlag, den er begleiten soll.
// 0 ist ein echter Aus-Schalter: ein Tail, dessen wirksamer Pegel 0 waere,
// wird beim Treffer gar nicht erst erzeugt (siehe /net/hitNode-Handler) --
// das spart Stimmen, nicht nur Pegel. Dasselbe gilt je Tail ueber dessen
// Amp-Regler.
~registerParam.(\tailVolume, 0.5, 0.0, 1.5, nil);

// Je Tail funf Regler: Attack, Decay, Sustain, Release, Amp. Kein
// onSet-Callback -- ein Tail ist ein One-Shot mit doneAction 2, die Werte sind
// Start-Argumente und wirken auf den NAECHSTEN Treffer (wie brightness/detune
// und bellVolume, siehe deren Begruendung oben). Bei der Trefferrate der
// Installation ist das praktisch sofort.
~registerParam.(\tailShimmerAttack,   0.02, 0.001, 2.0,  nil);
~registerParam.(\tailShimmerDecay,    0.0,  0.0,   4.0,  nil);
~registerParam.(\tailShimmerSustain,  1.0,  0.0,   1.0,  nil);
~registerParam.(\tailShimmerRelease,  4.5,  0.2,  12.0,  nil);
~registerParam.(\tailShimmerAmp,      1.0,  0.0,   4.0,  nil);

~registerParam.(\tailWhooshAttack,    0.02, 0.001, 2.0,  nil);
~registerParam.(\tailWhooshDecay,     0.0,  0.0,   4.0,  nil);
~registerParam.(\tailWhooshSustain,   1.0,  0.0,   1.0,  nil);
~registerParam.(\tailWhooshRelease,   3.2,  0.2,  12.0,  nil);
// Whoosh mit Default 3.0 statt 1.0, und die Amp-Regler reichen deshalb bis 4:
// die fuenf Tails wurden EINZELN abgenommen, nie gegeneinander. In der
// Mischung liegt der Whoosh gemessen rund 10 dB unter den anderen (NRT,
// 2026-08-01, gleiche Anregung, kein Hall: RMS 0.0006 gegen 0.0061 beim
// Shimmer, Spitze 0.017 gegen 0.058) -- bandbegrenztes Rauschen mit rq 0.15
// traegt schlicht wenig Energie. Bei 1.0 waere er zwar getriggert, aber nicht
// Teil der Schichtung. Die Zahl ist eine Messung, keine Schaetzung, und mit
// einem Regler zurueckzunehmen.
~registerParam.(\tailWhooshAmp,       3.0,  0.0,   4.0,  nil);

// FM-Glide und Granular: Attack bewusst bei 0.7 s (keine Rueckmeldung dazu,
// siehe Block oben) -- der langsame Einschwinger ist bei diesen zweien Teil
// des abgenommenen Klangs.
~registerParam.(\tailFmglideAttack,   0.7,  0.001, 2.0,  nil);
~registerParam.(\tailFmglideDecay,    0.0,  0.0,   4.0,  nil);
~registerParam.(\tailFmglideSustain,  1.0,  0.0,   1.0,  nil);
~registerParam.(\tailFmglideRelease,  3.8,  0.2,  12.0,  nil);
~registerParam.(\tailFmglideAmp,      1.0,  0.0,   4.0,  nil);

~registerParam.(\tailGranularAttack,  0.7,  0.001, 2.0,  nil);
~registerParam.(\tailGranularDecay,   0.0,  0.0,   4.0,  nil);
~registerParam.(\tailGranularSustain, 1.0,  0.0,   1.0,  nil);
~registerParam.(\tailGranularRelease, 3.5,  0.2,  12.0,  nil);
~registerParam.(\tailGranularAmp,     1.0,  0.0,   4.0,  nil);

~registerParam.(\tailSubglowAttack,   0.03, 0.001, 2.0,  nil);
~registerParam.(\tailSubglowDecay,    0.0,  0.0,   4.0,  nil);
~registerParam.(\tailSubglowSustain,  1.0,  0.0,   1.0,  nil);
~registerParam.(\tailSubglowRelease,  4.2,  0.2,  12.0,  nil);
~registerParam.(\tailSubglowAmp,      1.0,  0.0,   4.0,  nil);

// ---- Tail-Orbit: Kreisbewegung um den Ausloeseort ----------------------
// Jeder Tail wandert nach dem Trigger im Kreis um die Position des Knotens,
// der ihn ausgeloest hat -- zufaellig rechts oder links herum, schnell am
// Anfang, langsamer zum Ausklingen hin. Entwurf und Herleitung:
// docs/superpowers/specs/2026-08-01-tail-synth-orbit-design.md (Branch
// docs/tail-orbit-concept). Die Mathe steht in ~tailOrbitOut, hier stehen
// nur die Regler.
//
// FUENF GLOBALE PARAMETER, nicht fuenf mal fuenf (Entscheidung 2026-08-01):
// bei fuenf Tail-Synths waeren pro-Tail-Regler 25 Adressen fuer ein Feature,
// von dem noch niemand gehoert hat, wie es sich im Raum anfuehlt. Der
// Vertrag aendert sich nicht, wenn spaeter doch pro Tail differenziert wird
// -- ~tailOrbitOut bekommt die Werte ohnehin als Argumente, nur die
// Trigger-Stelle liest sie dann aus fuenf Adressen statt aus einer.
//
// Radius NORMIERT, nicht in Metern: 1.0 = Boxabstand. Die Grundflaeche ist
// 14 x 8 m, ~toQuad normiert je Achse getrennt -- ein Kreis in Metern waere
// im Lautsprecherraum eine Ellipse und klaenge nach einer schwankenden statt
// nach einer runden Bewegung. Nebenwirkung, die man kennen muss:
// tailOrbitRadius = 0.5 sind auf dem Grundriss 3.5 m in x, aber nur 2 m in y.
// Auf dem Papier eine Ellipse, im Klangbild ein Kreis. Das ist gewollt.
//
// radius = 0 IST der Aus-Schalter und klingt bitgleich wie ein Tail ohne
// dieses Feature -- dieselbe Idiomatik wie travelMix = 0 und
// regionBiasAmount = 0, deshalb gibt es keinen enabled-Schalter.
~registerParam.(\tailOrbitRadius, 0.25, 0.0, 2.0, nil);

// Umdrehungen pro Sekunde BEI VOLLER HUELLKURVE (env = 1). Der insgesamt
// ueberstrichene Winkel haengt deshalb nicht an diesem Wert allein, sondern
// am Flaecheninhalt unter der Huellkurve:
//   Umdrehungen gesamt = tailOrbitSpeed * Integral(env dt)
// Fuer eine Perc-artige Huelle mit curve -4 und Ausklingzeit T ist dieses
// Integral 0.2313 * T. Eine VOLLE Umdrehung ueber einen 4-Sekunden-Tail
// braucht also tailOrbitSpeed ~ 1.08. Mit dem Default 0.5 kommt weniger als
// eine halbe Umdrehung heraus -- kein Fehler, sondern die Folge davon, dass
// die Bewegung mit dem Klang ausklingt. Die Zahl steht hier, weil sie sonst
// beim Einstellen ueberrascht.
~registerParam.(\tailOrbitSpeed, 0.5, 0.0, 4.0, nil);

// Exponent auf die Huellkurve in der Ratenformel. < 1 = die Rotation haelt
// laenger in den Ausklang hinein, > 1 = sie bremst frueher ab.
~registerParam.(\tailOrbitEnvExp, 1.0, 0.25, 4.0, nil);

// 0 = Richtung je Tail zufaellig (Betrieb), +-1 = Richtung erzwungen (zum
// Pruefen, etwa in einer NRT-Messung). Gewuerfelt wird JE TAIL-SYNTH, nicht
// je Treffer: bei einer gemeinsamen Ziehung rotierten alle fuenf Schichten
// synchron und die additive Schichtung klaenge wieder wie eine Quelle.
~registerParam.(\tailOrbitDirLock, 0.0, -1.0, 1.0, nil);

// Untergrenze des wirksamen Radius, 0 = aus. Falls sich beim Hoeren zeigt,
// dass kleine Radien nur verschmieren statt zu bewegen, ist die Untergrenze
// damit eine Zahl und keine Codeaenderung. ACHTUNG, die eine Nebenwirkung:
// steht dieser Wert ueber 0, ist tailOrbitRadius = 0 nicht mehr der
// Aus-Schalter -- dann klemmt die Untergrenze den Radius wieder hoch.
~registerParam.(\tailOrbitMinRadius, 0.0, 0.0, 0.5, nil);

// ---- Einen Parameterwert anwenden --------------------------------------
// DER einzige Ort, an dem ein Sound-Parameter seinen Wert bekommt. Sowohl
// der OSC-Empfang (/klangnetz/param/<name>) als auch das Preset-Laden gehen
// hier durch -- zwei Kopien dieser vier Zeilen waeren zwei Wege, die
// auseinanderlaufen koennen (ein neuer Parameter mit onSet-Callback wuerde
// dann per OSC wirken, per Preset aber nicht, ohne Fehlermeldung).
//
// Geklemmt wird auf die Range aus der REGISTRY, nicht auf eine aus einer
// Datei -- dieselbe Regel wie PresetStore.applyPreset() auf der Java-Seite,
// damit ein aelteres Preset nach einer Bereichsaenderung gueltig bleibt.
//
// Liefert den tatsaechlich gesetzten Wert, oder nil bei unbekanntem Namen.
~applyParam = { |name, rawValue|
    var entry = ~params[name];
    if (entry.isNil) {
        nil
    } {
        var val = rawValue.asFloat.clip(entry[\lo], entry[\hi]);
        currentEnvironment[name] = val;
        entry[\onSet] !? { |cb| cb.(val) };
        // Die vier Master-/Reverb-Parameter wirken ueber den dauerhaft
        // laufenden \masterReverb-Synth. Ein .set auf alle ist billig und
        // robust gegen Aufrufreihenfolge -- unschaedlich, wenn ein
        // SynthDef-Argumentname bei diesem Parameter gar nicht existiert,
        // .set ignoriert unbekannte Keys still.
        ~masterReverbSyn !? { |syn| syn.set(name, val) };
        val
    }
};

// ---- Presets fuer die Sound-Parameter ----------------------------------
// Ein SC-Preset ist ein Satz von Werten fuer die oben registrierten
// Parameter -- und NUR fuer die. Das Licht (Speed, Lifetime, Sequencer,
// Farben) liegt in data/presets/<name>.txt auf der Processing-Seite und
// bleibt dort; imPulse ist Master und schickt bei jedem Wechsel
// /sc/preset/load <name> hierher (PresetManager.forwardToSound()).
//
// GLEICHER NAME, ZWEI DATEIEN: "hang_drum_slow" meint
// data/presets/hang_drum_slow.txt fuer das Licht UND
// supercollider/presets/hang_drum_slow.txt fuer den Klang. Ein Name, der
// hier keine Datei hat, ist kein Fehler -- dann bleibt der Klang stehen,
// waehrend das Licht wechselt. Das ist der haeufige Fall und wird nur
// gemeldet, nicht beklagt.
//
// FORMAT: dasselbe wie remoteSettings.txt und die visuellen Presets, sechs
// tabgetrennte Spalten, nach Adresse sortiert, Zeilenende '\n':
//   float <TAB> /klangnetz/param/<name> <TAB> <TAB> wert <TAB> min <TAB> max
// Die Beschreibungsspalte bleibt leer (wie in den vorhandenen SC-Presets).
// min/max stehen zur Information in der Datei, GEKLEMMT wird auf die Range
// aus der Registry (siehe ~applyParam).
~presetDir = thisProcess.nowExecutingPath.notNil.if(
    { thisProcess.nowExecutingPath.dirname +/+ "presets" },
    // Rueckfall, wenn die Datei nicht als Datei ausgewertet wird (aus einem
    // Scratch-Buffer heraus). Dann liegen die Presets im Arbeitsverzeichnis
    // und die Meldung unten nennt den Pfad, damit das nicht raetselhaft ist.
    { "presets" }
);
("Sound-Preset-Ordner: " ++ ~presetDir).postln;

// Spiegelt PresetStore.isValidName() auf der Java-Seite: a-z, 0-9,
// Unterstrich, Bindestrich, hoechstens 64 Zeichen. Autoritaet ist Java --
// hier geht es um Pfad-Traversal: ohne diese Pruefung wuerde
// /sc/preset/load "../../../etc/passwd" eine beliebige Datei oeffnen.
~presetValidName = { |name|
    var text = name.asString;
    var ok = (text.size > 0) and: { text.size <= 64 };
    text.do({ |c|
        var allowed = c.isDecDigit
            or: { (c >= $a) and: { c <= $z } }
            or: { c == $_ } or: { c == $- };
        allowed.not.if({ ok = false });
    });
    ok
};

~presetPath = { |name| ~presetDir +/+ (name.asString ++ ".txt") };

// Alle registrierten Parameter als Preset-Zeilen, nach Adresse sortiert
// (wie PresetStore.write()) -- so bleibt ein git diff zwischen zwei
// Speichervorgaengen auf die wirklich geaenderten Zeilen beschraenkt.
~presetSnapshot = {
    var lines = ~params.keys.asArray.collect({ |name|
        var entry = ~params[name];
        [
            "float",
            "/klangnetz/param/" ++ name,
            "",
            currentEnvironment[name].asString,
            entry[\lo].asString,
            entry[\hi].asString
        ].join("\t")
    });
    lines.sort({ |a, b| a < b });
    lines
};

~presetSave = { |name|
    var path, file, ok = false;
    if (~presetValidName.(name).not) {
        ("Sound-Preset speichern abgelehnt: unzulaessiger Name %"
            .format(name.asString.quote)).postln;
    } {
        File.exists(~presetDir).not.if({ File.mkdir(~presetDir) });
        path = ~presetPath.(name);
        // Direkt geschrieben, nicht ueber Temp-Datei plus Umbenennen wie auf
        // der Java-Seite: sclang hat kein portables rename, und hier
        // speichert ein Mensch von Hand statt eines Schedulers im
        // Sekundentakt. Der Preis ist eine halb geschriebene Datei, falls
        // sclang genau dabei abstuerzt.
        file = File(path, "w");
        if (file.isOpen) {
            file.write(~presetSnapshot.().join("\n") ++ "\n");
            file.close;
            ok = true;
            ("Sound-Preset % gespeichert (% Parameter) -> %"
                .format(name.asString.quote, ~params.size, path)).postln;
        } {
            ("Sound-Preset nicht schreibbar: %".format(path)).postln;
        };
    };
    ok
};

~presetLoad = { |name|
    var path, file, text, applied = 0, unknown = 0, broken = 0;
    if (~presetValidName.(name).not) {
        ("Sound-Preset laden abgelehnt: unzulaessiger Name %"
            .format(name.asString.quote)).postln;
    } {
        path = ~presetPath.(name);
        if (File.exists(path).not) {
            // KEIN Fehler: nicht jede Lichtszene hat ein Klang-Gegenstueck.
            // Der Klang bleibt dann einfach stehen.
            ("Kein Sound-Preset % - Klang bleibt unveraendert"
                .format(name.asString.quote)).postln;
        } {
            file = File(path, "r");
            if (file.isOpen.not) {
                ("Sound-Preset nicht lesbar: %".format(path)).postln;
            } {
                text = file.readAllString;
                file.close;
                // \r mit weg: eine von Hand unter Windows bearbeitete Datei
                // haette sonst ein \r am Ende jedes Zahlenwerts und jede
                // Zeile schluege als unlesbar fehl.
                text.replace("\r", "").split($\n).do({ |line|
                    var fields, address, key;
                    if (line.stripWhiteSpace.size > 0) {
                        fields = line.split($\t);
                        if (fields.size < 4) {
                            broken = broken + 1;
                        } {
                            address = fields[1].stripWhiteSpace;
                            if (address.beginsWith("/klangnetz/param/").not) {
                                unknown = unknown + 1;
                            } {
                                key = address.copyToEnd(
                                    "/klangnetz/param/".size).asSymbol;
                                if (~params[key].isNil) {
                                    unknown = unknown + 1;
                                } {
                                    ~applyParam.(key, fields[3].asFloat);
                                    applied = applied + 1;
                                };
                            };
                        };
                    };
                });
                ("Sound-Preset % geladen: % uebernommen, % unbekannt, % unlesbar"
                    .format(name.asString.quote, applied, unknown, broken)).postln;
                // Der wahrscheinlichste Grund, wenn gar nichts ankommt: die
                // Datei stammt aus dem alten Parametersatz (/sc/amp/*,
                // /sc/bell/*, /sc/scale/*), den es seit dem Pan4-Umbau nicht
                // mehr gibt. Ohne diesen Hinweis sucht man den Fehler im
                // Loader statt in der Datei.
                if ((applied == 0) and: { unknown > 0 }) {
                    ("  -> keine bekannte Adresse dabei. Stammt % noch aus dem"
                        + "alten Parametersatz? Mit /sc/preset/save % neu"
                        + "schreiben.").format(path, name.asString).postln;
                };
            };
        };
    };
    applied
};

s.waitForBoot({
    // ---- Alten Aufbau abräumen ---------------------------------------
    // Nur beim Re-Evaluieren im Live-Coding relevant, beim ersten Lauf sind
    // alle Variablen nil. Ohne das liefen nach dem zweiten Auswerten zwei
    // Decoder-Synths (doppelte Lautstärke) und zwei Reaper-Routinen, und der
    // alte B-Format-Bus bliebe belegt.
    //
    // Die zwei OSC-Empfänger ZUERST abmelden: sie werden weiter unten neu
    // angelegt, und dazwischen sind ~drones und ~quadBus nil. Käme in
    // diesem Fenster eine Meldung von imPulse, liefe der alte Handler in
    // einen Fehler auf nil.
    ~netHitNodeOscFunc !? { |f| f.free };
    ~noiseTestOscFunc !? { |f| f.free };
    ~noiseTestHardOscFunc !? { |f| f.free };
    OSCdef(\impulseStream) !? { |d| d.free };
    OSCdef(\scPresetLoad) !? { |d| d.free };
    OSCdef(\scPresetSave) !? { |d| d.free };
    ~droneReaper !? { |r| r.stop };
    ~paramOscDefs !? { |defs| defs.do({ |d| d.free }) };
    ~masterReverbSyn !? { |syn| syn.free };
    ~voices      !? { |g| g.free };   // frei macht auch alle Stimmen darin
    ~quadBus     !? { |b| b.free };
    ~drones = nil;
    // Liste aktiver Glocken zuruecksetzen: ~voices.free oben hat alle
    // Stimmen mitgenommen, die Synth-Referenzen darin sind also ungueltig.
    // Ohne das wuerde das Voice-Stealing spaeter versuchen, laengst
    // verschwundene Nodes freizugeben.
    ~activeBells = List.new;

    // Vier-Kanal-Bus: Pan4 schreibt direkt darauf, kein Zwischen-B-Format
    // mehr noetig (UMGEBAUT 2026-07-31, siehe ~toQuad unten). Vorher gab es
    // hier einen 3-kanaligen B-Format-Bus (W/X/Y) plus einen separaten
    // \b2Decoder-Synth, der daraus die vier Lautsprecherkanaele machte --
    // beides entfaellt, weil Pan4 schon die vier fertigen Kanaele liefert.
    ~quadBus = Bus.audio(s, 4);
    ~voices = Group.new;

    // ---- Position -> Vierkanal-Pan (Pan4) -----------------------------
    // UMGEBAUT 2026-07-31 (Birk vor Ort, nach der Azimuth-Testabnahme):
    // ersetzt die Ambisonics-Kette (PanB2-Encoder + EIN DecodeB2-Decoder,
    // 3-kanaliger B-Format-Bus) durch SCs Core-UGen Pan4. Grund: Ambisonics
    // 1. Ordnung ist ein DIFFUSIONS-Verfahren -- eine Punktquelle streut bei
    // voller Richtwirkung IMMER auf alle vier Kanaele (gemessen: dominanter
    // Kanal nur ~43% der Gesamtenergie, siehe Kopf der Datei und
    // docs/ambisonics-sharper-panning-optionen.md). Das lohnt sich nur bei
    // FLEXIBLEM Lautsprecher-Layout -- unser Setup ist fest, 4 Boxen, feste
    // Positionen. Pan4 ist SCs eingebauter Vierkanal-Panner fuer genau
    // diesen Fall, verifiziert per NRT-Messung (Hermes-vServer, 2026-07-31,
    // siehe Skill supercollider-sound-design fuer die Methodik):
    //   Position exakt in einer Boxecke -> 100% auf genau dieser Box
    //   Position exakt in der Netzmitte -> exakt 25/25/25/25% (automatisch,
    //     kein separater Diffus-Crossfade noetig, reiner Nebeneffekt der
    //     Equal-Power-Geometrie von Pan4)
    //
    // Pan4 erwartet xpos/ypos NORMIERT auf -1..1, mit den vier Kanaelen an
    // den ECKEN eines Quadrats (LeftFront, RightFront, LeftBack, RightBack
    // -- eine ANDERE Konvention als DecodeB2s azimutbasierte Kanaele!).
    // Unsere Boxen stehen aber auf den SEITENMITTEN eines 14x8m-Rechtecks,
    // nicht in den Ecken. Fix, verifiziert per NRT (siehe Skill): erst
    // JE ACHSE durch die halbe Boxdistanz normieren (7m bzw. 4m, siehe
    // ~maxX/~maxY unten), DANN eine 45-Grad-Rotation OHNE zusaetzliche
    // Normierung (xr = xn - yn, yr = xn + yn) -- das dreht eine Box exakt
    // in Pan4s Eckenkoordinatensystem (Betrag erreicht dabei automatisch
    // +-1, keine sqrt(2)-Korrektur noetig, das war eine erste Fehlannahme
    // beim Verifizieren und ist in der NRT-Messung dokumentiert).
    //
    // ~panSharpness (Nachfolger von ~directHardness) verzerrt den Radius
    // VOR der Rotation: r' = r^(1/panSharpness). Bei r=0 (Netzmitte) bleibt
    // das Ergebnis bei jedem Sharpness-Wert 0 -- die automatische
    // Diffusitaet in der Mitte bleibt also in jedem Fall erhalten.
    ~maxX = 7.0;  // halbe Breite der Grundflaeche (rechts/links-Boxabstand)
    ~maxY = 4.0;  // halbe Tiefe der Grundflaeche (vorn/hinten-Boxabstand)
    ~toQuad = { |sig, px, py|
        var xn, yn, r, angle, rWarped, xw, yw;
        xn = px / ~maxX;
        yn = py / ~maxY;
        r = xn.hypot(yn);
        angle = xn.atan2(yn);
        rWarped = r.pow(1.0 / ~panSharpness);
        xw = rWarped * angle.sin;
        yw = rWarped * angle.cos;
        Pan4.ar(sig, (xw - yw), (xw + yw), 1.0)
    };

    // ---- Huellkurve der Bell-Tails ------------------------------------
    // EIN Ort fuer die Kurve aller fuenf Tails statt fuenf Kopien -- dasselbe
    // Muster wie ~toQuad: ein sclang-Lambda, das beim .add in jeden Graphen
    // eingerechnet wird.
    //
    // Vier Segmente ueber drei Zeiten: 0 -> 1 (attack, \sin wie im
    // abgenommenen Stand) -> sustain (decay) -> 0 (release, exponentiell mit
    // der tailtypischen Kruemmung). SCHEITELWERT 1 ist Pflicht, nicht Zufall:
    // dieselbe Kurve steuert die Winkelgeschwindigkeit des Orbits, und
    // tailOrbitSpeed ist als "Umdrehungen pro Sekunde bei env = 1" definiert
    // (siehe ~tailOrbitOut). Ein anderer Scheitel skalierte die Rotation
    // stillschweigend mit.
    //
    // doneAction 2 sitzt HIER, an der Amplitudenhuelle -- der Orbit fuegt
    // keine eigene Lebensdauer hinzu und beendet nichts.
    ~tailEnv = { |attack, decay, sustain, release, releaseCurve|
        EnvGen.kr(
            Env([0, 1, sustain, 0], [attack, decay, release],
                curve: [\sin, releaseCurve, releaseCurve]),
            doneAction: 2
        )
    };

    // ---- Tail-Orbit: Position + Encoder in einem -----------------------
    // Sechs Zeilen Bahnrechnung, danach der VORHANDENE Encoder. Entwurf:
    // docs/superpowers/specs/2026-08-01-tail-synth-orbit-design.md, Abschnitt
    // 4-6. Ein Lambda statt fuenf Kopien -- dieselbe Ueberlegung wie bei
    // ~toQuad und ~tailEnv.
    //
    // WINKEL ALS INTEGRAL DER HUELLKURVE, nicht als Funktion der Zeit mal
    // Zerfall: Sweep.kr akkumuliert eine Rate, eine Aenderung der Rate
    // verschiebt also nie die Position, nur die Steigung (dieselbe
    // Eigenschaft, aus der MusicalClock auf der Java-Seite akkumuliert statt
    // neu zu rechnen). Der naive Weg "Winkel = Grundtempo * t * decay(t)"
    // liesse den Winkel bei starkem Zerfall gegen Ende ZURUECKLAUFEN -- die
    // Bewegung kehrte um. Das Integral einer Rate kann das strukturell nicht.
    //
    // Sweep nimmt die Rate in EINHEITEN PRO SEKUNDE, deshalb steht
    // tailOrbitSpeed hier direkt in Umdrehungen/s. Die naheliegende
    // Alternative Phasor.kr erwartet den Zuwachs pro CONTROL-SAMPLE; wer dort
    // ControlDur.ir vergisst, bekommt eine um Faktor ~689 zu schnelle
    // Rotation -- hoerbar als Zirpen, nicht als Bewegung.
    //
    // env ist die AMPLITUDENhuellkurve, keine zweite Kurve: laut = schnell,
    // leise = langsam, stumm = steht. Die Rotation hoert damit von selbst
    // auf, wenn nichts mehr zu hoeren ist -- ohne Sonderfall, ohne
    // Verzweigung. Zwei getrennte Kurven koennten auseinanderlaufen (dieselbe
    // Ueberlegung wie bei splitLifetimeJitter auf der Java-Seite, das bewusst
    // ein Faktor auf lifetime ist und kein Ersatzwert).
    //
    // RADIUS WAECHST VON 0 AN (entschieden, Abschnitt 2 des Entwurfs): der
    // Tail startet exakt am Ausloeseort und schraubt sich nach aussen. Bei
    // festem Radius saesse er zum Zeitpunkt 0 schon einen Radius daneben --
    // ein Sprung im Einsatz. Als Rampe dient die ATTACK-FLANKE desselben
    // Tails, kein eigener Parameter. Was man davon erwarten darf: bei den
    // kurzen Attacks (Shimmer/Whoosh/Sub-Glow, 20-30 ms) entfernt die Rampe
    // nur den Sprung, sie ist keine hoerbare Spiralbewegung; bei FM-Glide und
    // Granular (0.7 s) ist sie eine.
    //
    // RAUTEN-CLAMPING |xn| + |yn| <= 1 (entschieden, Abschnitt 5), NICHT
    // Kreis-Clamping: ~toQuad uebergibt Pan4 nicht xn/yn, sondern die
    // 45-Grad-Rotation (xw - yw, xw + yw). Beide Argumente bleiben genau dann
    // in +-1, wenn die Summe der Betraege <= 1 ist -- also innerhalb der
    // RAUTE mit den Ecken (+-1, 0), (0, +-1), und das ist genau die konvexe
    // Huelle der vier Boxen. Der Einheitskreis ist groesser: auf der
    // Diagonale (xn = yn = 0.707, r = 1) ist die Summe 1.41, ein
    // Pan4-Argument also 41 % ueber dem Bereich. Klemmt Pan4 intern, STEHT
    // die Quelle dort still, waehrend der Winkel weiterlaeuft -- viermal pro
    // Umdrehung, jeweils zwischen zwei Lautsprechern. Bei einer feststehenden
    // Glocke faellt das nicht auf, bei einem Feature, dessen ganzer Zweck die
    // Bewegung ist, schon.
    //
    // Das Clamping erfasst den AUSLOESEORT mit: eine Ecke der Grundflaeche
    // liegt normiert bei |7/7| + |4/4| = 2.0, also weit ausserhalb. Ein
    // Knoten dort erzeugt einen Orbit, der komplett draussen laege; die
    // Formel projiziert ihn geschlossen auf den Rand, statt einen Sonderfall
    // zu brauchen. Kein Kollaps, keine Stille, kein if. max(k, 1) >= 1, also
    // nie eine Division durch 0, und bei k <= 1 ist scale exakt 1 -- dann ist
    // das Ergebnis bitgleich der ungeklemmten Bahn.
    ~tailOrbitOut = { |sig, env, x0, y0, attack, orbitRadius, orbitSpeed,
            orbitEnvExp, orbitDir, orbitMinRadius|
        var angle, radiusRamp, rEff, xn, yn, k, scale;
        angle = Sweep.kr(0, orbitDir * orbitSpeed * env.pow(orbitEnvExp)) * 2pi;
        radiusRamp = EnvGen.kr(Env([0, 1], [attack], \sin));
        rEff = orbitRadius.max(orbitMinRadius) * radiusRamp;
        // Konvention wie ~testSweep: x = sin, y = cos. Winkel 0 zeigt nach
        // VORN (+y), wachsender Winkel laeuft vorn -> rechts -> hinten ->
        // links, orbitDir = +1 ist also "rechts herum".
        xn = (x0 / ~maxX) + (rEff * angle.sin);
        yn = (y0 / ~maxY) + (rEff * angle.cos);
        k = xn.abs + yn.abs;
        scale = 1 / k.max(1);   // Betrag gekappt, der Winkel laeuft weiter
        // Zurueck in Meter und durch den EINZIGEN Encoder: dort sitzen die
        // 45-Grad-Rotation, die Kanalkonvention und ~panSharpness. Ein
        // zweiter Encoder-Pfad, der normierte Koordinaten direkt naehme,
        // spaerte zwei Multiplikationen und kostete eine zweite Stelle, an
        // der all das gepflegt werden muss.
        ~toQuad.(sig, xn * scale * ~maxX, yn * scale * ~maxY)
    };

    // ---- SynthDef: Glockenspiel-Ton ----------------------------------
    // Mehrere Sinus-Partialtöne mit leicht unharmonischen Frequenzverhältnissen
    // (typisch für Glocken-/Metallophon-Klänge) und je eigener Decay-Zeit:
    // höhere Teiltöne klingen schneller ab als der Grundton -> das erzeugt den
    // "Bell"-Charakter (heller Anschlag, der sich schnell zum reinen Grundton
    // hin ausdünnt). Kurzer Attack (3 ms), langer exponentieller Decay.
    // Die Klangfarbe ist gegenüber der Zweikanal-Fassung unverändert; ersetzt
    // sind nur pan/Pan2 durch x/y und die Ambisonic-Kette.
    //
    // brightness und detune sind Start-Argumente, keine .set-baren Controls:
    // eine Glocke ist ein kurzer One-Shot (EnvGen mit doneAction:2, weg nach
    // ~3s), ein späteres .set würde nie mehr ankommen, bevor der Synth sich
    // selbst freigibt. Wer den Klang live hören will, hört den NÄCHSTEN
    // getriggerten Ton — bei der Anschlagsrate der Installation (Hits im
    // Sekundenbereich) praktisch sofort.
    SynthDef(\glockenBell, { |freq = 440, amp = 0.3, out = 0, x = 0, y = 0,
            brightness = 1.0, detune = 1.0, bellVolume = 1.0|
        var sig, bellSig, grit, gritEnv, partials, partialAmps, partialDecays,
            harmonicPartials;

        // Frequenzverhältnisse der Teiltöne (1 = Grundton), leicht verstimmt
        // gegenüber ganzzahligen Harmonischen für metallischen Glockenklang.
        partials      = #[1.0, 2.001, 2.998, 4.204, 5.515];
        // Rein harmonische Gegenprobe (1:2:3:4:5) — Ziel von detune = 0.
        harmonicPartials = #[1.0, 2.0, 3.0, 4.0, 5.0];
        partialAmps   = #[1.0, 0.55, 0.30, 0.18, 0.10];
        partialDecays = #[3.2, 2.1, 1.35, 0.85, 0.55]; // Sekunden, lang & abnehmend

        bellSig = Mix.fill(partials.size, { |i|
            var pFreq, pRatio, pAmp, pDecay, env;
            // detune blendet zwischen der unharmonischen und der rein
            // harmonischen Verhältniszahl. 1.0 = wie bisher, 0.0 = rein.
            pRatio = partials[i].blend(harmonicPartials[i], 1.0 - detune);
            pFreq  = freq * pRatio;
            // brightness skaliert NUR die oberen vier Partialtöne (i > 0) —
            // der Grundton bleibt Referenzpunkt, sonst würde brightness auch
            // die Gesamtlautstärke verschieben statt nur die Klangfarbe.
            pAmp   = if (i == 0) { partialAmps[i] } { partialAmps[i] * brightness };
            pDecay = partialDecays[i];
            // kurzer linearer Attack, langer exponentieller Decay-Tail
            env = EnvGen.kr(
                Env([0, 1, 0], [0.003, pDecay], curve: [\lin, -4]),
                doneAction: if(i == 0) { 2 } { 0 }
            );
            SinOsc.ar(pFreq) * pAmp * env;
        });

        // ---- "Reibung"/Kernigkeit: dezenter Sägezahn-Layer ---------------
        // Aus der Laptop-Fassung (Birk 2026-07-30): kurzer, schneller
        // abklingender Saw-Anteil, bandpassgefiltert um den Grundton -> gibt
        // Attack/Obertongehalt (Hang-Drum-typisch "metallisch-kernig"), ohne
        // die Bell-Hülle zu dominieren oder hart/verzerrt zu klingen. Läuft
        // bewusst VOR dem Encoder in derselben Stimme wie die Teiltöne: es
        // ist Teil des Glockenklangs und gehört an denselben Ort im Raum,
        // anders als der Hall (siehe \masterReverb).
        gritEnv = EnvGen.kr(Env([0, 1, 0], [0.002, 0.35], curve: [\lin, -6]));
        grit = Saw.ar(freq) * gritEnv;
        grit = BPF.ar(grit, freq * 2, 0.7) * 0.22;

        // bellVolume ist der Layer-Fader der Glockenschicht, multiplikativ
        // VOR dem globalen masterVolume (das sitzt in \masterReverb).
        // Bewusst ohne Lag: als Start-Argument eines One-Shots wuerde ein Lag
        // die Lautstaerke ueber die Lag-Zeit hochfahren und damit genau den
        // Anschlag weichzeichnen, der den Glockenklang ausmacht.
        sig = (bellSig + grit) * amp * bellVolume;
        // Position je Ton fest — eine Glocke wandert nicht. Kein Lag nötig.
        Out.ar(out, ~toQuad.(sig, x, y));
    }).add;

    // ---- SynthDefs: die fuenf Bell-Tails ------------------------------
    // Klangerzeugung 1:1 aus der abgenommenen Hoerprobe (v3, 2026-08-01),
    // Begruendung und Parametertabelle bei den ~registerParam-Aufrufen oben.
    // Drei Dinge sind gegenueber der Hoerprobe geaendert, alle drei aus dem
    // Vertrag an die Spatialisierung, nicht aus dem Klang heraus:
    //
    //  * MONO statt "sig ! 2". Die Hoerprobe war ein Stereo-Render; hier geht
    //    ein einkanaliges Signal in ~toQuad, den EINZIGEN Encoder. Zwei
    //    Panning-Pfade waeren eine zweite Wahrheit, die bei der naechsten
    //    Verkabelungsmessung auseinanderfaellt (dieselbe Ueberlegung, aus der
    //    der Hall hinter dem Decoder sitzt).
    //  * \tail4_granular: GrainSin mit numChannels 1 und OHNE pan-Argument.
    //    Das pan war ein zweiter Panner INNERHALB des Synths. Die Bewegung,
    //    die es leisten sollte, liefert jetzt der Orbit -- und zwar im echten
    //    Raum statt im Stereobild.
    //  * "dur" heisst jetzt "release" und kommt aus der Registry. Es ist
    //    dieselbe Zahl an derselben Stelle (Ausklingdauer UND Laufzeit der
    //    internen Sweeps), nur nicht mehr fest im SynthDef.
    //
    // BEWUSST GEBLIEBEN ist der FreeVerb in Shimmer, FM-Glide und Sub-Glow,
    // obwohl diese Datei sonst "Hall NACH dem Panning" durchhaelt (siehe
    // \masterReverb): er ist Teil des abgenommenen Klangs. Drei Gruende
    // sprechen dagegen, und alle drei sind Birks Entscheidung, nicht eine
    // Aufraeumaufgabe:
    //  * Ein Hall VOR dem Encoder wird selbst raeumlich codiert und wandert
    //    mit. Bei einer ROTIERENDEN Quelle ist das ein bekanntes Artefakt --
    //    der Raum kreist mit, statt stehen zu bleiben.
    //  * \masterReverb liefert ohnehin schon Hall, je Hardware-Kanal und
    //    dekorreliert. Der Tail-Hall kommt obendrauf.
    //  * ER KOSTET GEMESSEN ZWEI DRITTEL DER LAST DIESER SCHICHT: bei 10
    //    Treffern/s liegt die Server-CPU mit den drei FreeVerbs bei 65 %,
    //    ohne sie bei 23 % (gleiche Maschine, gleicher Lauf, 40 Treffer;
    //    zum Vergleich: ohne Tails 5 %). Gemessen auf einem Linux-vServer
    //    OHNE Echtzeitpriorisierung -- die absoluten Zahlen gelten nicht fuer
    //    den Windows-Laptop, das VERHAELTNIS schon.
    // Bis Birk am Netz gehoert hat, ob der Tail ohne seinen Eigenhall noch
    // der abgenommene Klang ist, bleibt er drin.
    SynthDef(\tail1_shimmer, { |freq = 440, amp = 0.22, out = 0, x0 = 0, y0 = 0,
            attack = 0.02, decay = 0.0, sustain = 1.0, release = 4.5,
            tailVolume = 1.0, orbitRadius = 0.25, orbitSpeed = 0.5,
            orbitEnvExp = 1.0, orbitDir = 1, orbitMinRadius = 0.0|
        var sig, env, n = 6;
        env = ~tailEnv.(attack, decay, sustain, release, -2);
        sig = Mix.fill(n, { |i|
            var detuneHz = LFNoise1.kr(0.1 + (i * 0.05)).range(-1.5, 1.5) * (i + 1);
            SinOsc.ar(freq * (i + 2) / 2 + detuneHz, 0, 1 / (i + 2))
        });
        sig = sig * amp * tailVolume * env;
        sig = FreeVerb.ar(sig, 0.5, 0.9, 0.3);
        Out.ar(out, ~tailOrbitOut.(sig, env, x0, y0, attack, orbitRadius,
            orbitSpeed, orbitEnvExp, orbitDir, orbitMinRadius));
    }).add;

    SynthDef(\tail2_whoosh, { |freq = 440, amp = 0.28, out = 0, x0 = 0, y0 = 0,
            attack = 0.02, decay = 0.0, sustain = 1.0, release = 3.2,
            tailVolume = 1.0, orbitRadius = 0.25, orbitSpeed = 0.5,
            orbitEnvExp = 1.0, orbitDir = 1, orbitMinRadius = 0.0|
        var sig, env, sweepFreq;
        env = ~tailEnv.(attack, decay, sustain, release, -3);
        sweepFreq = XLine.kr(freq * 6, freq * 0.8, release);
        sig = BPF.ar(WhiteNoise.ar(1), sweepFreq, 0.15);
        sig = sig + (BPF.ar(WhiteNoise.ar(1), sweepFreq * 2, 0.1) * 0.4);
        sig = sig * amp * tailVolume * env;
        Out.ar(out, ~tailOrbitOut.(sig, env, x0, y0, attack, orbitRadius,
            orbitSpeed, orbitEnvExp, orbitDir, orbitMinRadius));
    }).add;

    SynthDef(\tail3_fmglide, { |freq = 440, amp = 0.24, out = 0, x0 = 0, y0 = 0,
            attack = 0.7, decay = 0.0, sustain = 1.0, release = 3.8,
            tailVolume = 1.0, orbitRadius = 0.25, orbitSpeed = 0.5,
            orbitEnvExp = 1.0, orbitDir = 1, orbitMinRadius = 0.0|
        var sig, env, car, modIdx, glideFreq;
        env = ~tailEnv.(attack, decay, sustain, release, -4);
        glideFreq = XLine.kr(freq * 1.5, freq * 0.5, release);
        modIdx = XLine.kr(4, 0.2, release);
        car = SinOsc.ar(glideFreq + (SinOsc.ar(glideFreq * 2.756) * glideFreq * modIdx * 0.1));
        sig = car * amp * tailVolume * env;
        sig = FreeVerb.ar(sig, 0.4, 0.85, 0.25);
        Out.ar(out, ~tailOrbitOut.(sig, env, x0, y0, attack, orbitRadius,
            orbitSpeed, orbitEnvExp, orbitDir, orbitMinRadius));
    }).add;

    SynthDef(\tail4_granular, { |freq = 440, amp = 0.26, out = 0, x0 = 0, y0 = 0,
            attack = 0.7, decay = 0.0, sustain = 1.0, release = 3.5,
            tailVolume = 1.0, orbitRadius = 0.25, orbitSpeed = 0.5,
            orbitEnvExp = 1.0, orbitDir = 1, orbitMinRadius = 0.0|
        var sig, env, trig, grainFreq, grainDur;
        env = ~tailEnv.(attack, decay, sustain, release, -2);
        trig = Dust.kr(XLine.kr(35, 5, release));
        grainFreq = freq * (2 ** (LFNoise0.kr(8).range(-0.3, 1.2)));
        grainDur = 0.03;
        // numChannels 1, kein pan: siehe Block oben.
        sig = GrainSin.ar(1, trig, grainDur, grainFreq, envbufnum: -1);
        sig = sig * amp * tailVolume * env;
        Out.ar(out, ~tailOrbitOut.(sig, env, x0, y0, attack, orbitRadius,
            orbitSpeed, orbitEnvExp, orbitDir, orbitMinRadius));
    }).add;

    SynthDef(\tail5_subglow, { |freq = 440, amp = 0.3, out = 0, x0 = 0, y0 = 0,
            attack = 0.03, decay = 0.0, sustain = 1.0, release = 4.2,
            tailVolume = 1.0, orbitRadius = 0.25, orbitSpeed = 0.5,
            orbitEnvExp = 1.0, orbitDir = 1, orbitMinRadius = 0.0|
        var sig, env, sub, trem;
        env = ~tailEnv.(attack, decay, sustain, release, -2);
        trem = SinOsc.kr(4.5).range(0.6, 1.0);
        sub = (SinOsc.ar(freq / 2) * 0.7) + (SinOsc.ar(freq / 4) * 0.3);
        sig = sub * trem * amp * tailVolume * env;
        sig = FreeVerb.ar(sig, 0.3, 0.7, 0.2);
        Out.ar(out, ~tailOrbitOut.(sig, env, x0, y0, attack, orbitRadius,
            orbitSpeed, orbitEnvExp, orbitDir, orbitMinRadius));
    }).add;

    // ---- SynthDef: Drohne eines reisenden Impulses --------------------
    // Zwei Schichten in EINEM Synth je Impuls: ein leiser gehaltener Ton und
    // eine granulare Windschicht, dazwischen blendet travelMix.
    //
    // EIN Synth je Impuls, kein zweiter: dieser hier haengt schon an einer
    // Impuls-ID und bekommt Position, Huellkurve, ~droneTimeout und
    // ~droneLimit geschenkt. Ein zweiter Synth fuer den Wind braeuchte ein
    // zweites Dictionary, einen zweiten Reaper und ein zweites Limit und
    // verdoppelte die Stimmenzahl -- fuer ein Feature, dessen Ziel es ist,
    // Klangbrei zu VERMEIDEN. Siehe auch den Block bei ~registerParam
    // \travelMix.
    SynthDef(\impulseDrone, { |freq = 220, amp = 0.08, out = 0, x = 0, y = 0, gate = 1,
            lpfMult = 6, travelMix = 0, travelFreq = 800, travelRq = 0.35,
            travelAmpScale = 1.0, travelGrainRatio = 0.125, droneVolume = 1.0|
        var sig, tone, wind, env, xl, yl, ampl, lpfMultLagged, mixLagged,
            rqLagged, windAmpLagged, ratioLagged, volLagged,
            grainTrig, grainEnv, grainDur;
        // Lag VOR der Umrechnung: ein springender Positionswert darf nicht
        // als Klick durchschlagen. Bei 10 Hz Melderate glättet 0.1 s die
        // Sprünge zwischen zwei Meldungen.
        xl = Lag.kr(x, 0.1);
        yl = Lag.kr(y, 0.1);
        // amp braucht dieselbe Glättung und aus demselben Grund: die
        // Energie eines Impulses ändert sich von Meldung zu Meldung, und
        // ein Sprung der Lautstärke auf einem gehaltenen Ton knackt genauso
        // wie ein Sprung der Position. Gleiche Zeit wie oben, damit
        // Lautstärke und Ort nicht auseinanderlaufen.
        ampl = Lag.kr(amp, 0.1);
        // lpfMult kommt live per .set vom OSC-Parameter droneLpfMult (siehe
        // ~registerParam-Aufruf oben) — genauso lagged, sonst knackt ein
        // Regler-Sprung mitten im Filterverlauf.
        lpfMultLagged = Lag.kr(lpfMult, 0.1);
        // travelMix/travelRq/travelAmpScale/travelGrainRatio/droneVolume
        // kommen ebenfalls live per .set (siehe die onSet-Callbacks bei
        // ~registerParam) und brauchen dieselbe Glättung: ein Sprung im
        // Crossfade, in der Koernerdauer oder in der Lautstärke knackt auf
        // einem gehaltenen Ton genauso wie ein Positionssprung.
        mixLagged = Lag.kr(travelMix, 0.2);
        rqLagged = Lag.kr(travelRq, 0.1);
        windAmpLagged = Lag.kr(travelAmpScale, 0.1);
        ratioLagged = Lag.kr(travelGrainRatio, 0.1);
        volLagged = Lag.kr(droneVolume, 0.1);
        env = EnvGen.kr(Env.asr(0.05, 1, 0.3, \sin), gate, doneAction: 2);

        // Tonschicht -- unveraendert der bisherige Klang.
        tone = LFTri.ar(freq) * 0.5 + SinOsc.ar(freq * 2.01, 0, 0.2);
        tone = LPF.ar(tone, freq * lpfMultLagged);

        // ---- Windschicht: GRANULAR, nicht kontinuierlich ---------------
        // UMGEBAUT 2026-08-01 (Birk nach mehreren Hoerproben): war vorher
        // BPF.ar(PinkNoise.ar, travelFreq, rq) -- ein durchgehender, eng
        // gefilterter Rauschton ohne Struktur. Gewuenscht ist stattdessen
        // eine koernige Textur: viele kurze Rauschkoerner, "wie Sand, der
        // runterrinnt". Deshalb drei Aenderungen auf einmal:
        //
        //   1. WEISSES statt rosa Rauschen -- rosa faellt mit 3 dB/Oktave
        //      ab und klingt hinter einem Low-Cut dumpf/hohl; weiss ist der
        //      luftige, sandige Grundstoff.
        //   2. LOW-CUT (HPF) statt Bandpass -- ein Bandpass macht einen
        //      engen, pfeifenden Ton, genau das Gegenteil von luftig.
        //   3. GRANULAR statt Dauerton -- Dust.ar streut die Koerner
        //      zufaellig (Poisson), nicht periodisch: ein regelmaessiges
        //      Impulse.ar klaenge nach Motor, nicht nach rieselndem Sand.
        //
        // Die Speed-Kopplung bleibt der Zweck der Uebung und wird sogar
        // staerker: travelFreq bestimmt jetzt BEIDES -- die Grenzfrequenz
        // des Low-Cut UND die Koernerdichte. Ein 8x-Impuls rieselt damit
        // dichter UND heller als ein 1x-Impuls, zwei gleichgerichtete
        // Merkmale statt einem.
        grainDur = rqLagged * 0.02;              // 0.4 .. 20 ms je Korn
        grainTrig = Dust.ar(travelFreq * ratioLagged);
        // Decay2 = zwei Exponentialkurven, also ein Korn mit weichem
        // Ausklang statt eines harten Rechtecks. Der sehr kurze Attack
        // (0.3 ms) laesst die Kante hoerbar -- sie ist das "Kleinteilige".
        grainEnv = Decay2.ar(grainTrig, 0.0003, grainDur);
        wind = WhiteNoise.ar * grainEnv;
        // Low-Cut NACH der Huelle: so schneidet der Filter auch die
        // Korn-Kanten mit an und gibt ihnen etwas Luft, statt sie als
        // harten Klick stehen zu lassen.
        wind = HPF.ar(wind, travelFreq);
        // ---- Pegel: zwei getrennte Korrekturen -------------------------
        // 1. DICHTEAUSGLEICH. Die Koernerdichte haengt an travelFreq, also
        //    an der Speed-Klasse -- doppelt so viele Koerner tragen doppelt
        //    so viel Energie. OHNE diesen Ausgleich waere die 8x-Klasse
        //    VIERMAL so laut wie die 0.5x-Klasse (NRT-gemessen: RMS 0.134
        //    gegen 0.501). Die Klasse soll sich in Farbe und Dichte zeigen,
        //    NICHT in der Lautstaerke -- sonst regelt der Limiter die
        //    schnellen Impulse weg und die Zuordenbarkeit kippt.
        //    1/sqrt(rate) ist der richtige Exponent, weil sich unkorrelierte
        //    Koerner in der Leistung addieren, nicht in der Amplitude.
        //    Bezugspunkt 100 Koerner/s = die 2x-Klasse. Gemessen bleibt
        //    danach eine Streuung von Faktor 1.08 statt 4.0.
        // 2. ANGLEICH AN DIE TONSCHICHT, damit der Crossfade kein Sprung
        //    ist. Faktor 3 -- NRT-gemessen liegt die Windschicht damit rund
        //    3 dB unter der Tonschicht, was bei ihrem hohen Scheitelfaktor
        //    (10..19 gegen 2.1) etwa gleich laut WIRKT. Feinjustiert wird
        //    mit travelAmpScale.
        // Der max(..., 1) verhindert eine Division durch fast 0, das
        // .clip(0, 4) eine Explosion bei sehr kleiner Dichte.
        wind = wind
            * ((100 / max(travelFreq * ratioLagged, 1)).sqrt.clip(0, 4))
            * 3 * windAmpLagged;

        // Crossfade: 0 = reine Tondrohne (Auslieferungszustand, bitgleich
        // dem bisherigen Klang), 1 = reine Koernerschicht.
        sig = XFade2.ar(tone, wind, (mixLagged * 2) - 1);
        // droneVolume ist der Layer-Fader dieser Schicht, multiplikativ VOR
        // dem globalen masterVolume (das sitzt in \masterReverb).
        sig = sig * ampl * env * volLagged;
        Out.ar(out, ~toQuad.(sig, xl, yl));
    }).add;

    // \b2Decoder ENTFAELLT (UMGEBAUT 2026-07-31): war der Ambisonics-
    // Decoder (DecodeB2), der aus dem 3-kanaligen B-Format vier
    // Lautsprecherkanaele machte. Pan4 liefert die vier Kanaele bereits
    // direkt (siehe ~toQuad), ein separater Decoder-Synth ist ueberfluessig.

    // ---- SynthDef: Master-Volume + Reverb + Limiter --------------------
    // Fester Anschlusspunkt auf den vier fertigen Lautsprecherkanaelen
    // (~quadBus, direkt von Pan4 beschrieben) — bewusst NICHT VOR dem
    // Panning. Zwei Gründe:
    //
    //  1. Ein Hall VOR dem Panning würde selbst räumlich positioniert
    //     (Pan4 kennt nur Punktquellen, kein FreeVerb-Ausgang ist eine
    //     solche) und dadurch zu einer einzigen Phantomrichtung
    //     verschmiert statt zu einem diffusen, allseitigen
    //     Raumklang — das Gegenteil von dem, was ein Hall leisten soll.
    //  2. NACH dem Decoder liegen schon vier UNABHÄNGIGE Kanäle vor. Ein
    //     FreeVerb je Kanal (durch SCs Multichannel-Expansion automatisch
    //     vier separate Hallfahnen mit eigenem internen Zufallsrauschen)
    //     ergibt eine echte dekorrelierte Umhüllung, wie ein realer Raum
    //     sie hat — kein künstlich korrelierter „Hall aus einer Box".
    //
    // Master-Volume sitzt VOR dem Limiter (Auftrag: „NACH dem Decoder, VOR
    // dem Limiter") — ein Volume-Push, der den Limiter hörbar zum Greifen
    // bringt, ist ausdrücklich erwünschtes Verhalten (z.B. für einen
    // bewusst dichten/lauten Moment), kein Fehler.
    SynthDef(\masterReverb, { |in = 0, out = 0,
            masterVolume = 1.0, reverbMix = 0.15, reverbRoom = 0.5, reverbDamp = 0.5|
        var dry, wet, sig, permuted;
        dry = In.ar(in, 4);
        // FreeVerb ist ein Standard-SC-Core-UGen (kein Quark nötig).
        // .ar mit einem 4-Kanal-Signal expandiert automatisch zu vier
        // unabhängigen FreeVerb-Instanzen (Multichannel-Expansion) — siehe
        // Begründung oben.
        wet = FreeVerb.ar(dry, reverbMix, reverbRoom, reverbDamp);
        sig = wet * masterVolume;
        // Schutznetz gegen hartes digitales Clipping. Rechnung: für eine
        // einzelne Glocke ist amp * (Summe(partialAmps) + Saw-Layer) die
        // OBERE SCHRANKE — erreicht wird sie nie, weil die fünf Teiltöne auf
        // nicht ganzzahligen Verhältnissen liegen und nie gleichzeitig im
        // Scheitel stehen. GEMESSEN 2026-07-30 (scsynth NRT, SC 3.11.2,
        // Signalkette dieser SynthDef mono nachgebaut, Scheitel über die
        // ersten 0.5 s, alle verwendeten Tonhöhen): der tatsächliche Scheitel
        // lag bei amp = 0.5 zwischen 0.85 und 0.87, Grösstwert 0.864 bei
        // 880 Hz. Seit der Zusammenführung ist ~maxAmp = 0.348 statt 0.5, die
        // einzelne Glocke liegt also noch deutlicher darunter — dafür kommt
        // der Sägezahn-Layer obendrauf, und die Messung ist mit ihm NICHT
        // wiederholt worden.
        // Dazu kommen bis zu ~droneLimit = 32 Drohnen à höchstens
        // 2 * ~droneAmpScale = 0.12. Die Summe kann also 1.0 überschreiten
        // — sie tut es nur bei zufälliger Gleichphasigkeit, weil die
        // Stimmen auf verschiedenen Tonhöhen liegen, aber "selten" ist kein
        // Schutz. Eine einzelne Glocke bleibt mit 0.86 unter der Schwelle,
        // der Limiter ist im Normalfall also untätig und hörbar nur, wenn
        // wirklich viel gleichzeitig klingt — oder masterVolume > 1 einen
        // sonst unauffälligen Moment bewusst anhebt. Unterhalb von 0.95
        // greift er nicht ein.
        // Er wirkt je Kanal getrennt (Multichannel-Expansion): setzt er ein,
        // kann das Klangbild kurz wandern. Das ist der Preis dafür, dass ein
        // gekoppelter Vierkanal-Limiter kein Kern-UGen ist, und immer noch
        // besser als hartes Clipping. Preis: Limiter schaut 0,01 s voraus und
        // verzögert damit den GESAMTEN Ausgang um 10 ms — gleichmässig über
        // alle vier Kanäle, das Klangbild bleibt also unberührt.
        // Schwelle 0.15 (Birk, vor Ort gemessen, master-Commit b1c6e13
        // 2026-08-01) -- von diesem Feature-Branch nachgezogen, damit
        // beide Branches denselben abgenommenen Limiter-Wert fahren.
        sig = Limiter.ar(sig, 0.15, 0.01);
        // GEMESSEN 2026-07-31 (Show-Rechner, Birk vor Ort): die Hardware-
        // Verkabelung des ZOOM AMS-24 ist KEINE einfache Rotation der
        // Panner-Kanal-Reihenfolge, sondern eine echte Vertauschung.
        // Gemessene Kabel-Zuordnung (per \channelTest auf jedem
        // Hardware-Kanal einzeln, Birk hat pro Kanal die klingende Box
        // benannt): hw0=rechts, hw1=links, hw2=hinten, hw3=vorn.
        //
        // UMGEBAUT 2026-07-31 (Ambisonics -> Pan4, siehe ~toQuad): Pan4
        // liefert seine vier Kanaele in der FESTEN Reihenfolge
        // [vorn, rechts, links, hinten] (das ist NICHT dieselbe Konvention
        // wie DecodeB2s azimutbasierte Kanaele -- die alte Permutation
        // unten war fuer DecodeB2 gemessen und wurde NEU per NRT-Messung
        // verifiziert, nicht 1:1 uebernommen, siehe Skill
        // supercollider-sound-design). Verifiziert (Hermes-vServer,
        // Score.recordNRT, alle vier Boxpositionen einzeln getestet):
        // sig[0]=vorn, sig[1]=rechts, sig[2]=links, sig[3]=hinten ->
        //   hw0 (rechts) <- sig[1]
        //   hw1 (links)  <- sig[2]
        //   hw2 (hinten) <- sig[3]
        //   hw3 (vorn)   <- sig[0]
        // AUF EINEM ANDEREN INTERFACE/BEI ANDERER VERKABELUNG NEU MESSEN
        // (siehe klangnetz-remote-control Skill, Abschnitt "Schicht 0" für
        // den Kanaltest-Workflow) — diese Permutation ist an das ZOOM
        // AMS-24 und seine aktuelle Verkabelung gebunden, keine
        // allgemeine Pan4-Eigenschaft.
        permuted = [sig[1], sig[2], sig[3], sig[0]];
        Out.ar(out, permuted);
    }).add;

    // ---- SynthDef: Messhilfe -----------------------------------------
    // Nur für die Messung: Rauschen auf genau einen HARDWARE-Kanal, am
    // B-Format-Bus und am Decoder vorbei. Das ist der Sinn der Sache — hier
    // soll nichts decodiert werden, sondern nur die Verkabelung sichtbar.
    SynthDef(\channelTest, { |chan = 0, amp = 0.2|
        var sig = PinkNoise.ar(amp) * EnvGen.kr(Env.linen(0.05, 0.8, 0.15), doneAction: 2);
        Out.ar(chan, sig);
    }).add;

    // ---- SynthDef: Rausch-Testton DURCH den echten Encoder -------------
    // Für die Azimuth-Testabnahme per OSC (kein REPL-Zugriff vor Ort
    // nötig, siehe /klangnetz/test/noise unten). Läuft durch denselben
    // ~toQuad-Encoder wie Glocken/Drohnen — Rauschen ist deutlich
    // leichter zu orten als ein Sinuston.
    SynthDef(\noiseBformat, { |amp = 0.4, out = 0, x = 0, y = 0|
        var sig, env;
        env = EnvGen.kr(Env.linen(0.05, 1.5, 0.3), doneAction: 2);
        sig = PinkNoise.ar(1) * amp * env;
        Out.ar(out, ~toQuad.(sig, x, y));
    }).add;

    // ---- SynthDef: Rausch-Testton HART gepannt (voll gerichtet) --------
    // Fuer die Testabnahme deutlicher als \noiseBformat: erzwingt
    // panSharpness = 8 (Maximum) unabhaengig vom registrierten Live-Wert,
    // damit auch eine Position nahe der Netzmitte (normalerweise diffus)
    // genauso scharf geortet werden kann wie eine Position am Rand. Nur
    // fuer Testzwecke -- Nodes/Drohnen behalten die normale, per
    // ~panSharpness einstellbare Richtwirkung ueber ~toQuad.
    SynthDef(\noiseBformatHard, { |amp = 0.4, out = 0, x = 0, y = 0|
        var sig, env, xn, yn, r, angle, xw, yw;
        env = EnvGen.kr(Env.linen(0.05, 1.5, 0.3), doneAction: 2);
        sig = PinkNoise.ar(1) * amp * env;
        xn = x / ~maxX;
        yn = y / ~maxY;
        r = xn.hypot(yn);
        angle = xn.atan2(yn);
        // panSharpness fest 8 (Maximum) statt ueber ~toQuad/Live-Wert --
        // siehe Kommentar oben.
        xw = r.pow(1.0 / 8.0) * angle.sin;
        yw = r.pow(1.0 / 8.0) * angle.cos;
        Out.ar(out, Pan4.ar(sig, (xw - yw), (xw + yw), 1.0));
    }).add;

    s.sync;

    // ---- Master-Volume + Reverb starten --------------------------------
    // Direkt hinter allen Stimmen -- Pan4 schreibt bereits die vier
    // fertigen Lautsprecherkanaele auf ~quadBus, ein separater
    // Decoder-Synth (wie vorher \b2Decoder) ist nicht mehr noetig.
    ~masterReverbSyn = Synth.after(~voices, \masterReverb, [
        \in, ~quadBus.index, \out, 0,
        \masterVolume, ~masterVolume, \reverbMix, ~reverbMix,
        \reverbRoom, ~reverbRoom, \reverbDamp, ~reverbDamp
    ]);

    // ~setOrientation/~decoderOrientation ENTFALLEN (UMGEBAUT 2026-07-31):
    // waren ein Werkzeug fuer DecodeB2s orientation-Argument, das ohnehin
    // gemessen wirkungslos war (siehe Kopf der Datei). Pan4 kennt kein
    // Orientation-Argument -- die Drehung des Klangbilds passiert jetzt
    // vollstaendig ueber die feste 45-Grad-Rotation in ~toQuad (das ist
    // KEINE live nachstellbare Drehung mehr, weil unser Setup nur eine
    // einzige, gemessene Boxanordnung hat, keine variable Aufstellung).

    // ---- Drohnenverwaltung -------------------------------------------
    // Impuls-ID -> (synth: ..., lastSeen: ...)
    ~drones = IdentityDictionary.new;

    // Tonhöhe je Impuls aus seiner ID, auf derselben Leiter wie die Glocken,
    // aber eine Oktave darunter — so liegen die Drohnen unter dem
    // Glockenspiel statt dazwischen.
    ~droneFreq = { |id|
        var idx = id.abs;
        var step = ~scaleSteps[idx % ~scaleSteps.size];
        var oct = idx.div(~scaleSteps.size) % ~numOctaves;
        (~rootMidiNote - 12 + step + (12 * oct)).midicps
    };

    // ---- OSC-Empfang: /net/impulse <id> <x> <y> <energy> --------------
    // Gedrosselter Positionsstrom, ein Datagramm JE IMPULS und Takt, ohne
    // Anzahl-Feld und ohne Ende-Markierung. Wieviele Meldungen ein Takt hat,
    // ist hier nicht feststellbar — deshalb der Timeout unten.
    OSCdef(\impulseStream, { |msg|
        var id = msg[1], px = msg[2], py = msg[3], energy = msg[4];
        // Fuenftes Argument (Betrag der Geschwindigkeit in LEDs/s) kam
        // spaeter dazu. Die Absicherung laesst die Datei auch mit einem
        // aelteren Processing-Stand laufen -- dann klingt jeder Whoosh so,
        // als stuende der Impuls still.
        var speed = if (msg.size > 5) { msg[5].asFloat } { 0 };
        var entry = ~drones[id];
        // energy ist NICHT durch 1 begrenzt: an einer Kreuzung erbt jeder
        // Zweig die volle Energie des Elternimpulses, sie vervielfacht sich
        // also mit jeder Aufspaltung. Deshalb hier clampen.
        var amp = energy.clip(0, 2) * ~droneAmpScale;
        var travelFreq, travelOct, bias;
        if (entry.notNil) {
            entry[\synth].set(\x, px, \y, py, \amp, amp);
            entry[\lastSeen] = Main.elapsedTime;
        } {
            // Über der Obergrenze wird die Meldung still verworfen. Der
            // Impuls bekommt dann auch bei späteren Meldungen keine Drohne
            // mehr, bis wieder Platz frei ist — das ist gewollt: die Grenze
            // soll den Rechner schützen, nicht Stimmen umschichten.
            if (~drones.size < ~droneLimit) {
                // Speed -> Mittenfrequenz des Windbands, OKTAVBASIERT (siehe
                // die ~registerParam-Aufrufe oben fuer die Begruendung).
                // Einmal hier gerechnet und als Start-Argument uebergeben
                // statt im SC-Graphen: die Geschwindigkeit eines Impulses
                // aendert sich ueber sein Leben kaum, und so bleibt der
                // Graph schlank.
                //
                // max(speed, 0.001): ein alter Processing-Stand ohne das
                // fuenfte Argument meldet 0, und log2(0) waere -inf.
                travelOct = (max(speed, 0.001) / ~travelSpeedRef).log2;
                // Auf die Speed-Klasse rasten, damit Jitter die
                // Zuordenbarkeit nicht verschmiert.
                if (~travelSnap > 0.5) { travelOct = travelOct.round };
                travelFreq = ~travelFreqBase
                    * (2 ** (travelOct * ~travelOctavesPerStep));
                travelFreq = travelFreq.clip(~travelFreqMin, ~travelFreqMax);
                // Klangbias der HERKUNFTS-Region (siehe ~regionBias): die
                // ERSTE gemeldete Position eines Impulses kommt aus dem Takt
                // direkt nach seiner Entstehung, liegt also nahe an seinem
                // Spawn- bzw. Splitpunkt -- das IST die Herkunftsregion, ohne
                // ein weiteres OSC-Feld und ohne Zustand auf der
                // Processing-Seite. Einmal beim Anlegen gerechnet, damit der
                // Whoosh seine Klangidentitaet ueber seine ganze Lebensdauer
                // behaelt, auch wenn er in eine andere Region fliegt.
                bias = ~regionBias.(px, py);
                travelFreq = (travelFreq * bias[\brightness]).clip(20, 18000);
                ~drones[id] = (
                    synth: Synth(\impulseDrone, [
                        \freq, ~droneFreq.(id), \x, px, \y, py,
                        \amp, amp, \out, ~quadBus.index, \lpfMult, ~droneLpfMult,
                        \travelMix, ~travelMix, \travelFreq, travelFreq,
                        \travelRq, ~travelRq, \travelAmpScale, ~travelAmpScale,
                        \travelGrainRatio, ~travelGrainRatio,
                        \droneVolume, ~droneVolume
                    ], ~voices),
                    lastSeen: Main.elapsedTime
                );
            };
        };
    }, '/net/impulse', recvPort: ~oscListenPort);

    // Freigabe allein über den Timeout. Ein Impuls, der aus der Auswahl der
    // energiereichsten fällt, verschwindet für die Klangseite genauso still
    // wie einer, der stirbt — deshalb genau ein Mechanismus für beides.
    ~droneReaper = Routine({
        loop {
            var now = Main.elapsedTime;
            ~drones.keys.asArray.do({ |id|
                var entry = ~drones[id];
                if (entry.notNil and: { (now - entry[\lastSeen]) > ~droneTimeout }) {
                    // gate 0 startet das Release der asr-Hülle, der Synth
                    // gibt sich danach über doneAction: 2 selbst frei.
                    entry[\synth].set(\gate, 0);
                    ~drones.removeAt(id);
                };
            });
            0.1.wait;
        }
    }).play;

    // ---- OSC-Empfang: /net/hitNode <nodeId> <energy> <x> <y> ---------
    // Eigener Listener auf Port 8002 (nicht der sclang-Default 57120), weil
    // imPulse fest an Port 8002 sendet (siehe imPulse.pde).
    // (Das Abmelden eines schon vorhandenen Empfängers passiert oben im
    // Abräumblock, zusammen mit allem anderen aus einem früheren Durchlauf.)
    ~netHitNodeOscFunc = OSCFunc({ |msg, time, addr|
        var nodeId, energy, px, py, noteIndex, octave, degree, semitoneOffset,
            midiNote, freq, amp, newBell, bias, tailAmp, newTails, voiceGroup;

        nodeId = msg[1].asInteger;
        energy = msg[2].asFloat;

        // x und y sind erst seit Aufgabe 14 dabei. Die Absicherung lässt die
        // Datei auch mit einem älteren Processing-Stand laufen, der noch
        // keine Koordinaten schickt — dann klingt alles aus der Mitte.
        px = if (msg.size > 3) { msg[3].asFloat } { 0 };
        py = if (msg.size > 4) { msg[4].asFloat } { 0 };

        // Klangbias nach Netzregion (siehe ~regionBias oben): der Notenoffset
        // wird auf den SKALENINDEX addiert, nicht auf die MIDI-Note -- so
        // bleibt jeder Ton in Phrygisch.
        bias = ~regionBias.(px, py);

        // Node-ID auf einen Ton der Tonleiter über mehrere Oktaven abbilden.
        noteIndex = (nodeId + bias[\noteOffset]) % ~notesPerOctaveSet;
        // Ein negativer Offset kann noteIndex unter 0 druecken; SCs % liefert
        // fuer negative Zahlen bereits einen nichtnegativen Rest, die
        // Absicherung hier ist das Netz darunter.
        noteIndex = noteIndex.abs;
        octave = noteIndex div: ~scaleSteps.size;
        degree = noteIndex mod: ~scaleSteps.size;
        semitoneOffset = ~scaleSteps[degree] + (octave * 12);
        midiNote = ~rootMidiNote + semitoneOffset;
        freq = midiNote.midicps;

        // Energie -> Lautstärke, geclampt in einen angenehmen Bereich.
        amp = energy.clip(0.0, 2.0).linlin(0.0, 2.0, ~minAmp, ~maxAmp);

        // Polyphonie-Deckel (siehe ~maxPolyphony oben): ist das Limit
        // erreicht, die AELTESTE aktive Glocke sofort abwuergen (voice
        // stealing), bevor die neue startet. ~activeBells haelt die Synths in
        // Erzeugungsreihenfolge -> removeAt(0) ist immer die aelteste. Free
        // ist sofort wirksam (kein Fade-out), das ist ein bewusster
        // Trade-off: ein kurzer harter Cut an der Ueberlastgrenze ist
        // unauffaelliger als gar kein Sound mehr, weil der Server dicht ist.
        // Ein Eintrag ist seit den Bell-Tails ein ARRAY [glocke, tail1..tail5]
        // (siehe ~maxPolyphony oben), deshalb .do statt .free -- gestohlen
        // wird die ganze Stimme, nicht nur ihr Anschlag.
        if (~activeBells.size >= ~maxPolyphony) {
            ~activeBells.removeAt(0).do({ |syn| syn.free });
        };

        // Die frühere Zufalls-Panoramisierung entfällt: sie war der
        // Platzhalter für genau diese Knotenposition.
        // brightness/detune sind die globalen Regler MAL dem Zonenfaktor --
        // der Bias verschiebt die Klangfarbe relativ, statt den vor Ort
        // eingestellten Grundwert zu ueberschreiben.
        newBell = Synth(\glockenBell, [
            \freq, freq, \amp, amp, \x, px, \y, py, \out, ~quadBus.index,
            \brightness, (~brightness * bias[\brightness]).clip(0.0, 2.0),
            \detune, (~detune * bias[\detune]).clip(0.0, 1.0),
            \bellVolume, ~bellVolume
        ], ~voices);
        // ---- Fuenf additive Tails, ALLE bei JEDEM Treffer ---------------
        // Nicht einer nach Auswahl: der Schweif ist eine Schichtung, keine
        // Variantenliste (Birk, 2026-08-01: "am Ende machen wir die alle
        // additiv"). Wer einen davon nicht will, zieht seinen Amp-Regler auf
        // 0 -- dann entsteht der Synth gar nicht erst, siehe unten.
        //
        // Alle fuenf starten GLEICHZEITIG mit der Glocke, ohne Versatz. Die
        // Hoerprobe hatte 150 ms Vorsprung fuer die Glocke, damit man Anschlag
        // und Schweif getrennt beurteilen kann; Birks spaetere Rueckmeldung
        // ("soll sofort mit voller Power losgehen") hat das ueberholt, und ein
        // Versatz braeuchte hier eine Zeitplanung je Treffer, die es sonst
        // nirgends in dieser Datei gibt.
        tailAmp = amp * ~tailAmpScale;
        newTails = ~tailDefs.collect({ |entry|
            var defName = entry[0], prefix = entry[1], dir, thisAmp;
            thisAmp = tailAmp * currentEnvironment[(prefix ++ "Amp").asSymbol];
            // Ein stummer Tail wird gar nicht erst erzeugt: der Amp-Regler
            // (und tailVolume) sparen damit nicht nur Pegel, sondern auch
            // Last -- sonst liefen fuenf Synths, von denen vier Stille
            // rechnen, und der Polyphonie-Deckel zaehlte sie mit.
            if ((thisAmp * ~tailVolume) <= 0) {
                nil
            } {
                // Richtung JE TAIL unabhaengig gewuerfelt, nicht je Treffer:
                // bei einer gemeinsamen Ziehung rotierten alle fuenf
                // Schichten synchron -- genau das, was die additive
                // Schichtung raeumlich wieder zu einer Quelle machen wuerde.
                // Bewusst hier in sclang und nicht per TIRand im Graphen:
                // hier ist die Richtung sichtbar, loggbar und per
                // tailOrbitDirLock feststellbar.
                dir = if (~tailOrbitDirLock.abs > 0.5) {
                    ~tailOrbitDirLock.sign
                } {
                    [-1, 1].choose
                };
                Synth(defName, [
                    \freq, freq, \amp, thisAmp,
                    \x0, px, \y0, py, \out, ~quadBus.index,
                    \attack,  currentEnvironment[(prefix ++ "Attack").asSymbol],
                    \decay,   currentEnvironment[(prefix ++ "Decay").asSymbol],
                    \sustain, currentEnvironment[(prefix ++ "Sustain").asSymbol],
                    \release, currentEnvironment[(prefix ++ "Release").asSymbol],
                    \tailVolume, ~tailVolume,
                    \orbitRadius, ~tailOrbitRadius,
                    \orbitSpeed, ~tailOrbitSpeed,
                    \orbitEnvExp, ~tailOrbitEnvExp,
                    \orbitDir, dir,
                    \orbitMinRadius, ~tailOrbitMinRadius
                ], ~voices)
            }
        }).reject({ |syn| syn.isNil });

        // Glocke und ihre Tails als EINE Stimme fuehren (siehe ~maxPolyphony):
        // ein Eintrag, ein Deckel, ein Aufraeumer. Kein zweites Dictionary,
        // kein zweiter Reaper, kein zweites Limit -- dieselbe Ueberlegung, aus
        // der die Windschicht IN \impulseDrone sitzt statt als eigener Synth.
        voiceGroup = [newBell] ++ newTails;
        ~activeBells.add(voiceGroup);
        // Natuerliches Verklingen (doneAction 2 im SynthDef) muss
        // ~activeBells auch aufraeumen, sonst waechst die Liste dauerhaft
        // weiter und removeAt(0) wuergt laengst verklungene Synths ab
        // (harmlos, aber sinnlos -- das echte Voice-Stealing traefe dann die
        // falschen). Aufgehaengt ist das an der GLOCKE, nicht am laengsten
        // Tail: sie ist der einzige Synth, den es sicher gibt. Folge, die man
        // kennen muss -- die Tails klingen laenger als die Glocke (bis 4.5 s
        // gegen 3.2 s), ein Eintrag verlaesst die Buchfuehrung also, waehrend
        // seine Tails noch ausklingen. Das Fenster ist durch die
        // Release-Zeiten begrenzt und laeuft von selbst leer; es waechst
        // nicht. Die Alternative -- jeden der sechs Synths einzeln beobachten
        // und den Eintrag erst beim letzten raeumen -- kostete sechs
        // Node-Watcher je Treffer statt einem, fuer ein paar Sekunden
        // genauere Buchfuehrung an der Ueberlastgrenze.
        newBell.onFree({ ~activeBells.remove(voiceGroup); });

        ("node % -> % Hz (amp %) bei (%, %) m, Zone %".format(
            nodeId, freq.round(0.1), amp.round(0.01), px.round(0.01), py.round(0.01),
            bias[\zone]
        )).postln;
    }, '/net/hitNode', recvPort: ~oscListenPort);

    // ---- OSC-Empfang: Rausch-Testton fuer die Azimuth-Testabnahme ------
    // /klangnetz/test/noise <x:float> <y:float>  (optional amp als drittes
    // Argument, Default 0.4) — spielt PinkNoise durch den echten Encoder
    // an einer beliebigen Position, ohne REPL-Zugriff vor Ort. Deutlich
    // leichter zu orten als der Glockenton (siehe klangnetz-remote-control
    // Skill, "Nimm Rauschen statt Glockenton fuer die Ortungstests").
    ~noiseTestOscFunc = OSCFunc({ |msg|
        var px = msg[1].asFloat, py = msg[2].asFloat;
        var amp = if (msg.size > 3) { msg[3].asFloat } { 0.4 };
        Synth(\noiseBformat, [\amp, amp, \x, px, \y, py, \out, ~quadBus.index], ~voices);
        ("testNoise bei (%, %) m, amp %".format(px.round(0.01), py.round(0.01), amp)).postln;
    }, '/klangnetz/test/noise', recvPort: ~oscListenPort);

    // ---- OSC-Empfang: HART gepannter Rausch-Testton --------------------
    // /klangnetz/test/noiseHard <x:float> <y:float> [amp] — wie
    // /klangnetz/test/noise, aber mit voll gerichtetem \noiseBformatHard
    // (direct = 1 immer), fuer eine eindeutigere Testabnahme naeher an
    // der Netzmitte.
    ~noiseTestHardOscFunc = OSCFunc({ |msg|
        var px = msg[1].asFloat, py = msg[2].asFloat;
        var amp = if (msg.size > 3) { msg[3].asFloat } { 0.4 };
        Synth(\noiseBformatHard, [\amp, amp, \x, px, \y, py, \out, ~quadBus.index], ~voices);
        ("testNoiseHard bei (%, %) m, amp %".format(px.round(0.01), py.round(0.01), amp)).postln;
    }, '/klangnetz/test/noiseHard', recvPort: ~oscListenPort);

    // ---- OSC-Empfang: Sound-Parameter-Layer ---------------------------
    // Ein OSCdef je registriertem Parameter (siehe ~registerParam oben) —
    // schlichter Loop über ~params statt sieben Kopien desselben Rumpfs.
    // Jede Nachricht bringt EINEN Float als erstes Argument.
    //
    // Das Anwenden selbst macht ~applyParam (oben definiert), nicht dieser
    // Rumpf: dieselbe Funktion nutzt auch das Preset-Laden. Zwei Kopien
    // waeren zwei Wege, die auseinanderlaufen koennen.
    //
    // recvPort ist ~oscListenPort, derselbe wie /net/hitNode — ein OSCdef
    // pro Adresse ist die feinste Granularität, die SC anbietet, ein
    // gemeinsamer Empfänger für alle Adressen bräuchte selbst wieder eine
    // Verzweigung nach Adresse und würde nur Code verschieben.
    ~paramOscDefs = ~params.keys.collect({ |name|
        OSCdef(
            ("klangnetzParam_" ++ name).asSymbol,
            { |msg|
                var val = ~applyParam.(name, msg[1]);
                ("klangnetz param % -> %".format(name, val.round(0.001))).postln;
            },
            ("/klangnetz/param/" ++ name).asSymbol,
            recvPort: ~oscListenPort
        );
    });

    // ---- OSC-Empfang: Sound-Presets -----------------------------------
    // /sc/preset/load <name:string>  und  /sc/preset/save <name:string>
    //
    // imPulse ist Master: PresetManager schickt bei JEDEM Preset-Wechsel
    // (von Hand, per Scheduler oder beim Start) /sc/preset/load mit
    // demselben Namen, unter dem das Licht-Preset in data/presets/ liegt.
    // Hier hoert also niemand auf einen eigenen Zeitplan -- es gibt genau
    // einen Scheduler, und der laeuft in imPulse.
    //
    // Fire-and-forget in beide Richtungen: es geht keine Antwort zurueck.
    // Ein Name ohne Datei ist kein Fehler (siehe ~presetLoad).
    OSCdef(\scPresetLoad, { |msg|
        if (msg.size > 1) { ~presetLoad.(msg[1].asString) };
    }, '/sc/preset/load', recvPort: ~oscListenPort);

    OSCdef(\scPresetSave, { |msg|
        if (msg.size > 1) { ~presetSave.(msg[1].asString) };
    }, '/sc/preset/save', recvPort: ~oscListenPort);

    // =================================================================
    // Messhilfen für die zwei ungemessenen Werte
    // =================================================================
    // Aufruf in sclang: ~testChannels.() / ~testAzimuth.() / ~testSweep.()
    // Alle drei brauchen die vier angeschlossenen Lautsprecher. Grundsatz:
    // erst 1., dann 2., dann 3. — ohne 1. lässt sich das Ergebnis von 2.
    // nicht auswerten, weil dann unklar bleibt, ob eine falsche Richtung an
    // der Verkabelung oder an der Rotation/Permutation in ~toQuad/
    // \masterReverb liegt.

    // ---- 1. Kanalreihenfolge: welche Box hängt an welchem Kanal? -----
    // Rauschen nacheinander auf die Hardware-Kanäle 0, 1, 2, 3, je 1,2 s,
    // die Nummer erscheint auf der Konsole.
    //
    // ERWARTUNG: keine — das ist eine reine Aufnahme des Ist-Zustands.
    // Aufschreiben, welche physische Box (vorn / rechts / hinten / links)
    // bei welcher Nummer klingt. Klingt bei einer Nummer GAR NICHTS, ist
    // entweder die Box nicht angeschlossen oder der Server nicht mit vier
    // Ausgangskanälen gebootet (s.options.numOutputBusChannels wirkt erst
    // beim Booten — dann s.reboot und die Datei neu auswerten).
    ~testChannels = {
        Routine({
            "== Kanaltest: notieren, welche Box bei welcher Nummer klingt ==".postln;
            4.do({ |i|
                ("Kanal " ++ i).postln;
                Synth(\channelTest, [\chan, i]);
                1.2.wait;
            });
            "== Kanaltest fertig ==".postln;
        }).play;
    };

    // ---- 2. Position: kommt „rechts" von rechts? -----------------------
    // UMGEBAUT 2026-07-31 (Ambisonics -> Pan4): kein Azimut/Offset mehr,
    // die Rotation+Permutation ist FEST in ~toQuad bzw. \masterReverb
    // eingerechnet und per NRT-Messung verifiziert (siehe Skill
    // supercollider-sound-design). Dieser Test bleibt als GEGENPROBE am
    // echten Geraet nuetzlich, meldet aber keine "was aendern"-Anleitung
    // mehr -- eine Abweichung hier bedeutet, dass sich die Verkabelung
    // seit der letzten NRT-Messung geaendert hat (channelTest wiederholen,
    // Permutation in \masterReverb neu herleiten).
    //
    // ERWARTUNG: der Ton kommt jeweils aus genau der Box, deren Name auf
    // der Konsole steht, und praktisch scharf (Pan4 an einer Boxposition
    // liefert 100% auf einem Kanal, siehe ~toQuad-Kommentar).
    ~testAzimuth = {
        Routine({
            "== Positionstest: kommt der Ton aus der genannten Box? ==".postln;
            [[0, 4, "vorn"], [7, 0, "rechts"], [0, -4, "hinten"], [-7, 0, "links"]].do({ |p|
                p[2].postln;
                Synth(\glockenBell, [\freq, 440, \amp, 0.3, \x, p[0], \y, p[1],
                    \out, ~quadBus.index], ~voices);
                1.5.wait;
            });
            "== Positionstest fertig ==".postln;
        }).play;
    };

    // ---- 3. Umlauf: in welche Richtung wandert der Klang? ------------
    // Eine Drohne läuft in 8 s einmal ganz herum, in 64 Schritten, auf einem
    // Kreis mit Radius ~maxRadiusM (nicht mehr genau kreisfoermig in
    // Pan4-Koordinaten -- ~toQuad normiert x/y unabhaengig ueber ~maxX/
    // ~maxY, ein Kreis in Metern wird dadurch zur Ellipse in Pan4s
    // Koordinatensystem. Fuer diesen Test unerheblich: er prueft nur die
    // UMLAUFRICHTUNG, keine exakte Kreisform).
    //
    // ERWARTUNG: der Klang wandert
    //   vorn -> rechts -> hinten -> links -> vorn,
    // also IM UHRZEIGERSINN von oben gesehen. Die Konsole meldet die vier
    // Viertelpunkte, während er sie passiert.
    //
    // Dieser Test ist der verlässlichere von beiden: eine Bewegungsrichtung
    // hört man auch dann noch eindeutig, wenn eine einzelne Position sich
    // schlecht orten lässt.
    ~testSweep = {
        // Vorgänger abräumen, BEVOR ein neuer Umlauf startet. Der Testsynth
        // steht bewusst nicht in ~drones, der Reaper sieht ihn also nie —
        // wird die Routine unterbrochen oder diese Funktion ein zweites Mal
        // aufgerufen, bliebe der alte Synth sonst bis zum nächsten s.freeAll
        // auf voller Lautstärke hängen. Erst die alte Routine anhalten (sonst
        // schreibt sie weiter auf einen freigegebenen Node und der Server
        // meldet in einer Tour „Node not found"), dann den Synth ins Release.
        ~sweepRoutine !? { |r| r.stop };
        ~sweepSyn     !? { |old| old.set(\gate, 0) };
        ~sweepRoutine = Routine({
            var steps = 64, dur = 8.0, syn;
            var marks = [0, 16, 32, 48], names = ["vorn", "rechts", "hinten", "links"];
            "== Umlauftest: erwartet vorn -> rechts -> hinten -> links ==".postln;
            syn = Synth(\impulseDrone, [
                \freq, 220, \amp, 0.15, \x, 0, \y, ~maxRadiusM,
                \out, ~quadBus.index
            ], ~voices);
            ~sweepSyn = syn;
            steps.do({ |i|
                var a = i / steps * 2pi;
                var idx = marks.indexOf(i);
                idx !? { |k| names[k].postln };
                syn.set(\x, ~maxRadiusM * sin(a), \y, ~maxRadiusM * cos(a));
                (dur / steps).wait;
            });
            syn.set(\gate, 0);
            ~sweepSyn = nil;
            ~sweepRoutine = nil;
            "== Umlauftest fertig ==".postln;
        }).play;
    };

    "KlangNetz Hang-Drum-Bells (Phrygisch ab A2, Pan4 auf % Kanaele, Limiter): hoere auf /net/hitNode und /net/impulse, Port % - bereit.".format(
        s.options.numOutputBusChannels, ~oscListenPort).postln;
    // "Stimmen", nicht "Glocken": ein Eintrag ist die Glocke PLUS ihre fuenf
    // Tails (siehe ~maxPolyphony). Die Synth-Zahl dahinter ist die, die den
    // Server beschaeftigt, und die soll im Startbericht stehen.
    (("Polyphonie: hoechstens % Stimmen (je bis zu % Synths: Glocke +"
        + "% Tails) und % Drohnen gleichzeitig.").format(
        ~maxPolyphony, ~tailDefs.size + 1, ~tailDefs.size, ~droneLimit)).postln;
    ("Sound-Parameter-Layer aktiv: % Parameter unter /klangnetz/param/<name>, Port % - siehe Adressliste im Kopf der Datei.".format(
        ~params.size, ~oscListenPort)).postln;
    "KlangNetz: Panning auf Pan4 umgebaut (2026-07-31), Rotation+Hardware-Kanal-Permutation per NRT-Messung verifiziert - alle vier Boxpositionen 100% scharf, Netzmitte 25/25/25/25%.".postln;
});
)

// ---- Zum Testen ohne imPulse-Verbindung --------------------------------
// (
// NetAddr("127.0.0.1", 8002).sendMsg("/net/hitNode", 3, 1.0, 3.5, 2.0);
// NetAddr("127.0.0.1", 8002).sendMsg("/net/impulse", 42, -5.0, 1.0, 0.8);
// )

// ---- Sound-Parameter-Layer testen (ohne imPulse-Verbindung) ------------
// Adressliste siehe Kopf der Datei ("OSC-STEUERBARER SOUND-PARAMETER-LAYER").
// (
// NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/param/masterVolume", 1.2);
// NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/param/reverbMix", 0.4);
// NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/param/reverbRoom", 0.8);
// NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/param/reverbDamp", 0.3);
// NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/param/brightness", 1.8);
// NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/param/detune", 0.0);
// NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/param/droneLpfMult", 2.0);
// NetAddr("127.0.0.1", 8002).sendMsg("/klangnetz/param/panSharpness", 3.0);
// )

// ---- Kanal-/Positionstest (bei neuer/geaenderter Verkabelung) ---------
// Der Reihe nach auswerten. Ausführlich kommentiert bei den Funktionen
// oben im Block. Braucht eine INTERAKTIVE Sitzung (IDE oder sclang-REPL) —
// headless unter `sclang -D` gibt es nichts, woraus man das aufruft.
// (
// ~testChannels.();
// )
// (
// ~testAzimuth.();
// )
// (
// ~testSweep.();
// )

// ---- Aufräumen -----------------------------------------------------
// (
// ~netHitNodeOscFunc.free;
// OSCdef(\impulseStream).free;
// ~droneReaper.stop;
// ~paramOscDefs.do({ |d| d.free });
// s.freeAll;
// )
