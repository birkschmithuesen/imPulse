# imPulse
imPulse is an audiovisual instrument. An installation with led-stripes, speakers, metal pipes and contact mics as user interface. The led-stripes are arranged as a chaotic net with multiply nodes. The metal pipes are acoustic sound bodys and user interface to create light impulses that travel along the net. When an impulse reaches a node, it triggers a sound and splits up into multiply impulses corresponding to the system behaviour.
<b>Topic for 35C3 lab</b> is to play with the system rules and add attributes to the traveling agents(light impulses).


## video documentation on working process
<b>Wisp lab 2018</b></br >
focusing on the sound design, contact mic building, sound spatialisation movements, connecting Ableton/MaxForLive + Processing + Ambisonic</br >
https://vimeo.com/295063279</br >
PW: workinprogress

<b>network, impulses, nodes</b></br >
https://vimeo.com/244515640

## sketch for 35C3 version
![My image](https://github.com/birkschmithuesen/imPulse/blob/master/impulse_topView.jpg)

## communication between instances
Max/MSP -> (OSC) -> Processing -> (Syphon) -> Madmapper -> (ArtNet) -> APA 102</br>
Max/MSP <- (OSC) <- Processing

Processing can also send Art-Net directly to the LED controllers, bypassing Syphon/MadMapper entirely - this is the currently active output path, see "Art-Net output" below.

## rules
* create a new impulse when a tube is hit at the beginning of its coresponding led srtipe
* the impulse travels along the stripe. split up into three impulses, when a node - crossing of two led stripes is reached
* play the corresponding note when a node is reached by an impulse

## geometry
The current installation has 30 led stripes of 600 LEDs each (18,000 LEDs total), driven by 15 Pixel2LED controllers. Each controller has two outputs and drives two stripes (controller `k` -> stripes `2k` and `2k+1`). Controllers are addressed by the last octet of their IP (`2.2.2.<octet>`); the octets in use are `2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21`.

## Art-Net output
`ArtNetOutput` sends Art-Net directly to the controllers, no MadMapper in between. Each LED takes 4 bytes, so one universe (512 DMX channels) carries 128 LEDs. At 600 LEDs per output that's 5 universes, the last one only filled to 88 real LEDs (the remaining slots are zeroed, not left over from a previous frame). The start universe follows the convention `octet * 100`.

The byte order within those 4 bytes is R, G, B, 0 - byte 0 drives red, byte 1 green, byte 2 blue, byte 3 is unused. Measured against the physical installation on 2026-07-30 with a white start marker and a single, non-repeating color sequence (one clean time base). Deriving this from the firmware is misleading: reading the four DMX bytes as a little-endian `uint32` and passing it to `CRGB(word)` suggests B, G, R instead - two independent analyses reached that wrong conclusion. Trust the measurement, not the derivation. See `R_OFFSET`/`G_OFFSET`/`B_OFFSET` in `ArtNetOutput.java`.

An **ArtSync** packet (OpSync) is sent after each controller's universes and is mandatory, not optional - without it the firmware would display each universe as soon as it arrives, tearing the image across outputs. Sending happens on its own 40 Hz thread with triple buffering (build/ready/send buffers), so `draw()` never blocks waiting on the network. `describeMapping()` prints the full controller/universe/stripe table to the console at startup, useful for checking against the controllers' web interface before anything is connected.

## master level - one show fader, test patterns dim themselves

`/master/level` is the show fader, freely adjustable **0..1** since 2026-07-30
(Birk: the installation never drives the stripes to full white during a show).
The hardware risk - full white on a 10 m stripe already showing voltage drop
per the strip's datasheet - only ever applied to the calibration test
patterns, not to the show content.

Since 2026-07-31 the fader applies unfiltered to **every** mode - show,
calibration, position mode. `draw()` simply calls
`artNetOutput.setMasterLevel(masterLevel.getValue())`; the former
`calibrationMode ? CALIBRATION_MASTER_LEVEL : ...` and the constant behind it
are gone. The reason: the calibration view shows only individual points plus
two cursor stripes tinted to 6 %, and it needs to be bright while recording
crossings - a per-mode ceiling cannot separate it from the full-surface test
patterns that live in the same mode.

The safety limit now sits where the brightness is produced:
`TestPatterns.PATTERN_LEVEL = 0.1f`, applied in the private helper
`lit(r, g, b)` that **every** colour of **every** pattern passes through. No
pattern builds its own `LedColor` - otherwise the ceiling would be a
convention a later pattern has to remember. `test/TestPatternsTest.java`
enforces it (no channel above `PATTERN_LEVEL`, and pattern 5 actually reaches
it, so an accidentally black pattern cannot pass).

This makes the guarantee *stricter*, not weaker: the master still multiplies
on top and is clamped to 0..1 by `ArtNetOutput.setMasterLevel()`, so a test
pattern can never exceed 10 % - the fader can only darken it further.

Consequence for `test/PatternProbe.java`: its `MASTER_LEVEL` is now **1.0**,
not 0.1. Two dimmings in series would give 0.01, i.e. 2 of 255 - the test
patterns would be practically invisible on site.

## parameters
* /net/impulse/speed
* /net/impulse/lifetime
* /net/impulse/nodeDeadTime
* /nodes/times/fire
* /nodes/times/recover
* /master/level (see "master level" above - show fader, 0..1)
* /preset/scheduler/enabled, /preset/scheduler/interval (see "Presets" below)

## Presets

A preset holds the complete parameter set of visuals and sound under one name.
Files: `data/presets/<name>.txt` (visuals) and `supercollider/presets/<name>.txt`
(sound). Same six tab-separated columns as `remoteSettings.txt`, so a scene
snapshot can be turned into a preset by copying it.

Load and save over OSC on port 8001:

```
/preset/load hang_drum_slow
/preset/save mein_neues_preset
/preset/next
```

At startup - sketch argument first, environment variable as a fallback:

```bash
IMPULSE_PRESET=standby processing-java --sketch=$PWD --run
```

Automatic change every ten minutes:

```
/preset/scheduler/interval 600
/preset/scheduler/enabled 1
```

Order is alphabetical by file name, the list is read fresh on every change, and
switching the scheduler on does **not** jump immediately - the interval starts
from that moment. imPulse forwards the name to SuperCollider as
`/sc/preset/load <name>` on port 8002; there is only one scheduler, so light and
sound cannot drift apart. Fire-and-forget: if sclang isn't running, the visual
show carries on.

Preset names are restricted to `[a-z0-9_-]`, 1 to 64 characters. The name
arrives over OSC from outside, so without that check `/preset/load
../../../etc/passwd` would be a file access of the sender's choosing.

## libraries to be imported into Processing
* [oscP5](http://www.sojamo.de/libraries/oscP5/) (also provides `netP5`)
* [Syphon](https://github.com/Syphon/Processing) (macOS) / Spout (Windows) - used to preview/share the LED canvas as a texture

`controlP5` and `artnet4j` are **no longer needed**. The old dropdown-based calibration UI (which used controlP5) has been replaced by the two-cursor calibration described below, and Art-Net output is now handled by the self-written `ArtNetOutput` class instead of the `artnet4j` library.

## Node calibration
Node crossings are recorded by hand into `data/nodeCrossings.txt` (one line per node, space-separated global LED indices) using a two-cursor tool (`NodeCalibration`) - not the seven-mode dropdown of earlier versions. `NodeCrossingStore` handles validation, undo and file I/O; `LedInNetInfo.applyCrossings(...)` turns the stored crossings into the actual node objects the transport effect reacts to.

Press <b>c</b>/<b>C</b> to toggle calibration mode (`calibrationMode`). While active, the preview shows both cursor stripes dimly lit (cursor A green, cursor B red) with a bright dot at each cursor's LED, and a HUD below the preview shows both cursor positions, the loaded/new node count, the current step size and the last message.

Key bindings while calibration mode is active:
* <b>arrow keys</b> move the active cursor (left/right = LED index, up/down = stripe)
* <b>TAB</b> switch between cursor A and cursor B
* <b>ENTER</b> add the current cursor pair as a node crossing; rejections (out of range, identical LED, too close on the same stripe, duplicate) are shown in the HUD **and** printed to the console, in case the HUD isn't visible
* <b>BACKSPACE</b> undo the last crossing added in this session - crossings loaded from file at startup are deliberately protected and cannot be undone this way
* <b>f</b> cycle the arrow-key step size (1/10/100)
* <b>s</b> write the full list to `data/nodeCrossings.txt` (atomic write via a temp file + rename, **not** append - saving repeatedly in one session never duplicates entries)
* <b>r</b> re-apply the current crossings at runtime, without restarting the sketch
* <b>n</b> toggle display of loaded (magenta) and newly added (cyan) crossings
* <b>0-5</b> switch test patterns - `0` is the calibration view itself, `1`-`5` are the five acceptance test patterns; toggling calibration mode with `C` - either direction, entering or leaving - resets this back to `0`, so re-entering calibration never shows a leftover test pattern
* <b>l</b> discard **all** crossings, including the ones loaded from file - for when a calibration file turns out to be from a different stripe geometry and `BACKSPACE`'s protection needs to be overridden on purpose. Requires explicit confirmation: the first press announces how many crossings would be discarded, a second press between 300 ms and 5 s later actually clears the list. Any other key - arrow keys and toggling calibration mode with `C` included - silently cancels a pending confirmation; the 300 ms floor guards against key repeat confirming instantly while `L` is held down

`data/nodeCrossings_16x720.txt` and `data/nodeCrossings_35C3.txt` are topologies of earlier installations, kept as a record but not loaded.

## Tests
`test/run.sh` compiles the Processing- and network-independent classes together with everything in `test/` and runs them. Called with no arguments it runs all four suites: `ArtNetOutputTest`, `ArtNetDecoderTest`, `NodeCrossingStoreTest`, `ApplyCrossingsTest`.

The same folder also has three standalone probes that talk to the real hardware and are **not** part of the default run: `TimingProbe` (measures the actual 40 Hz send rate against the real network), `PollProbe` (queries the controllers over Art-Net's ArtPoll) and `PatternProbe` (drives the five acceptance test patterns straight through `ArtNetOutput`, without the Processing sketch). Run these individually and deliberately, e.g. `test/run.sh TimingProbe` - never as part of an unattended check.
