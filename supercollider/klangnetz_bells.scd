// =====================================================================
// KlangNetz / imPulse — Vierkanal-Klang: Glocken an Knoten, Drohnen für
// reisende Impulse, Ambisonics 2D erster Ordnung auf vier Lautsprecher
// =====================================================================
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
// Für jeden Node-Treffer wird ein Ton auf einer pentatonischen Tonleiter
// gespielt: kurzer Attack, langer Decay, mehrere leicht unharmonische
// Sinus-Teiltöne — klingt wie ein Glockenspiel. Jeder reisende Impuls
// bekommt zusätzlich eine leise gehaltene Drohne, die mit ihm wandert.
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
// Die Messsitzung für die ungemessenen Werte weiter unten ist dagegen
// eine INTERAKTIVE: ~testChannels.(), ~testAzimuth.() und ~testSweep.()
// werden von Hand aufgerufen, also aus der SuperCollider-IDE oder aus
// einer sclang-REPL. Im headless-Betrieb (`sclang -D`) gibt es keine
// Eingabezeile, aus der man sie aufrufen könnte — dort läuft die Datei
// nur als Empfänger.
//
// =====================================================================
// !!!  ZWEI WERTE IN DIESER DATEI SIND UNGEMESSEN  !!!
// =====================================================================
//
// Stand 2026-07-30: die Datei ist vollständig, aber die Vierkanal-Kette
// ist NOCH NIE an vier echten Lautsprechern gehört worden. Genau zwei
// Werte lassen sich nicht am Schreibtisch feststellen, sondern nur vor
// Ort, und sie stehen beide oben im Konfigurationsteil:
//
//   ~azimuthSign    — zählt der Azimut von PanB2 positiv nach rechts
//                     oder nach links?
//   ~azimuthOffset  — um wieviel muss das ganze Klangbild gedreht
//                     werden, damit „vorn" auch AUS der vorderen Box
//                     kommt und nicht zwischen zwei Boxen sitzt?
//
// (~decoderOrientation ist KEIN dritter solcher Wert mehr — siehe die
// Messung gleich darunter. Er ist auf SC 3.11.2 nachweislich wirkungslos
// und steht nur noch als Rückfallwert in der Datei.)
//
// ---- GEMESSEN 2026-07-30: DecodeB2 ignoriert sein orientation --------
//
// Aufbau: scsynth im NRT-Modus (`Score.recordNRT`, kein Audiogerät, kein
// Netz), SC 3.11.2, konstantes Eingangssignal DC.ar(1), Rendern nach
// Datei, Abgreifen eines Samples je Messfenster.
//
// Ergebnis: das orientation-Argument von DecodeB2 hat KEINE beobachtbare
// Wirkung. DecodeB2.ar(4, w, x, y, orientation) liefert bei Azimut 0 für
// orientation = 0, 0.25, 0.5 und 1.0 bytegleich
//     [0.8536, 0.8536, 0.1464, 0.1464]
// — und zwar sowohl, wenn der Wert als Konstante im Graphen steht, als
// auch, wenn er als Synth-Control gesetzt wird.
//
// Gegenprobe im GLEICHEN Messaufbau, damit „kein Unterschied" nicht mit
// „misst gar nichts" verwechselt wird: PanAz reagiert sofort auf sein
// orientation. PanAz.ar(4, DC.ar(1), 0, 1, 2, orientation) liefert
//     orientation = 0    ->  [1.0, 0.0, 0.0, 0.0008]
//     orientation = 0.5  ->  [0.7076, 0.7066, 0.0, 0.0]
//
// Daraus folgt die feste Lautsprecheranordnung von DecodeB2 (ebenfalls
// gemessen, durch Abfahren des Azimuts):
//     Azimut -0.25 -> [1.0, 0.5, 0.0, 0.5]     Kanal 0 liegt bei -45°
//     Azimut +0.25 -> [0.5, 1.0, 0.5, 0.0]     Kanal 1 liegt bei +45°
//     Azimut +0.75 -> [0.0, 0.5, 1.0, 0.5]     Kanal 2 liegt bei +135°
//     Azimut -0.75 -> [0.5, 0.0, 0.5, 1.0]     Kanal 3 liegt bei -135°
// Die Boxen des Decoders stehen also bei ±45° und ±135° Halbzyklen — die
// FRONT halbiert eine Seite des Quadrats, sie trifft keine Box.
//
// AUF DEM SHOW-RECHNER NEU ZU BESTÄTIGEN: diese Messung stammt von
// SC 3.11.2 auf einem ANDEREN Rechner (Entwicklungsmaschine, Linux).
// Bevor man sich vor Ort darauf verlässt, mit derselben Gegenprobe
// wiederholen — eine andere SuperCollider-Version kann das Argument
// sehr wohl auswerten, dann sieht die Lage anders aus.
//
// ---- Was das für diese Installation bedeutet -------------------------
//
// Unsere Boxen stehen auf den SEITENMITTEN: vorn, rechts, hinten, links.
// Gegen einen Decoder mit Boxen bei ±45°/±135° sitzt damit jede Quelle
// um 45° daneben — ein Ton, der „vorn" gemeint ist, kommt zu gleichen
// Teilen aus der vorderen und der rechten Box. Umstecken hilft dagegen
// NICHT: eine Drehung um 45° ist keine Vertauschung von Kanälen, kein
// Kabelwechsel bildet sie ab.
//
// Deshalb wird gedreht, wo es nachweislich wirkt: im ENCODER.
// ~azimuthOffset wird in ~toBformat auf den Azimut addiert, bevor PanB2
// ihn zu sehen bekommt. (PanB2 wickelt den Azimut selbst um ±1 herum —
// im selben Lauf gemessen: Azimut 1.25 liefert dasselbe wie -0.75. Ein
// zusätzliches wrap ist also unnötig.)
//
// Dass das wirkt, ist ebenfalls gemessen (gleicher NRT-Aufbau, ganze
// Encoder-Zeile nachgebaut, Quelle bei (0, +4) m — also „vorn"):
//     ~azimuthOffset =  0     ->  [0.675, 0.675, 0.325, 0.325]
//                                 zu gleichen Teilen auf Kanal 0 und 1,
//                                 genau der beschriebene 45°-Fehler
//     ~azimuthOffset = -0.25  ->  [0.748, 0.5,   0.252, 0.5  ]  Gipfel Kanal 0
//     ~azimuthOffset = +0.25  ->  [0.5,   0.748, 0.5,   0.252]  Gipfel Kanal 1
// Dass „vorn" auch bei richtigem Offset noch auf die Nachbarkanäle
// streut, ist kein Fehler: direct = 4/8.062 ≈ 0.50, vorn und hinten sind
// absichtlich nur halb gerichtet (siehe die Anmerkung bei ~testAzimuth).
//
// ---- Warum die zwei übrigen Werte trotzdem ungemessen bleiben --------
//
// Die Hilfedateien von PanB2 und DecodeB2 machen zu Vorzeichen und
// Nullpunkt eine Aussage (sie steht als Erwartung bei den Variablen),
// aber das ist eine HERLEITUNG, keine Messung. In CLAUDE.md steht für die
// ArtNet-Bytefolge derselbe Fall: zwei unabhängige Ableitungen aus der
// Dokumentation kamen zum falschen Ergebnis, erst die Messung am Aufbau
// hat es geklärt — „Massgeblich ist die Messung, nicht die Herleitung."
//
// Ein falsches Azimut-Vorzeichen spiegelt das ganze Klangbild links/
// rechts, ein falscher ~azimuthOffset dreht es. Beides geht ohne Fehler
// und ohne Meldung durch und fällt erfahrungsgemäss erst zur Eröffnung
// auf.
//
// ZU TUN, BEVOR DIE INSTALLATION ZUM ERSTEN MAL SPIELT — Reihenfolge
// einhalten, Ablauf im Detail bei den Funktionen ganz unten. Die ganze
// Sitzung läuft interaktiv (IDE oder sclang-REPL), nicht headless:
//
//   1. Diese Datei auswerten (Server bootet mit vier Ausgangskanälen).
//   2. ~testChannels.()  — welche Box klingt bei Kanal 0, 1, 2, 3?
//   3. ~testAzimuth.()   — sitzt jeder Ton IN der genannten Box?
//   4. Sitzen alle vier Töne ZWISCHEN zwei Boxen: ~azimuthOffset auf
//      +0.25 bzw. -0.25 setzen (welches von beiden, entscheidet 3. —
//      siehe die Anleitung bei ~testAzimuth), Datei neu auswerten,
//      3. wiederholen.
//   5. ~testSweep.()     — wandert der Klang vorn → rechts → hinten → links?
//      Wandert er andersherum, ~azimuthSign = -1 und zurück zu 3.
//   6. Ergebnis in ~azimuthSign / ~azimuthOffset eintragen, Datei neu
//      auswerten, 3. und 5. gegenprüfen.
//   7. Die zwei Zeilen „UNGEMESSEN" durch das Messergebnis MIT DATUM
//      ersetzen, damit der nächste nicht wieder von vorn anfängt.
// =====================================================================
//
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
//     Globaler Gain NACH dem Decoder (DecodeB2), VOR dem Limiter — sitzt
//     im neuen \masterReverb-Synth (siehe unten), der genau an dieser
//     Stelle in der Kette hängt. 1.5 statt 1.0 als Obergrenze, damit sich
//     eine bewusst leise gemischte Szene noch anheben lässt; der Limiter
//     dahinter fängt das ab, bevor es hart clippt.
//
//   /klangnetz/param/reverbMix      0.0 .. 1.0   Default 0.15
//   /klangnetz/param/reverbRoom     0.0 .. 1.0   Default 0.5
//   /klangnetz/param/reverbDamp     0.0 .. 1.0   Default 0.5
//     Hall als FreeVerb NACH dem Decoder, auf den vier fertig decodierten
//     Hardware-Kanälen (Begründung: siehe Kommentar bei \masterReverb
//     weiter unten — kurz zusammengefasst, ein Hall VOR dem Decoder auf
//     dem 3-kanaligen B-Format-Bus würde selbst wieder räumlich codiert
//     und vom Decoder zu einer Phantomquelle verschmiert statt zu einem
//     diffusen Raumklang; FreeVerb je Hardware-Kanal — durch SCs
//     Multichannel-Expansion automatisch vier UNABHÄNGIGE Hallfahnen —
//     ergibt eine echte, dekorrelierte Umhüllung, wie man sie von einem
//     realen Raum erwartet). reverbMix ist der trocken/nass-Regler von
//     FreeVerb selbst (0 = trocken, 1 = nur Hall), reverbRoom die
//     gefühlte Raumgrösse/Nachhallzeit, reverbDamp die Höhendämpfung im
//     Hallschweif (0 = hell/lang nachklingend, 1 = dumpf/kurz).
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

