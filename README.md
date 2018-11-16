# imPulse
imPulse is an audiovisual instrument. An installation with led-stripes, speakers, metal pipes and contact mics as user interface to create impulses that travel along the chaotic net of led stripes. The processing code creates the content for the LEDs and simulate impulses traveling through a net, splitting themselves on every crossing.

## video documentation
<b>Wisp lab 2018</b></br >
focusing on the sound design, contact mic building, sound spatialisation movements, connecting Ableton/MaxForLive + Processing + Ambisonic</br >
https://vimeo.com/295063279</br >
PW: workinprogress

<b>network, impulses, nodes</b></br >
https://vimeo.com/244515640

## communication between instances

Max/MSP -> (OSC) -> Processing -> (Syphon) -> Madmapper -> (ArtNet) -> APA 102</ br>
Max/SMP <- (OSC) <- Processing</ br>

## rules
* create a new impulse when a tube is hit at the beginning of its coresponding led srtipe </ br>
* the impulse travels along the stripe. split up into three impulses, when a node - crossing of two led stripes is reached</ br>
* play the corresponding note when a node is reached by an impulse</ br>

## parameters
