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
// =====================================================================
// !!!  ZWEI WERTE IN DIESER DATEI SIND UNGEMESSEN  !!!
// =====================================================================
//
// Stand 2026-07-30: die Datei ist vollständig, aber die Vierkanal-Kette
// ist NOCH NIE an vier echten Lautsprechern gehört worden. Genau zwei
// Werte lassen sich nicht am Schreibtisch feststellen, sondern nur vor
// Ort, und sie stehen beide oben im Konfigurationsteil:
//
//   ~azimuthSign         — zählt der Azimut von PanB2 positiv nach
//                          rechts oder nach links?
//   ~decoderOrientation  — welcher Wert stellt bei DecodeB2 einen
//                          Lautsprecher genau nach vorn, und in welcher
//                          Reihenfolge liegen die vier Kanäle?
//
// Die Hilfedateien von PanB2 und DecodeB2 machen dazu eine Aussage (sie
// steht als Erwartung bei den beiden Variablen), aber das ist eine
// HERLEITUNG, keine Messung. In CLAUDE.md steht für die ArtNet-Bytefolge
// derselbe Fall: zwei unabhängige Ableitungen aus der Dokumentation kamen
// zum falschen Ergebnis, erst die Messung am Aufbau hat es geklärt —
// „Massgeblich ist die Messung, nicht die Herleitung."
//
// Ein falsches Azimut-Vorzeichen spiegelt das ganze Klangbild links/
// rechts, ohne dass irgendetwas schiefgeht oder eine Meldung erscheint.
// Das fällt erfahrungsgemäss erst zur Eröffnung auf.
//
// ZU TUN, BEVOR DIE INSTALLATION ZUM ERSTEN MAL SPIELT — Reihenfolge
// einhalten, Ablauf im Detail bei den Funktionen ganz unten:
//
//   1. Diese Datei auswerten (Server bootet mit vier Ausgangskanälen).
//   2. ~testChannels.()  — welche Box klingt bei Kanal 0, 1, 2, 3?
//   3. ~testAzimuth.()   — kommt „rechts" auch von rechts?
//   4. ~testSweep.()     — wandert der Klang vorn → rechts → hinten → links?
//   5. Ergebnis in ~azimuthSign / ~decoderOrientation eintragen, Datei
//      neu auswerten, 3. und 4. gegenprüfen.
//   6. Die zwei Zeilen „UNGEMESSEN" durch das Messergebnis MIT DATUM
//      ersetzen, damit der nächste nicht wieder von vorn anfängt.
// =====================================================================