// Halbe Diagonale der Grundfläche 14 × 8 m. Ab diesem Radius ist ein Klang
// vollständig gerichtet.
~maxRadiusM = 8.062;

// Lautsprecher auf den SEITENMITTEN, nicht in den Ecken:
//   (0, +4) vorn   (+7, 0) rechts   (0, -4) hinten   (-7, 0) links
// Die Zuordnung zu den Hardware-Kanälen 0..3 macht DecodeB2 und ist mit
// ~testChannels/~testAzimuth zu messen, nicht hier zu behaupten.

// Obergrenze gleichzeitiger Drohnen, als Netz unter dem oscMaxCount der
// Processing-Seite: kommt der Strom aus einer falsch konfigurierten Quelle,
// soll der Klangrechner nicht in die Knie gehen.
~droneLimit = 32;
// Ohne Meldung für diese Zeit wird eine Drohne freigegeben. Bei 10 Hz
// Melderate also nach vier ausgefallenen Meldungen. Das ist der EINZIGE
// Freigabemechanismus — Processing schickt kein Todes-Signal, weil der Strom
// durch oscMaxCount ohnehin lückenhaft ist.
~droneTimeout = 0.4;

// ---- Die zwei ungemessenen Werte -------------------------------------
// Siehe den Warnblock am Kopf der Datei. Beide stehen hier bewusst
// nebeneinander, damit die Messung an genau einer Stelle eingetragen wird.
// (~decoderOrientation weiter unten gehört NICHT mehr dazu: gemessen
// wirkungslos, siehe Kopf der Datei.)

