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

// Limiter am Summenbus (Birk vor Ort 2026-07-31): bei vielen gleichzeitig
// klingenden Glocken summiert sich das Signal und kann uebersteuern/
// verzerren, auch wenn jede einzelne Stimme innerhalb ~maxAmp bleibt.
// ~limiterCeiling ist die harte Ausgabegrenze (0.95 statt 1.0 als kleiner
// Sicherheitsabstand zur digitalen Vollausteuerung).
~limiterCeiling = 0.95;

s.waitForBoot({
    // ---- Gruppen fuer garantierte Ausfuehrungsreihenfolge ---------------
    // Synth.new haengt standardmaessig an den KOPF (addToHead) der Ziel-
    // gruppe -- ohne explizite Gruppen wuerde ein spaeter gestarteter
    // Glocken-Synth VOR dem einmalig gestarteten Limiter landen und der
    // Limiter wuerde ein leeres/veraltetes Signal lesen. Zwei Gruppen lösen
    // das robust, unabhaengig vom Spawn-Zeitpunkt einzelner Glocken:
    // ~sourceGroup (Glocken) immer VOR ~fxGroup (Limiter) in der Node-Order.
    ~sourceGroup.free;
    ~fxGroup.free;
    ~sourceGroup = Group.new(s);
    ~fxGroup = Group.after(~sourceGroup);

    // ---- Privater Summenbus: alle Glocken schreiben hierher, nicht mehr
    // direkt auf den Hardware-Ausgang -- der Limiter liest von hier und
    // schreibt erst danach auf out 0/1.
    ~mixBus.free;
    ~mixBus = Bus.audio(s, 2);

    // ---- SynthDef: Glockenspiel-Ton ----------------------------------
    // Mehrere Sinus-Partialtöne mit leicht unharmonischen Frequenzverhältnissen
    // (typisch für Glocken-/Metallophon-Klänge) und je eigener Decay-Zeit:
    // höhere Teiltöne klingen schneller ab als der Grundton -> das erzeugt den
    // "Bell"-Charakter (heller Anschlag, der sich schnell zum reinen Grundton
    // hin ausdünnt). Kurzer Attack (3 ms), langer exponentieller Decay.
    // out zeigt jetzt auf ~mixBus (Default per \out-Arg beim Synth-Start
    // gesetzt) statt direkt auf den Hardware-Ausgang -- der Limiter sitzt
    // dazwischen, siehe \klangLimiter unten.
    SynthDef(\glockenBell, { |freq = 440, amp = 0.3, out = 0, pan = 0|
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
        sig = Pan2.ar(sig, pan);
        Out.ar(out, sig);
    }).add;

    // ---- SynthDef: Limiter am Summenbus --------------------------------
    // Liest ~mixBus (wohin alle Glocken-Synths schreiben), begrenzt hart auf
    // ~limiterCeiling und schreibt erst danach auf den echten Hardware-
    // Ausgang (0/1). Limiter.ar braucht KEINEN Attack/Release-Tuningaufwand
    // wie ein Compressor -- er ist ein reiner Peak-Limiter, der Samples
    // notfalls hart kappt, ohne vorher hoerbar zu komprimieren/pumpen.
    // Laeuft dauerhaft (nicht pro Treffer neu gestartet), deshalb eigene
    // Gruppe ~fxGroup NACH ~sourceGroup: der Bus muss beim Lesen bereits die
    // Summe aller in diesem Frame geschriebenen Glocken enthalten.
    SynthDef(\klangLimiter, { |in = 0, out = 0, ceiling = 0.95|
        var sig = In.ar(in, 2);
        sig = Limiter.ar(sig, ceiling, 0.01);
        Out.ar(out, sig);
    }).add;

    s.sync;

    // Limiter einmalig starten, liest ~mixBus, schreibt auf Hardware-out 0.
    // \fadeTime/Free nicht noetig -- laeuft die ganze Session durch.
    ~limiterSynth.free;
    ~limiterSynth = Synth(\klangLimiter, [\in, ~mixBus, \out, 0,
        \ceiling, ~limiterCeiling], ~fxGroup);

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

        // Glocken landen in ~sourceGroup (VOR dem Limiter in der Node-Order)
        // und schreiben auf ~mixBus statt direkt auf Hardware-out.
        Synth(\glockenBell, [\freq, freq, \amp, amp, \pan, pan, \out, ~mixBus],
            ~sourceGroup);

        ("node % -> % Hz (amp %)".format(nodeId, freq.round(0.1), amp.round(0.01))).postln;
    }, '/net/hitNode', recvPort: ~oscListenPort);

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
