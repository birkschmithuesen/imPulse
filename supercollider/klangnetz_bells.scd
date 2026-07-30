// =====================================================================
// KlangNetz / imPulse — Glockenspiel-Sound für Node-Treffer
// =====================================================================
//
// Empfängt OSC von imPulse (Processing-Sketch) auf Port 8002:
//   /net/hitNode <nodeId:int> <energy:float>
// (siehe LedNetworkTransportEffect.java sendOscMessage(), Adresse in
// imPulse.pde: NetAddress oscOutput = new NetAddress("127.0.0.1", 8002))
//
// Für jeden Node-Treffer wird ein Ton auf einer pentatonischen Tonleiter
// gespielt: kurzer Attack, langer Decay, mehrere leicht unharmonische
// Sinus-Teiltöne — klingt wie ein Glockenspiel.
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
// in einem einzigen (...)-Block.
// =====================================================================

(
// ---- Konfiguration ---------------------------------------------------
~oscListenPort = 8002;

// Pentatonische Tonleiter (Dur-Pentatonik, Halbtonabstände von der Grundnote)
~pentatonicSteps = [0, 2, 4, 7, 9];

// Grundnote + Oktavbereich: angenehm hörbarer Bereich, nicht zu tief/schrill.
// C4 (MIDI 60) als Basis, 3 Oktaven Spielraum nach oben -> ~262 Hz bis ~1976 Hz.
~rootMidiNote = 72; // eine Oktave hoeher (+12 Halbtoene), Birk vor Ort 2026-07-31
~numOctaves = 3;
~notesPerOctaveSet = ~pentatonicSteps.size * ~numOctaves;

// Lautstärke-Mapping: eingehende "energy" (aus imPulse, kann > 1 sein,
// siehe CLAUDE.md "Farbwerte 0..1, Werte >1 erlaubt") auf einen sinnvollen
// Amp-Bereich clampen.
~minAmp = 0.16; // +6dB (Faktor x2), Birk vor Ort 2026-07-31
~maxAmp = 1.0; // +6dB (Faktor x2), Birk vor Ort 2026-07-31

// Zwei globale Klangregler, seit dem Preset-System (2026-07-30) per OSC
// steuerbar. Sie decken die Achse ab, die "hang drum slow" von "bells"
// unterscheidet, ohne die Teilton-Literale in der SynthDef antasten zu müssen:
//   decayScale streckt oder kürzt ALLE Teilton-Decays gemeinsam
//   tilt ist ein Exponent auf die Teilton-Amps: >1 dumpfer, <1 brillanter
// Weil partialAmps[0] gleich 1.0 ist, bleibt der Grundton bei jedem tilt
// gleich laut - tilt regelt nur, wieviel von den oberen Teiltönen übrig
// bleibt.
~decayScale = 1.0;
~tilt = 1.0;

// Ordner der Preset-Dateien. nowExecutingPath ist nil, wenn der Block aus dem
// Editor heraus ohne gespeicherte Datei läuft - dann relativ zum
// Arbeitsverzeichnis.
~presetDir = if (thisProcess.nowExecutingPath.notNil) {
    thisProcess.nowExecutingPath.dirname ++ "/presets"
} {
    "presets"
};

// Grenzen wie in imPulse: der Wert wird geklemmt, nicht abgewiesen.
~clip = { |value, low, high| value.max(low).min(high) };

// notesPerOctaveSet hängt von Tonleiter UND Oktavzahl ab und muss nach jeder
// Änderung an beiden neu gerechnet werden - sonst greift die Node-auf-Ton-
// Abbildung ins Leere.
~recalcScale = { ~notesPerOctaveSet = ~pentatonicSteps.size * ~numOctaves; };

s.waitForBoot({
    // ---- SynthDef: Glockenspiel-Ton ----------------------------------
    // Mehrere Sinus-Partialtöne mit leicht unharmonischen Frequenzverhältnissen
    // (typisch für Glocken-/Metallophon-Klänge) und je eigener Decay-Zeit:
    // höhere Teiltöne klingen schneller ab als der Grundton -> das erzeugt den
    // "Bell"-Charakter (heller Anschlag, der sich schnell zum reinen Grundton
    // hin ausdünnt). Kurzer Attack (3 ms), langer exponentieller Decay.
    SynthDef(\glockenBell, { |freq = 440, amp = 0.3, out = 0, pan = 0,
                             decayScale = 1.0, tilt = 1.0|
        var sig, partials, partialAmps, partialDecays;

        // Frequenzverhältnisse der Teiltöne (1 = Grundton), leicht verstimmt
        // gegenüber ganzzahligen Harmonischen für metallischen Glockenklang.
        partials      = #[1.0, 2.001, 2.998, 4.204, 5.515];
        partialAmps   = #[1.0, 0.55, 0.30, 0.18, 0.10];
        partialDecays = #[3.2, 2.1, 1.35, 0.85, 0.55]; // Sekunden, lang & abnehmend

        sig = Mix.fill(partials.size, { |i|
            var pFreq, pAmp, pDecay, env;
            pFreq  = freq * partials[i];
            // Binäroperator-Form, damit sclang die Zahl zum UGen befördert:
            // partialAmps[i] ist ein Float, tilt ein Control. Float.pow(einUGen)
            // ist dafür nicht verlässlich.
            pAmp   = partialAmps[i] ** tilt;
            pDecay = partialDecays[i] * decayScale;
            // kurzer linearer Attack, langer exponentieller Decay-Tail
            env = EnvGen.kr(
                Env([0, 1, 0], [0.003, pDecay], curve: [\lin, -4]),
                doneAction: if(i == 0) { 2 } { 0 }
            );
            SinOsc.ar(pFreq) * pAmp * env;
        });

        sig = sig * amp;
        sig = Pan2.ar(sig, pan);
        Out.ar(out, sig);
    }).add;

    s.sync;

    // ---- OSC-Empfang: /net/hitNode <nodeId> <energy> -----------------
    // Eigener Listener auf Port 8002 (nicht der sclang-Default 57120), weil
    // imPulse fest an Port 8002 sendet (siehe imPulse.pde).
    ~netHitNodeOscFunc.free; // falls schon vorhanden (Re-Evaluieren beim Live-Coding)

    ~netHitNodeOscFunc = OSCFunc({ |msg, time, addr|
        var nodeId, energy, noteIndex, octave, degree, semitoneOffset, midiNote, freq, amp, pan;

        nodeId = msg[1].asInteger;
        energy = msg[2].asFloat;

        // Node-ID auf einen Ton der pentatonischen Tonleiter über mehrere Oktaven abbilden.
        noteIndex = nodeId % ~notesPerOctaveSet;
        octave = noteIndex div: ~pentatonicSteps.size;
        degree = noteIndex mod: ~pentatonicSteps.size;
        semitoneOffset = ~pentatonicSteps[degree] + (octave * 12);
        midiNote = ~rootMidiNote + semitoneOffset;
        freq = midiNote.midicps;

        // Energie -> Lautstärke, geclampt in einen angenehmen Bereich.
        amp = energy.clip(0.0, 2.0).linlin(0.0, 2.0, ~minAmp, ~maxAmp);

        // leichte Zufalls-Panoramisierung für räumlichen Glockenspiel-Eindruck
        pan = 1.0.rand2;

        Synth(\glockenBell, [\freq, freq, \amp, amp, \pan, pan,
            \decayScale, ~decayScale, \tilt, ~tilt]);

        ("node % -> % Hz (amp %)".format(nodeId, freq.round(0.1), amp.round(0.01))).postln;
    }, '/net/hitNode', recvPort: ~oscListenPort);

    // ---- Preset-Dateien -----------------------------------------------
    // Format wie in imPulse: sechs tabgetrennte Spalten
    //   typ, adresse, beschreibung, wert, min, max
    // Ein Format über beide Hälften. Die Tonleiter ist ein Array und kein
    // Skalar - sie steht als Typ "ints" kommagetrennt in der Wertspalte, das
    // ist die einzige Formaterweiterung gegenüber imPulse.
    //
    // Reihenfolge alphabetisch nach Adresse, genau wie PresetStore.write() in
    // imPulse sortiert.
    ~presetNameValid = { |name|
        name.isString
            and: { name.size > 0 }
            and: { name.size <= 64 }
            and: { name.every({ |char| "abcdefghijklmnopqrstuvwxyz0123456789_-".includes(char) }) };
    };

    ~presetValues = {
        [
            ["float", "/sc/amp/max",         ~maxAmp.asString,               "0",   "1"],
            ["float", "/sc/amp/min",         ~minAmp.asString,               "0",   "1"],
            ["float", "/sc/bell/decayScale", ~decayScale.asString,           "0.1", "4.0"],
            ["float", "/sc/bell/tilt",       ~tilt.asString,                 "0.3", "3.0"],
            ["int",   "/sc/scale/octaves",   ~numOctaves.asString,           "1",   "6"],
            ["int",   "/sc/scale/rootMidi",  ~rootMidiNote.asString,         "24",  "96"],
            ["ints",  "/sc/scale/steps",     ~pentatonicSteps.join(","),     "0",   "11"]
        ];
    };

    // true, wenn die Adresse hierher gehört. Wie in imPulse wird geklemmt,
    // nicht abgewiesen.
    ~presetApply = { |address, value|
        case
        { address == "/sc/scale/steps" } {
            var steps = value.split($,).collect({ |part| part.asInteger });
            if (steps.size > 0) {
                ~pentatonicSteps = steps;
                ~recalcScale.value;
            };
            true;
        }
        { address == "/sc/scale/rootMidi" } {
            ~rootMidiNote = ~clip.value(value.asInteger, 24, 96); true;
        }
        { address == "/sc/scale/octaves" } {
            ~numOctaves = ~clip.value(value.asInteger, 1, 6); ~recalcScale.value; true;
        }
        { address == "/sc/amp/min" } {
            ~minAmp = ~clip.value(value.asFloat, 0.0, 1.0); true;
        }
        { address == "/sc/amp/max" } {
            ~maxAmp = ~clip.value(value.asFloat, 0.0, 1.0); true;
        }
        { address == "/sc/bell/decayScale" } {
            ~decayScale = ~clip.value(value.asFloat, 0.1, 4.0); true;
        }
        { address == "/sc/bell/tilt" } {
            ~tilt = ~clip.value(value.asFloat, 0.3, 3.0); true;
        }
        { true } { false };
    };

    ~presetSave = { |name|
        var path, file;
        if (~presetNameValid.value(name).not) {
            ("SC-Preset-Name unzulaessig: %".format(name)).postln;
        } {
            File.mkdir(~presetDir);
            path = ~presetDir ++ "/" ++ name ++ ".txt";
            file = File(path, "w");
            if (file.isOpen) {
                ~presetValues.value.do({ |row|
                    file.write("%\t%\t\t%\t%\t%\n".format(
                        row[0], row[1], row[2], row[3], row[4]));
                });
                file.close;
                ("SC-Preset gespeichert: %".format(path)).postln;
            } {
                ("SC-Preset nicht schreibbar: %".format(path)).postln;
            };
        };
    };

    ~presetLoad = { |name|
        var path, applied = 0, unknown = [];
        if (~presetNameValid.value(name).not) {
            ("SC-Preset-Name unzulaessig: %".format(name)).postln;
        } {
            path = ~presetDir ++ "/" ++ name ++ ".txt";
            if (File.exists(path).not) {
                // Wie visuell: ein fehlendes Preset hält nichts an, der Klang
                // bleibt wie er ist.
                ("SC-Preset nicht gefunden: % - Klang bleibt unveraendert".format(path)).postln;
            } {
                File.readAllString(path).split($\n).do({ |line|
                    var cols;
                    if (line.stripWhiteSpace.size > 0) {
                        cols = line.split($\t);
                        if (cols.size >= 4) {
                            if (~presetApply.value(cols[1], cols[3])) {
                                applied = applied + 1;
                            } {
                                unknown = unknown.add(cols[1]);
                            };
                        };
                    };
                });
                ("SC-Preset \"%\" geladen: % Werte gesetzt%".format(name, applied,
                    if (unknown.size > 0) { ", unbekannt: " ++ unknown.join(", ") } { "" })).postln;
            };
        };
    };

    // ---- Parameter-Steuerung per OSC ---------------------------------
    // Bewusst derselbe Port wie /net/hitNode: imPulse sendet schon dorthin
    // (oscOutput in imPulse.pde). Der bestehende Listener wird nicht
    // angefasst, es kommen nur neue Adressen dazu - und alles bleibt in
    // DIESEM einen (...)-Block, weil mehrere Top-Level-Blöcke sclang -D
    // aufhängen.
    ~paramOscFuncs.do({ |func| func.free });   // Re-Evaluieren beim Live-Coding
    ~paramOscFuncs = [
        OSCFunc({ |msg|
            var steps = msg[1..].collect({ |value| value.asInteger });
            if (steps.size > 0) {
                ~pentatonicSteps = steps;
                ~recalcScale.value;
                ("scale/steps -> %".format(~pentatonicSteps)).postln;
            } {
                "scale/steps ohne Werte - ignoriert".postln;
            };
        }, '/sc/scale/steps', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~rootMidiNote = ~clip.value(msg[1].asInteger, 24, 96);
            ("scale/rootMidi -> %".format(~rootMidiNote)).postln;
        }, '/sc/scale/rootMidi', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~numOctaves = ~clip.value(msg[1].asInteger, 1, 6);
            ~recalcScale.value;
            ("scale/octaves -> %".format(~numOctaves)).postln;
        }, '/sc/scale/octaves', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~minAmp = ~clip.value(msg[1].asFloat, 0.0, 1.0);
            ("amp/min -> %".format(~minAmp)).postln;
        }, '/sc/amp/min', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~maxAmp = ~clip.value(msg[1].asFloat, 0.0, 1.0);
            ("amp/max -> %".format(~maxAmp)).postln;
        }, '/sc/amp/max', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~decayScale = ~clip.value(msg[1].asFloat, 0.1, 4.0);
            ("bell/decayScale -> %".format(~decayScale)).postln;
        }, '/sc/bell/decayScale', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~tilt = ~clip.value(msg[1].asFloat, 0.3, 3.0);
            ("bell/tilt -> %".format(~tilt)).postln;
        }, '/sc/bell/tilt', recvPort: ~oscListenPort),

        // Zwei mögliche Absender: imPulse bei jedem Preset-Wechsel
        // (PresetManager.forwardToSound) und ein Bediener von aussen.
        OSCFunc({ |msg|
            ~presetLoad.value(msg[1].asString);
        }, '/sc/preset/load', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~presetSave.value(msg[1].asString);
        }, '/sc/preset/save', recvPort: ~oscListenPort)
    ];

    "KlangNetz Glockenspiel: Server gebootet, hoere auf /net/hitNode Port % - bereit.".format(~oscListenPort).postln;
});
)

// ---- Zum Testen ohne imPulse-Verbindung --------------------------------
// (
// NetAddr("127.0.0.1", 8002).sendMsg("/net/hitNode", 3, 1.0);
// )

// ---- Aufräumen -----------------------------------------------------
// (
// ~netHitNodeOscFunc.free;
// s.freeAll;
// )
