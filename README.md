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

## libraries to be imported into Processing
* [oscP5](http://www.sojamo.de/libraries/oscP5/)
* [controlP5](http://www.sojamo.de/libraries/controlP5/)

## Controls for manual node selection
* <b>UP</b>/<b>DOWN</b> keys for brightness
* <b>LEFT</b>/<b>RIGHT</b> keys for decreasing/increasing the parameter selected in the drop down menu
* <b>f</b> to cycle through speeds for decreasing/increasing
* <b>n</b> to show loaded and newly created nodes
* <b>ENTER</b> to save current node
* <b>s</b> to save all created nodes

#### How to set crossings on different stripes:

1. select a black stripe (```CYCLE_BLACK_STRIPE```)
2. select an led on that stripe that crosses with another stripe (```CONTROL_BLACK_STRIPE_LEDS```)
 * use <b>f</b>-key to speed up/slow down the control
3. select the other stripe that matches the previously selected crossing (```CYCLE_BRIGHT_STRIPES```)
4. select an led on that stripe that crosses with another stripe (```CONTROL_BRIGHT_STRIPE_LEDS```)
 * use <b>f</b>-key to speed up/slow down the control
5. Hit <b>ENTER</b> to save the crossing as a node
 * with <b>n</b> you can show all currently set nodes
6. *optional*: reset the bright stripes if you want to switch the stripe (```ACTIVATE_ALL_BRIGHT_STRIPES```)
7. repeat starting at 1. (if you want to switch the stripe) or 2.

When you are done, save the configuration to a file by hitting the <b>s</b>-key.

#### How to set crossings of one stripe with itself

1. select a black stripe (```CYCLE_BLACK_STRIPE```)
2. select an led on that stripe that crosses with the same stripe (```SET_SAME_STRIPE_FIRST_NODE```)
 * use <b>f</b>-key to speed up/slow down the control
3. select matching led on that stripe (```SET_SAME_STRIPE_SECOND_NODE```)
4. Hit <b>ENTER</b> to save the crossing as a node
 * with <b>n</b> you can show all currently set nodes
5. *optional*: reset the bright stripes if you want to switch the stripe (```ACTIVATE_ALL_BRIGHT_STRIPES```)
6. repeat starting at 1. (if you want to switch the stripe) or 2.

When you are done, save the configuration to a file by hitting the <b>s</b>-key.
