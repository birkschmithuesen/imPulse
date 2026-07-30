// =====================================================================
// KlangNetz / imPulse — Glockenspiel-Sound für Node-Treffer
// Zoom AMS-24 (4 Outputs) via ASIO
// Update 2026-07-30 (Birk):
//  - Skala: D Kurd (klassische Hang-Drum/Handpan-Skala, natürlich Moll ab D)
//  - Grundton eine Oktave tiefer (D3 statt vorher C4)
//  - mehr Obertöne/Sägezahn-Anteil für "Reibung"/Kernigkeit (nicht hart/verzerrt)
//  - mehr Hall (FreeVerb)
// =====================================================================
(
s.options.device = "ASIO : ZOOM AMS-24 ASIO Driver";
s.options.numOutputBusChannels = 4;
s.options.numInputBusChannels = 2;

~oscListenPort = 8002;

// D Kurd Skala (Handpan/Hang-Drum-Standardskala): D E F G A Bb C
// = natürliche Moll-Tonleiter ab D. Halbtonabstände von der Grundnote:
~hangDrumSteps = [0, 2, 3, 5, 7, 8, 10];

// Grundton D2 (MIDI 38) -> nochmal eine Oktave tiefer als D3 (Birk-Wunsch 2026-07-30) + Hang-Drum-Skala.
~rootMidiNote = 38;
~numOctaves = 3;
~notesPerOctaveSet = ~hangDrumSteps.size * ~numOctaves;

// Mindestlautstärke deutlich angehoben (Birk 2026-07-30): manche Node-Treffer
// kommen mit so niedriger "energy" rein, dass sie unterhalb der Hörschwelle
// landeten (Verdacht: Randomizer/Jitter auf der Energy trotz augenscheinlich
// konstantem Wert). Fix: Range nach oben verschoben, damit auch die leisesten
// Treffer klar hörbar bleiben.
// -10dB gegenüber dem vorherigen Pegel (0.22/0.55), linear: *0.316228
// (Birk, 2026-07-30, "master -10db" für den Sound)
~minAmp = 0.06957;
~maxAmp = 0.17393;
~numOutChannels = 4;

s.waitForBoot({
    SynthDef(\glockenBell, { |freq = 440, amp = 0.3, out = 0|
        var sig, bellSig, grit, partials, partialAmps, partialDecays, env0;

        // ---- Glocken-Grundklang: unharmonische Sinus-Partialtöne ----
        partials      = #[1.0, 2.001, 2.998, 4.204, 5.515];
        partialAmps   = #[1.0, 0.55, 0.30, 0.18, 0.10];
        partialDecays = #[3.2, 2.1, 1.35, 0.85, 0.55];

        bellSig = Mix.fill(partials.size, { |i|
            var pFreq, pAmp, pDecay, env;
            pFreq  = freq * partials[i];
            pAmp   = partialAmps[i];
            pDecay = partialDecays[i];
            env = EnvGen.kr(
                Env([0, 1, 0], [0.003, pDecay], curve: [\lin, -4]),
                doneAction: if(i == 0) { 2 } { 0 }
            );
            SinOsc.ar(pFreq) * pAmp * env;
        });

        // ---- "Reibung"/Kernigkeit: dezenter Sägezahn-Layer -----------
        // Kurzer, schneller abklingender Saw-Anteil, bandpass-gefiltert um den
        // Grundton herum -> gibt Attack/Obertongehalt (Hang-Drum-typisch "metallisch-
        // kernig"), ohne die Bell-Hülle zu dominieren oder hart/verzerrt zu klingen.
        env0 = EnvGen.kr(Env([0, 1, 0], [0.002, 0.35], curve: [\lin, -6]));
        grit = Saw.ar(freq) * env0;
        grit = BPF.ar(grit, freq * 2, 0.7) * 0.22;

        sig = (bellSig + grit) * amp;

        // ---- Hall: mehr räumliche Tiefe -------------------------------
        sig = FreeVerb.ar(sig, mix: 0.42, room: 0.75, damp: 0.35);

        Out.ar(out, sig);
    }).add;

    s.sync;

    ~netHitNodeOscFunc.free;
    ~netHitNodeOscFunc = OSCFunc({ |msg, time, addr|
        var nodeId, energy, noteIndex, octave, degree, semitoneOffset, midiNote, freq, amp, outCh;

        nodeId = msg[1].asInteger;
        energy = msg[2].asFloat;

        noteIndex = nodeId % ~notesPerOctaveSet;
        octave = noteIndex div: ~hangDrumSteps.size;
        degree = noteIndex mod: ~hangDrumSteps.size;
        semitoneOffset = ~hangDrumSteps[degree] + (octave * 12);
        midiNote = ~rootMidiNote + semitoneOffset;
        freq = midiNote.midicps;

        amp = energy.clip(0.0, 2.0).linlin(0.0, 2.0, ~minAmp, ~maxAmp);

        outCh = nodeId % ~numOutChannels;

        Synth(\glockenBell, [\freq, freq, \amp, amp, \out, outCh]);

        ("node % -> % Hz (amp %) on output ch %".format(nodeId, freq.round(0.1), amp.round(0.01), outCh)).postln;
    }, '/net/hitNode', recvPort: ~oscListenPort);

    "KlangNetz Hang-Drum-Bells (D Kurd, Zoom AMS-24 4ch): Server gebootet, hoere auf /net/hitNode Port % - bereit.".format(~oscListenPort).postln;
});
)