// UNGEMESSEN (Stand 2026-07-30). Vorzeichen des Azimut von PanB2.
//   +1 = ein Klang bei x > 0 kommt von rechts   (Erwartung laut Hilfedatei
//        von PanB2: „azimuth ... 0 is forward, +0.5 is right")
//   -1 = umgekehrt, das Klangbild wäre sonst gespiegelt
// Prüfen mit ~testAzimuth.() und ~testSweep.(). Nach dem Ändern MUSS die
// Datei komplett neu ausgewertet werden: der Wert wird beim Bauen der
// SynthDefs fest in den Graphen gerechnet, ein .set auf laufende Synths
// erreicht ihn nicht.
~azimuthSign = 1;

// UNGEMESSEN (Stand 2026-07-30). Drehung des ganzen Klangbildes, in
// HALBZYKLEN, addiert in ~toBformat auf den Azimut vor PanB2.
//   Nötig, weil DecodeB2 seine vier Boxen fest bei ±45°/±135° annimmt
//   (GEMESSEN 2026-07-30, siehe Kopf der Datei) und sich das über sein
//   orientation-Argument nicht verstellen lässt. Unsere Boxen stehen aber
//   auf den Seitenmitten. Also dreht der Encoder.
//   Kandidaten — welcher stimmt, entscheidet erst das Ohr vor Ort:
//      0     — falls sich die Boxen doch auf ±45°/±135° stellen lassen
//              oder eine spätere SC-Version das orientation auswertet
//     -0.25  — „vorn" landet auf Decoder-Kanal 0, dann weiter über
//              Kanal 1 (rechts), 2 (hinten), 3 (links)
//     +0.25  — „vorn" landet auf Decoder-Kanal 1, die Zuordnung ist um
//              einen Kanal weitergedreht
//   Welche der beiden 45°-Drehungen die richtige ist, hängt daran, welche
//   physische Box an welchem Hardware-Kanal hängt — deshalb erst
//   ~testChannels.(), dann ~testAzimuth.().
// Prüfen mit ~testAzimuth.() und ~testSweep.(). Nach dem Ändern MUSS die
// Datei komplett neu ausgewertet werden, aus demselben Grund wie bei
// ~azimuthSign: der Wert steht fest im SynthDef-Graphen.
~azimuthOffset = -0.25;

