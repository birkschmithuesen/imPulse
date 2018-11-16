# imPulse
imPulse is an audiovisual instrument. An installation with led-stripes, speakers, metal pipes and contact mics as user interface to create impulses that travel along the chaotic net of led stripes. The processing code creates the content for the LEDs and simulate impulses traveling through a net, splitting themselves on every crossing.

## video documentation
<b>Wisp-Lab 2018
focusing on the sound design, contact mic building, sound spatialisation movements, connecting Ableton/MaxForLive + Processing + Ambisonic
https://vimeo.com/295063279
PW: workinprogress

###network, impulses, nodes
https://vimeo.com/244515640

## I/O Design

Max/MSP -> (OSC) -> Processing -> (Syphon) -> Madmapper -> (ArtNet) -> APA 102 
when a tube is hit create an impulse.
