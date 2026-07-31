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
//   /net/impulse <impulseId:int> <x:float>      <y:float> <energy:float>
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
// Sieben Klangparameter sind zur Laufzeit per OSC einstellbar, unabhängig
// vom imPulse-Netz-Traffic (eigener Port, eigenes Adress-Präfix). Jeder
// Parameter klemmt eingehende Werte auf einen festen Bereich (siehe unten)
// und quittiert den Empfang mit einer postln-Zeile — das ist bewusst KEIN
// generisches Framework wie `AbstractParameter.java`/`remoteSettings.txt`
// auf der imPulse-Seite, sondern eine kleine, für genau diese Datei
// gebaute Registry (~params, ~registerParam), siehe deren Definition
// weiter unten. Die Liste hier ist die Referenz für eine spätere
// WebUI-Anbindung (siehe `webui/server.py`, das aktuell nur an Port 8001
// sendet, also an imPulse — für diese Klangparameter bräuchte es einen
// zweiten Sender an Port 8002 mit dieser Adressliste).
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
~maxPolyphony = 24;
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
            brightness = 1.0, detune = 1.0|
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

        sig = (bellSig + grit) * amp;
        // Position je Ton fest — eine Glocke wandert nicht. Kein Lag nötig.
        Out.ar(out, ~toQuad.(sig, x, y));
    }).add;

    // ---- SynthDef: Drohne eines reisenden Impulses --------------------
    // Leiser gehaltener Ton für einen reisenden Impuls. Die Klangfarbe ist
    // ein schlichter Startpunkt und ausdrücklich zum Ersetzen gedacht — die
    // Mechanik drumherum ist der Teil, der stimmen muss.
    SynthDef(\impulseDrone, { |freq = 220, amp = 0.08, out = 0, x = 0, y = 0, gate = 1,
            lpfMult = 6|
        var sig, env, xl, yl, ampl, lpfMultLagged;
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
        env = EnvGen.kr(Env.asr(0.05, 1, 0.3, \sin), gate, doneAction: 2);
        sig = LFTri.ar(freq) * 0.5 + SinOsc.ar(freq * 2.01, 0, 0.2);
        sig = LPF.ar(sig, freq * lpfMultLagged);
        sig = sig * ampl * env;
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
        sig = Limiter.ar(sig, 0.95, 0.01);
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
        var entry = ~drones[id];
        // energy ist NICHT durch 1 begrenzt: an einer Kreuzung erbt jeder
        // Zweig die volle Energie des Elternimpulses, sie vervielfacht sich
        // also mit jeder Aufspaltung. Deshalb hier clampen.
        var amp = energy.clip(0, 2) * ~droneAmpScale;
        if (entry.notNil) {
            entry[\synth].set(\x, px, \y, py, \amp, amp);
            entry[\lastSeen] = Main.elapsedTime;
        } {
            // Über der Obergrenze wird die Meldung still verworfen. Der
            // Impuls bekommt dann auch bei späteren Meldungen keine Drohne
            // mehr, bis wieder Platz frei ist — das ist gewollt: die Grenze
            // soll den Rechner schützen, nicht Stimmen umschichten.
            if (~drones.size < ~droneLimit) {
                ~drones[id] = (
                    synth: Synth(\impulseDrone, [
                        \freq, ~droneFreq.(id), \x, px, \y, py,
                        \amp, amp, \out, ~quadBus.index, \lpfMult, ~droneLpfMult
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
            midiNote, freq, amp, newBell;

        nodeId = msg[1].asInteger;
        energy = msg[2].asFloat;

        // x und y sind erst seit Aufgabe 14 dabei. Die Absicherung lässt die
        // Datei auch mit einem älteren Processing-Stand laufen, der noch
        // keine Koordinaten schickt — dann klingt alles aus der Mitte.
        px = if (msg.size > 3) { msg[3].asFloat } { 0 };
        py = if (msg.size > 4) { msg[4].asFloat } { 0 };

        // Node-ID auf einen Ton der Tonleiter über mehrere Oktaven abbilden.
        noteIndex = nodeId % ~notesPerOctaveSet;
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
        if (~activeBells.size >= ~maxPolyphony) {
            ~activeBells.removeAt(0).free;
        };

        // Die frühere Zufalls-Panoramisierung entfällt: sie war der
        // Platzhalter für genau diese Knotenposition.
        newBell = Synth(\glockenBell, [
            \freq, freq, \amp, amp, \x, px, \y, py, \out, ~quadBus.index,
            \brightness, ~brightness, \detune, ~detune
        ], ~voices);
        ~activeBells.add(newBell);
        // Natuerliches Verklingen (doneAction 2 im SynthDef) muss
        // ~activeBells auch aufraeumen, sonst waechst die Liste dauerhaft
        // weiter und removeAt(0) wuergt laengst verklungene Synths ab
        // (harmlos, aber sinnlos -- das echte Voice-Stealing traefe dann die
        // falschen).
        newBell.onFree({ ~activeBells.remove(newBell); });

        ("node % -> % Hz (amp %) bei (%, %) m".format(
            nodeId, freq.round(0.1), amp.round(0.01), px.round(0.01), py.round(0.01)
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
    // Jede Nachricht bringt EINEN Float als erstes Argument, wird auf
    // [lo, hi] geklemmt, in die Environment-Variable geschrieben, an einen
    // etwaigen onSet-Callback gereicht und dann an den laufenden
    // \masterReverb-Synth weitergegeben (der einzige Sink, der die vier
    // reinen Environment-Parameter masterVolume/reverbMix/reverbRoom/
    // reverbDamp live braucht — brightness/detune/droneLpfMult werden
    // anders zugestellt, siehe deren Kommentare bei den SynthDefs).
    // recvPort ist ~oscListenPort, derselbe wie /net/hitNode — ein OSCdef
    // pro Adresse ist die feinste Granularität, die SC anbietet, ein
    // gemeinsamer Empfänger für alle sieben Adressen bräuchte selbst
    // wieder eine Verzweigung nach Adresse und würde nur Code verschieben.
    ~paramOscDefs = ~params.keys.collect({ |name|
        OSCdef(
            ("klangnetzParam_" ++ name).asSymbol,
            { |msg|
                var entry = ~params[name];
                var val = msg[1].asFloat.clip(entry[\lo], entry[\hi]);
                currentEnvironment[name] = val;
                entry[\onSet] !? { |cb| cb.(val) };
                // Die vier Master-/Reverb-Parameter wirken über den
                // dauerhaft laufenden \masterReverb-Synth — ein .set auf
                // alle vier ist billig und robust gegen Aufrufreihenfolge
                // (unschädlich, wenn ein SynthDef-Argumentname bei diesem
                // Parameter gar nicht existiert, .set ignoriert unbekannte
                // Keys still).
                ~masterReverbSyn !? { |syn| syn.set(name, val) };
                ("klangnetz param % -> %".format(name, val.round(0.001))).postln;
            },
            ("/klangnetz/param/" ++ name).asSymbol,
            recvPort: ~oscListenPort
        );
    });

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
    ("Polyphonie: hoechstens % Glocken und % Drohnen gleichzeitig.".format(
        ~maxPolyphony, ~droneLimit)).postln;
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