// GEMESSEN 2026-07-30 (scsynth NRT, SC 3.11.2, Entwicklungsrechner):
// das orientation-Argument von DecodeB2 ist WIRKUNGSLOS — der Ausgang ist
// für 0, 0.25, 0.5 und 1.0 bytegleich, als Graphkonstante wie als
// Synth-Control. Gegenprobe im selben Aufbau: PanAz reagiert sofort.
// Ausführlich mit Zahlen im Kopf der Datei.
// Der Wert bleibt hier nur als Rückfallwert stehen, falls eine andere
// SuperCollider-Version das Argument doch auswertet; die Drehung des
// Klangbildes macht ~azimuthOffset oben. AUF DEM SHOW-RECHNER IST DIE
// MESSUNG ZU WIEDERHOLEN, bevor man sich dort darauf verlässt — sie
// stammt von einer anderen Maschine und einer möglicherweise anderen
// SC-Version.
~decoderOrientation = 0;

// Pentatonische Tonleiter (Dur-Pentatonik, Halbtonabstände von der Grundnote)
~pentatonicSteps = [0, 2, 4, 7, 9];

// Grundnote + Oktavbereich: angenehm hörbarer Bereich, nicht zu tief/schrill.
// C4 (MIDI 60) als Basis, 3 Oktaven Spielraum nach oben -> ~262 Hz bis ~1976 Hz.
~rootMidiNote = 60;
~numOctaves = 3;
~notesPerOctaveSet = ~pentatonicSteps.size * ~numOctaves;

// Lautstärke-Mapping: eingehende "energy" (aus imPulse, kann > 1 sein, weil
// jeder Zweig an einer Kreuzung die volle Energie des Elternimpulses erbt,
// siehe CLAUDE.md) auf einen sinnvollen Amp-Bereich clampen.
~minAmp = 0.08;
~maxAmp = 0.5;

// Amp einer Drohne = geclampte Energie mal diesem Faktor. Deutlich leiser als
// eine Glocke: bis zu ~droneLimit Drohnen klingen gleichzeitig, eine Glocke
// steht meist allein.
~droneAmpScale = 0.06;

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
~registerParam.(\reverbMix,    0.15, 0.0, 1.0, nil);
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

