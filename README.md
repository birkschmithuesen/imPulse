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

## rules
* create a new impulse when a tube is hit at the beginning of its coresponding led srtipe
* the impulse travels along the stripe. split up into three impulses, when a node - crossing of two led stripes is reached
* play the corresponding note when a node is reached by an impulse

## parameters
* /net/impulse/speed
* /net/impulse/energyDecay
* /net/impulse/nodeDeadTime
* /nodes/times/fire
* /nodes/times/recover