(
// ---- Konfiguration ---------------------------------------------------
~oscListenPort = 8002;

// ---- Vierkanal-Ausgang -----------------------------------------------
// numOutputBusChannels wirkt erst beim BOOTEN des Servers. Läuft schon
// einer, muss er neu gestartet werden — sonst bleiben Kanal 2 und 3 stumm,
// ohne Fehlermeldung.
s.options.numOutputBusChannels = 4;

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

// UNGEMESSEN (Stand 2026-07-30). Vorzeichen des Azimut von PanB2.
//   +1 = ein Klang bei x > 0 kommt von rechts   (Erwartung laut Hilfedatei
//        von PanB2: „azimuth ... 0 is forward, +0.5 is right")
//   -1 = umgekehrt, das Klangbild wäre sonst gespiegelt
// Prüfen mit ~testAzimuth.() und ~testSweep.(). Nach dem Ändern MUSS die
// Datei komplett neu ausgewertet werden: der Wert wird beim Bauen der
// SynthDefs fest in den Graphen gerechnet, ein .set auf laufende Synths
// erreicht ihn nicht.
~azimuthSign = 1;

// UNGEMESSEN (Stand 2026-07-30). orientation-Argument von DecodeB2.
//   Erwartung laut Hilfedatei von DecodeB2: 0, wenn ein Lautsprecher genau
//   vorn steht („front is a vertex of the polygon"), 0.5, wenn die Front
//   eine Seite halbiert. Unsere Boxen stehen auf den Seitenmitten des
//   Rechtecks, also mit einer Box genau vorn — das spricht für 0. Der
//   Klassen-Default von DecodeB2 ist dagegen 0.5, ein Vertippen fiele hier
//   also nicht auf.
// Prüfen mit ~testChannels.() und ~testAzimuth.(). Zur Laufzeit umstellbar
// mit ~setOrientation.(0.5) — das legt den Decoder-Synth neu an, weil nicht
// gesichert ist, dass DecodeB2 sein orientation nach dem Start noch liest.
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
    OSCdef(\impulseStream) !? { |d| d.free };
    ~droneReaper !? { |r| r.stop };
    ~decoderSyn  !? { |syn| syn.free };
    ~voices      !? { |g| g.free };   // frei macht auch alle Stimmen darin
    ~bformatBus  !? { |b| b.free };
    ~drones = nil;

    // B-Format-Bus: 2D erster Ordnung, drei Kanäle W X Y. Alle Klangobjekte
    // schreiben hier hinein, EIN Decoder-Synth am Ende macht daraus die vier
    // Lautsprecherkanäle.
    ~bformatBus = Bus.audio(s, 3);
    ~voices = Group.new;

    // ---- Position -> B-Format ----------------------------------------
    // Aus einer Position in Metern wird B-Format. Zwei Grössen:
    //
    //   azimuth in Halbzyklen, 0 = vorn. Unser System hat X nach rechts und
    //   Y nach vorn, PanB2 zählt von vorn — daher x.atan2(y), NICHT
    //   y.atan2(x). Vorzeichen und Nullpunkt sind ungemessen, siehe
    //   ~azimuthSign oben.
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
        azimuth = (px * ~azimuthSign).atan2(py) / pi;
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
    SynthDef(\glockenBell, { |freq = 440, amp = 0.3, out = 0, x = 0, y = 0|
        var sig, partials, partialAmps, partialDecays;

        // Frequenzverhältnisse der Teiltöne (1 = Grundton), leicht verstimmt
        // gegenüber ganzzahligen Harmonischen für metallischen Glockenklang.
        partials      = #[1.0, 2.001, 2.998, 4.204, 5.515];
        partialAmps   = #[1.0, 0.55, 0.30, 0.18, 0.10];
        partialDecays = #[3.2, 2.1, 1.35, 0.85, 0.55]; // Sekunden, lang & abnehmend

        sig = Mix.fill(partials.size, { |i|
            var pFreq, pAmp, pDecay, env;
            pFreq  = freq * partials[i];
            pAmp   = partialAmps[i];
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
    SynthDef(\impulseDrone, { |freq = 220, amp = 0.08, out = 0, x = 0, y = 0, gate = 1|
        var sig, env, xl, yl;
        // Lag VOR der Umrechnung: ein springender Positionswert darf nicht
        // als Klick durchschlagen. Bei 10 Hz Melderate glättet 0.1 s die
        // Sprünge zwischen zwei Meldungen.
        xl = Lag.kr(x, 0.1);
        yl = Lag.kr(y, 0.1);
        env = EnvGen.kr(Env.asr(0.05, 1, 0.3, \sin), gate, doneAction: 2);
        sig = LFTri.ar(freq) * 0.5 + SinOsc.ar(freq * 2.01, 0, 0.2);
        sig = LPF.ar(sig, freq * 6);
        sig = sig * amp * env;
        Out.ar(out, ~toBformat.(sig, xl, yl));
    }).add;

    // ---- SynthDef: der eine Decoder ----------------------------------
    // Genau EIN Decoder am Ende der Kette. Der Default von orientation hier
    // ist nur ein Rückfallwert; der gültige Wert kommt aus ~decoderOrientation
    // und ist ungemessen (siehe Kopf der Datei).
    SynthDef(\b2Decoder, { |in = 0, orientation = 0, amp = 1|
        var bf, sig;
        bf  = In.ar(in, 3);
        sig = DecodeB2.ar(4, bf[0], bf[1], bf[2], orientation) * amp;
        // Schutznetz gegen hartes digitales Clipping. Rechnung: eine Glocke
        // erreicht im Anschlag amp * Summe(partialAmps) = 0.5 * 2.13 ≈ 1.07,
        // und bis zu ~droneLimit = 32 Drohnen à höchstens
        // 2 * ~droneAmpScale = 0.12 laufen gleichzeitig. Die Summe kann also
        // 1.0 überschreiten — sie tut es nur bei zufälliger Gleichphasigkeit,
        // weil die Stimmen auf verschiedenen Tonhöhen liegen, aber „selten"
        // ist kein Schutz. Unterhalb von 0.95 ist der Limiter untätig.
        // Er wirkt je Kanal getrennt (Multichannel-Expansion): setzt er ein,
        // kann das Klangbild kurz wandern. Das ist der Preis dafür, dass ein
        // gekoppelter Vierkanal-Limiter kein Kern-UGen ist, und immer noch
        // besser als hartes Clipping. Preis: Limiter schaut 0,01 s voraus und
        // verzögert damit den GESAMTEN Ausgang um 10 ms — gleichmässig über
        // alle vier Kanäle, das Klangbild bleibt also unberührt.
        sig = Limiter.ar(sig, 0.95, 0.01);
        Out.ar(0, sig);
    }).add;

    // ---- SynthDef: Messhilfe -----------------------------------------
    // Nur für die Messung: Rauschen auf genau einen HARDWARE-Kanal, am
    // B-Format-Bus und am Decoder vorbei. Das ist der Sinn der Sache — hier
    // soll nichts decodiert werden, sondern nur die Verkabelung sichtbar.
    SynthDef(\channelTest, { |chan = 0, amp = 0.2|
        var sig = PinkNoise.ar(amp) * EnvGen.kr(Env.linen(0.05, 0.8, 0.15), doneAction: 2);
        Out.ar(chan, sig);
    }).add;

    s.sync;

    // ---- Decoder starten ---------------------------------------------
    // Der Decoder MUSS hinter den Stimmen laufen, sonst decodiert er einen
    // Bus, in den sie im selben Block noch nicht geschrieben haben.
    ~decoderSyn = Synth.after(~voices, \b2Decoder, [
        \in, ~bformatBus.index, \orientation, ~decoderOrientation
    ]);

    // orientation zur Laufzeit umstellen. Bewusst über Neuanlegen statt über
    // .set: DecodeB2 rechnet die Lautsprecherwinkel möglicherweise beim Start
    // des Synths aus, und ob ein .set sie noch erreicht, ist ungeprüft. Neu
    // anlegen wirkt in jedem Fall.
    ~setOrientation = { |o|
        ~decoderOrientation = o;
        ~decoderSyn.free;
        ~decoderSyn = Synth.after(~voices, \b2Decoder, [
            \in, ~bformatBus.index, \orientation, ~decoderOrientation
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
                        \amp, amp, \out, ~bformatBus.index
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
            \freq, freq, \amp, amp, \x, px, \y, py, \out, ~bformatBus.index
        ], ~voices);

        ("node % -> % Hz (amp %) bei (%, %) m".format(
            nodeId, freq.round(0.1), amp.round(0.01), px.round(0.01), py.round(0.01)
        )).postln;
    }, '/net/hitNode', recvPort: ~oscListenPort);

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
    //    ->  orientation stimmt nicht: ~setOrientation.(0.5) und den Test
    //    wiederholen. Passt es dann, ~decoderOrientation oben auf 0.5.
    //  * die Richtungen sind um 90° oder 180° verdreht, aber nicht
    //    gespiegelt  ->  das ist die Verkabelung, nicht die Rechnung:
    //    mit dem Ergebnis von 1. die Boxen umstecken.
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
            ("== Azimuttest fertig (azimuthSign = %, orientation = %) ==".format(
                ~azimuthSign, ~decoderOrientation)).postln;
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
        Routine({
            var steps = 64, dur = 8.0, syn;
            var marks = [0, 16, 32, 48], names = ["vorn", "rechts", "hinten", "links"];
            "== Umlauftest: erwartet vorn -> rechts -> hinten -> links ==".postln;
            syn = Synth(\impulseDrone, [
                \freq, 220, \amp, 0.15, \x, 0, \y, ~maxRadiusM,
                \out, ~bformatBus.index
            ], ~voices);
            steps.do({ |i|
                var a = i / steps * 2pi;
                var idx = marks.indexOf(i);
                idx !? { |k| names[k].postln };
                syn.set(\x, ~maxRadiusM * sin(a), \y, ~maxRadiusM * cos(a));
                (dur / steps).wait;
            });
            syn.set(\gate, 0);
            ("== Umlauftest fertig (azimuthSign = %) ==".format(~azimuthSign)).postln;
        }).play;
    };

    "KlangNetz: Server gebootet (% Ausgangskanäle), hoere auf /net/hitNode und /net/impulse, Port % - bereit.".format(
        s.options.numOutputBusChannels, ~oscListenPort).postln;
    "ACHTUNG: ~azimuthSign und ~decoderOrientation sind UNGEMESSEN. Vor der ersten Vorstellung ~testChannels.(), ~testAzimuth.(), ~testSweep.() durchlaufen - siehe Kopf der Datei.".postln;
});
)

// ---- Zum Testen ohne imPulse-Verbindung --------------------------------
// (
// NetAddr("127.0.0.1", 8002).sendMsg("/net/hitNode", 3, 1.0, 3.5, 2.0);
// NetAddr("127.0.0.1", 8002).sendMsg("/net/impulse", 42, -5.0, 1.0, 0.8);
// )

// ---- Messung der zwei ungemessenen Werte -------------------------------
// Der Reihe nach auswerten, Ergebnis oben in ~azimuthSign und
// ~decoderOrientation eintragen. Ausführlich kommentiert bei den Funktionen
// oben im Block.
// (
// ~testChannels.();
// )
// (
// ~testAzimuth.();
// )
// (
// ~testSweep.();
// )
// (
// ~setOrientation.(0.5);
// )

// ---- Aufräumen -----------------------------------------------------
// (
// ~netHitNodeOscFunc.free;
// OSCdef(\impulseStream).free;
// ~droneReaper.stop;
// s.freeAll;
// )