s.waitForBoot({
    // ---- Alten Aufbau abräumen ---------------------------------------
    // Nur beim Re-Evaluieren im Live-Coding relevant, beim ersten Lauf sind
    // alle Variablen nil. Ohne das liefen nach dem zweiten Auswerten zwei
    // Decoder-Synths (doppelte Lautstärke) und zwei Reaper-Routinen, und der
    // alte B-Format-Bus bliebe belegt.
    //
    // Die zwei OSC-Empfänger ZUERST abmelden: sie werden weiter unten neu
    // angelegt, und dazwischen sind ~drones und ~bformatBus nil. Käme in
    // diesem Fenster eine Meldung von imPulse, liefe der alte Handler in
    // einen Fehler auf nil.
    ~netHitNodeOscFunc !? { |f| f.free };
    ~noiseTestOscFunc !? { |f| f.free };
    ~noiseTestHardOscFunc !? { |f| f.free };
    OSCdef(\impulseStream) !? { |d| d.free };
    ~droneReaper !? { |r| r.stop };
    ~paramOscDefs !? { |defs| defs.do({ |d| d.free }) };
    ~masterReverbSyn !? { |syn| syn.free };
    ~decoderSyn  !? { |syn| syn.free };
    ~voices      !? { |g| g.free };   // frei macht auch alle Stimmen darin
    ~bformatBus  !? { |b| b.free };
    ~decodedBus  !? { |b| b.free };
    ~drones = nil;

    // B-Format-Bus: 2D erster Ordnung, drei Kanäle W X Y. Alle Klangobjekte
    // schreiben hier hinein, EIN Decoder-Synth am Ende macht daraus die vier
    // Lautsprecherkanäle.
    ~bformatBus = Bus.audio(s, 3);
    // 4-kanaliger Zwischenbus zwischen \b2Decoder und \masterReverb — siehe
    // Begründung der Kettenreihenfolge beim \masterReverb-SynthDef.
    ~decodedBus = Bus.audio(s, 4);
    ~voices = Group.new;

    // ---- Position -> B-Format ----------------------------------------
    // Aus einer Position in Metern wird B-Format. Zwei Grössen:
    //
    //   azimuth in Halbzyklen, 0 = vorn. Unser System hat X nach rechts und
    //   Y nach vorn, PanB2 zählt von vorn — daher x.atan2(y), NICHT
    //   y.atan2(x). Vorzeichen und Nullpunkt sind ungemessen, siehe
    //   ~azimuthSign oben.
    //
    //   ~azimuthOffset dreht das ganze Klangbild, hier und nur hier. Die
    //   Drehung gehört in den ENCODER, weil das orientation-Argument des
    //   Decoders gemessen wirkungslos ist (siehe Kopf der Datei). Kein
    //   wrap nötig: PanB2 wickelt den Azimut selbst um ±1 herum, ebenfalls
    //   gemessen.
    //
    //   direct = Richtwirkung. Bei erster Ordnung IST die Richtwirkung das
    //   Verhältnis der X/Y-Anteile zum W-Anteil, eine Multiplikation genügt
    //   also. Bei direct = 0 bleibt nur der omnidirektionale Anteil und der
    //   Klang kommt aus allen vier Boxen — genau richtig für einen Knoten
    //   senkrecht über der Netzmitte, der nicht aus einer Richtung kommen
    //   soll, sondern von überall. Am Rand wird er zur ebenen Welle.
    //
    //   Keine Abstandsdämpfung: ein Knoten am Netzrand soll nicht leiser
    //   sein als einer in der Mitte, die Lautstärke kommt allein aus energy.
    ~toBformat = { |sig, px, py|
        var azimuth, radius, direct, bf;
        azimuth = ((px * ~azimuthSign).atan2(py) / pi) + ~azimuthOffset;
        radius  = px.hypot(py);
        direct  = (radius / ~maxRadiusM).clip(0, 1);
        bf = PanB2.ar(sig, azimuth, 1);
        [bf[0], bf[1] * direct, bf[2] * direct]
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
        var sig, partials, partialAmps, partialDecays, harmonicPartials;

        // Frequenzverhältnisse der Teiltöne (1 = Grundton), leicht verstimmt
        // gegenüber ganzzahligen Harmonischen für metallischen Glockenklang.
        partials      = #[1.0, 2.001, 2.998, 4.204, 5.515];
        // Rein harmonische Gegenprobe (1:2:3:4:5) — Ziel von detune = 0.
        harmonicPartials = #[1.0, 2.0, 3.0, 4.0, 5.0];
        partialAmps   = #[1.0, 0.55, 0.30, 0.18, 0.10];
        partialDecays = #[3.2, 2.1, 1.35, 0.85, 0.55]; // Sekunden, lang & abnehmend

        sig = Mix.fill(partials.size, { |i|
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

        sig = sig * amp;
        // Position je Ton fest — eine Glocke wandert nicht. Kein Lag nötig.
        Out.ar(out, ~toBformat.(sig, x, y));
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
        Out.ar(out, ~toBformat.(sig, xl, yl));
    }).add;

    // ---- SynthDef: der eine Decoder ----------------------------------
    // Genau EIN Decoder am Ende der Kette. Das orientation-Argument ist auf
    // SC 3.11.2 gemessen WIRKUNGSLOS (siehe Kopf der Datei) — es bleibt
    // verdrahtet, damit sich die Messung vor Ort mit ~setOrientation.() in
    // einem Aufruf nachhören lässt. Gedreht wird das Klangbild woanders,
    // nämlich mit ~azimuthOffset im Encoder.
    //
    // Schreibt NICHT mehr direkt auf die Hardware-Kanäle (Out.ar(0, ...)),
    // sondern auf ~decodedBus — dazwischen hängt jetzt \masterReverb (Hall +
    // Master-Volume + Limiter), siehe dort für die Begründung der Reihenfolge.
    SynthDef(\b2Decoder, { |in = 0, orientation = 0, amp = 1, out = 0|
        var bf, sig;
        bf  = In.ar(in, 3);
        sig = DecodeB2.ar(4, bf[0], bf[1], bf[2], orientation) * amp;
        Out.ar(out, sig);
    }).add;

    // ---- SynthDef: Master-Volume + Reverb + Limiter --------------------
    // Fester Anschlusspunkt NACH dem Decoder, auf den vier fertig
    // decodierten Hardware-Kanälen — bewusst NICHT auf dem 3-kanaligen
    // B-Format-Bus davor. Zwei Gründe:
    //
    //  1. Ein Hall VOR dem Decoder würde selbst als B-Format kodiert
    //     (PanB2 kennt nur Punktquellen, kein FreeVerb-Ausgang ist eine
    //     solche) und beim Decodieren wieder zu einer einzigen Phantom-
    //     richtung verschmiert statt zu einem diffusen, allseitigen
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
        // einzelne Glocke ist amp * Summe(partialAmps) = 0.5 * 2.13 ≈ 1.07
        // die OBERE SCHRANKE — erreicht wird sie nie, weil die fünf
        // Teiltöne auf nicht ganzzahligen Verhältnissen liegen und nie
        // gleichzeitig im Scheitel stehen. GEMESSEN 2026-07-30 (scsynth
        // NRT, SC 3.11.2, Signalkette dieser SynthDef mono nachgebaut,
        // Scheitel über die ersten 0.5 s, alle 15 verwendeten Tonhöhen):
        // der tatsächliche Scheitel liegt bei amp = 0.5 zwischen 0.85 und
        // 0.87, Grösstwert 0.864 bei 880 Hz.
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
        // Decoder-Kanal-Reihenfolge, sondern eine echte Vertauschung.
        // Gemessene Kabel-Zuordnung (per \channelTest auf jedem
        // Hardware-Kanal einzeln, Birk hat pro Kanal die klingende Box
        // benannt): hw0=rechts, hw1=links, hw2=hinten, hw3=vorn.
        // Bei ~azimuthOffset = -0.25 liefert der Decoder seine vier
        // logischen Kanaele in der Reihenfolge D0=vorn, D1=rechts,
        // D2=hinten, D3=links (siehe ~azimuthOffset-Kommentar oben, per
        // NRT-Sweep auf dem Hermes-vServer bestaetigt). Diese zwei
        // Zuordnungen zusammen ergeben eine reine Kanal-Permutation vor
        // dem Hardware-Output — kein weiterer Dreh-Offset kann das
        // abbilden, weil hw0/hw1 und D0/D1 in UNTERSCHIEDLICHER
        // Reihenfolge um den Kreis laufen:
        //   hw0 (rechts) <- D1
        //   hw1 (links)  <- D3
        //   hw2 (hinten) <- D2
        //   hw3 (vorn)   <- D0
        // AUF EINEM ANDEREN INTERFACE/BEI ANDERER VERKABELUNG NEU MESSEN
        // (siehe klangnetz-remote-control Skill, Abschnitt "Schicht 0" für
        // den Kanaltest-Workflow) — diese Permutation ist an das ZOOM
        // AMS-24 und seine aktuelle Verkabelung gebunden, keine
        // allgemeine Ambisonics-Eigenschaft.
        permuted = [sig[1], sig[3], sig[2], sig[0]];
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
    // ~toBformat-Encoder wie Glocken/Drohnen — Rauschen ist deutlich
    // leichter zu orten als ein Sinuston.
    SynthDef(\noiseBformat, { |amp = 0.4, out = 0, x = 0, y = 0|
        var sig, env;
        env = EnvGen.kr(Env.linen(0.05, 1.5, 0.3), doneAction: 2);
        sig = PinkNoise.ar(1) * amp * env;
        Out.ar(out, ~toBformat.(sig, x, y));
    }).add;

    // ---- SynthDef: Rausch-Testton HART gepannt (voll gerichtet) --------
    // Fuer die Testabnahme deutlicher als \noiseBformat: erzwingt
    // direct = 1 unabhaengig vom Radius, damit auch eine Position nahe
    // der Netzmitte (kleiner Radius, normalerweise diffus) genauso
    // scharf geortet werden kann wie eine Position am Rand. Nur fuer
    // Testzwecke -- Nodes/Drohnen behalten die normale, radiusabhaengige
    // Richtwirkung ueber ~toBformat.
    SynthDef(\noiseBformatHard, { |amp = 0.4, out = 0, x = 0, y = 0|
        var sig, env, azimuth, bf;
        env = EnvGen.kr(Env.linen(0.05, 1.5, 0.3), doneAction: 2);
        sig = PinkNoise.ar(1) * amp * env;
        azimuth = ((x * ~azimuthSign).atan2(y) / pi) + ~azimuthOffset;
        bf = PanB2.ar(sig, azimuth, 1);
        Out.ar(out, bf); // direct = 1, kein radiusabhaengiges Daempfen von bf[1]/bf[2]
    }).add;

    s.sync;

    // ---- Decoder starten ---------------------------------------------
    // Der Decoder MUSS hinter den Stimmen laufen, sonst decodiert er einen
    // Bus, in den sie im selben Block noch nicht geschrieben haben.
    ~decoderSyn = Synth.after(~voices, \b2Decoder, [
        \in, ~bformatBus.index, \orientation, ~decoderOrientation, \out, ~decodedBus.index
    ]);

    // ---- Master-Volume + Reverb starten --------------------------------
    // MUSS hinter dem Decoder laufen (derselbe Grund wie oben: sonst liest
    // er ~decodedBus, bevor \b2Decoder im selben Block hineingeschrieben
    // hat) und ist der letzte Synth vor der Hardware — Out.ar(0, ...) im
    // SynthDef selbst.
    ~masterReverbSyn = Synth.after(~decoderSyn, \masterReverb, [
        \in, ~decodedBus.index, \out, 0,
        \masterVolume, ~masterVolume, \reverbMix, ~reverbMix,
        \reverbRoom, ~reverbRoom, \reverbDamp, ~reverbDamp
    ]);

    // orientation zur Laufzeit umstellen. Bewusst über Neuanlegen statt über
    // .set: DecodeB2 rechnet die Lautsprecherwinkel möglicherweise beim Start
    // des Synths aus, und ob ein .set sie noch erreicht, ist ungeprüft. Neu
    // anlegen wirkt in jedem Fall.
    //
    // ACHTUNG: auf SC 3.11.2 ändert das GAR NICHTS — gemessen, siehe Kopf
    // der Datei; auch das Neuanlegen half dort nicht, weil schon die
    // Graphkonstante keine Wirkung hatte. Diese Funktion ist deshalb KEIN
    // Werkzeug mehr, um eine 45°-Drehung zu beheben (das macht
    // ~azimuthOffset). Sie ist genau noch für eines gut: die Messung auf
    // dem Show-Rechner nachzuhören. Ändert sich beim Aufruf hörbar etwas,
    // dann wertet die dortige SuperCollider-Version das Argument doch aus
    // und die Messung von 2026-07-30 gilt dort nicht — das wäre ein Befund,
    // der in diese Datei gehört.
    ~setOrientation = { |o|
        ~decoderOrientation = o;
        ~decoderSyn.free;
        ~decoderSyn = Synth.after(~voices, \b2Decoder, [
            \in, ~bformatBus.index, \orientation, ~decoderOrientation, \out, ~decodedBus.index
        ]);
        ("Decoder neu angelegt mit orientation = %".format(o)).postln;
    };

    // ---- Drohnenverwaltung -------------------------------------------
    // Impuls-ID -> (synth: ..., lastSeen: ...)
    ~drones = IdentityDictionary.new;

    // Tonhöhe je Impuls aus seiner ID, auf derselben pentatonischen Leiter
    // wie die Glocken, aber eine Oktave darunter — so liegen die Drohnen
    // unter dem Glockenspiel statt dazwischen.
    ~droneFreq = { |id|
        var idx = id.abs;
        var step = ~pentatonicSteps[idx % ~pentatonicSteps.size];
        var oct = idx.div(~pentatonicSteps.size) % ~numOctaves;
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
                        \amp, amp, \out, ~bformatBus.index, \lpfMult, ~droneLpfMult
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
        var nodeId, energy, px, py, noteIndex, octave, degree, semitoneOffset, midiNote, freq, amp;

        nodeId = msg[1].asInteger;
        energy = msg[2].asFloat;

        // x und y sind erst seit Aufgabe 14 dabei. Die Absicherung lässt die
        // Datei auch mit einem älteren Processing-Stand laufen, der noch
        // keine Koordinaten schickt — dann klingt alles aus der Mitte.
        px = if (msg.size > 3) { msg[3].asFloat } { 0 };
        py = if (msg.size > 4) { msg[4].asFloat } { 0 };

        // Node-ID auf einen Ton der pentatonischen Tonleiter über mehrere Oktaven abbilden.
        noteIndex = nodeId % ~notesPerOctaveSet;
        octave = noteIndex div: ~pentatonicSteps.size;
        degree = noteIndex mod: ~pentatonicSteps.size;
        semitoneOffset = ~pentatonicSteps[degree] + (octave * 12);
        midiNote = ~rootMidiNote + semitoneOffset;
        freq = midiNote.midicps;

        // Energie -> Lautstärke, geclampt in einen angenehmen Bereich.
        amp = energy.clip(0.0, 2.0).linlin(0.0, 2.0, ~minAmp, ~maxAmp);

        // Die frühere Zufalls-Panoramisierung entfällt: sie war der
        // Platzhalter für genau diese Knotenposition.
        Synth(\glockenBell, [
            \freq, freq, \amp, amp, \x, px, \y, py, \out, ~bformatBus.index,
            \brightness, ~brightness, \detune, ~detune
        ], ~voices);

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
        Synth(\noiseBformat, [\amp, amp, \x, px, \y, py, \out, ~bformatBus.index], ~voices);
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
        Synth(\noiseBformatHard, [\amp, amp, \x, px, \y, py, \out, ~bformatBus.index], ~voices);
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
    // der Verkabelung oder an DecodeB2 liegt.

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

    // ---- 2. Azimut: kommt „rechts" von rechts? -----------------------
    // Ein Glockenton nacheinander an den vier Lautsprecherpositionen, je
    // 1,5 s, die Richtung erscheint auf der Konsole.
    //
    // ERWARTUNG, wenn beide Werte stimmen: der Ton kommt jeweils aus genau
    // der Box, deren Name auf der Konsole steht.
    //
    // WAS ZU ÄNDERN IST:
    //  * „rechts" kommt aus der LINKEN Box und „links" aus der rechten,
    //    vorn und hinten stimmen  ->  Azimut ist gespiegelt:
    //    ~azimuthSign = -1 setzen und die Datei neu auswerten.
    //  * jeder der vier Töne sitzt ZWISCHEN zwei Boxen, um 45° verdreht
    //    ->  das ist der ERWARTETE Ausgangszustand bei ~azimuthOffset = 0,
    //    kein Defekt: DecodeB2 stellt seine Boxen fest auf ±45°/±135°,
    //    unsere stehen auf den Seitenmitten (gemessen, siehe Kopf der
    //    Datei). Abhilfe ist ~azimuthOffset, NICHT ~setOrientation — das
    //    ist auf SC 3.11.2 wirkungslos. Welche der beiden 45°-Drehungen es
    //    braucht, sagt der Test selbst:
    //      - „vorn" sitzt zwischen der vorderen und der RECHTEN Box
    //        (also im Uhrzeigersinn zu weit)  ->  ~azimuthOffset = -0.25
    //      - „vorn" sitzt zwischen der vorderen und der LINKEN Box
    //        (gegen den Uhrzeigersinn zu weit)  ->  ~azimuthOffset = +0.25
    //    Danach die Datei NEU AUSWERTEN (der Wert steht fest im Graphen,
    //    ein .set erreicht ihn nicht) und diesen Test wiederholen: jetzt
    //    muss jeder Ton IN der genannten Box stehen.
    //    Bei ~azimuthOffset = 0 klingen alle vier Töne gleich diffus; bei
    //    richtig gesetztem Offset klingen sie deutlich punktförmiger — das
    //    ist neben der Richtung das zweite Merkmal, an dem man es hört.
    //  * die Richtungen sind um 90° oder 180° verdreht, aber nicht
    //    gespiegelt und nicht um 45° verschoben  ->  das ist die
    //    Verkabelung, nicht die Rechnung: mit dem Ergebnis von 1. die
    //    Boxen umstecken. (Eine 45°-Drehung dagegen bekommt man durch
    //    Umstecken NIE weg — dafür ist ~azimuthOffset da.)
    //
    // Zu erwarten und KEIN Fehler: „vorn" und „hinten" klingen diffuser als
    // „rechts" und „links". Die Boxen stehen bei (0, ±4) und (±7, 0), und
    // direct = radius / ~maxRadiusM ist damit 4/8.062 ≈ 0.50 gegen
    // 7/8.062 ≈ 0.87 — vorn und hinten sind absichtlich nur halb gerichtet.
    // Für die Frage nach dem Vorzeichen (links/rechts) ist das unerheblich;
    // wer es eindeutiger braucht, nimmt 3.
    ~testAzimuth = {
        Routine({
            "== Azimuttest: kommt der Ton aus der genannten Box? ==".postln;
            [[0, 4, "vorn"], [7, 0, "rechts"], [0, -4, "hinten"], [-7, 0, "links"]].do({ |p|
                p[2].postln;
                Synth(\glockenBell, [\freq, 440, \amp, 0.3, \x, p[0], \y, p[1],
                    \out, ~bformatBus.index], ~voices);
                1.5.wait;
            });
            ("== Azimuttest fertig (azimuthSign = %, azimuthOffset = %) ==".format(
                ~azimuthSign, ~azimuthOffset)).postln;
        }).play;
    };

    // ---- 3. Umlauf: in welche Richtung wandert der Klang? ------------
    // Eine Drohne läuft in 8 s einmal ganz herum, in 64 Schritten, auf einem
    // Kreis mit Radius ~maxRadiusM. Voller Radius mit Absicht: so ist
    // direct = 1 auf dem ganzen Weg und die Richtwirkung ändert sich
    // unterwegs nicht — der Kreis liegt damit ausserhalb der Grundfläche,
    // was für einen Testton egal ist und die Ortung am saubersten macht.
    //
    // ERWARTUNG, wenn ~azimuthSign stimmt: der Klang wandert
    //   vorn -> rechts -> hinten -> links -> vorn,
    // also IM UHRZEIGERSINN von oben gesehen. Die Konsole meldet die vier
    // Viertelpunkte, während er sie passiert.
    //
    // WAS ZU ÄNDERN IST: wandert er stattdessen vorn -> links -> hinten ->
    // rechts (gegen den Uhrzeigersinn), ist das Vorzeichen umgekehrt:
    // ~azimuthSign = -1 setzen und die Datei neu auswerten.
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
                \out, ~bformatBus.index
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
            ("== Umlauftest fertig (azimuthSign = %, azimuthOffset = %) ==".format(
                ~azimuthSign, ~azimuthOffset)).postln;
        }).play;
    };

    "KlangNetz: Server gebootet (% Ausgangskanäle), hoere auf /net/hitNode und /net/impulse, Port % - bereit.".format(
        s.options.numOutputBusChannels, ~oscListenPort).postln;
    ("Sound-Parameter-Layer aktiv: % Parameter unter /klangnetz/param/<name>, Port % - siehe Adressliste im Kopf der Datei.".format(
        ~params.size, ~oscListenPort)).postln;
    ("ACHTUNG: ~azimuthSign (= %) und ~azimuthOffset (= %) sind UNGEMESSEN. Vor der ersten Vorstellung ~testChannels.(), ~testAzimuth.(), ~testSweep.() durchlaufen - interaktiv in der IDE oder einer sclang-REPL, siehe Kopf der Datei.".format(
        ~azimuthSign, ~azimuthOffset)).postln;
    "HINWEIS: ~decoderOrientation ist auf SC 3.11.2 GEMESSEN wirkungslos (NRT-Messung 2026-07-30, Gegenprobe mit PanAz). Gedreht wird mit ~azimuthOffset im Encoder. Auf dem Show-Rechner ist die Messung zu wiederholen.".postln;
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
// )

// ---- Messung der zwei ungemessenen Werte -------------------------------
// Der Reihe nach auswerten, Ergebnis oben in ~azimuthSign und
// ~azimuthOffset eintragen. Ausführlich kommentiert bei den Funktionen
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
// Nur noch als Gegenprobe der NRT-Messung auf dem Show-Rechner: ändert der
// folgende Aufruf HÖRBAR etwas, wertet die dortige SuperCollider-Version das
// orientation-Argument von DecodeB2 doch aus und die Messung von 2026-07-30
// gilt dort nicht. Zum Beheben einer 45°-Drehung taugt er NICHT — dafür ist
// ~azimuthOffset da.
// (
// ~setOrientation.(0.5);
// )

// ---- Aufräumen -----------------------------------------------------
// (
// ~netHitNodeOscFunc.free;
// OSCdef(\impulseStream).free;
// ~droneReaper.stop;
// ~paramOscDefs.do({ |d| d.free });
// s.freeAll;
// )
