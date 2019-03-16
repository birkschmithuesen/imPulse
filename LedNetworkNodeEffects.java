import processing.core.*;
import java.util.*;
import processing.core.PApplet;
import processing.core.PVector;

///////////////////////////////////////////////////////////
// a class to describe the behaviour of nodes - crossings of two LED-Stripes
///////////////////////////////////////////////////////////
public class LedNetworkNodeEffects implements runnableLedEffect {

	PApplet papplet;
	String name = "Nodes";
	String id;
	int nLeds;
	LedColor[] bufferLedColors;
	LedInNetInfo[] ledNetInfo;
	ArrayList<LedNetworkNode> nodes;
	int radius;

	// settings
	RemoteControlledFloatParameter timeFromFireToInactive = new RemoteControlledFloatParameter("/nodes/times/fire",
			0.1f, 0, 10);
	RemoteControlledFloatParameter timeFromInactiveToWait = new RemoteControlledFloatParameter("/nodes/times/recover",
			4f, 0, 10);

	RemoteControlledIntParameter centralSpotRadiusWaiting = new RemoteControlledIntParameter("/nodes/radius/waiting", 3,
			0, 10); // size of the spot arounf the connected LEDs
	RemoteControlledIntParameter centralSpotRadiusFiring = new RemoteControlledIntParameter("/nodes/radius/fired", 8, 0,
			10); // size of the spot arounf the connected LEDs
	RemoteControlledIntParameter centralSpotRadiusInactive = new RemoteControlledIntParameter("/nodes/radius/inactive",
			3, 0, 10); // size of the spot arounf the connected LEDs

	RemoteControlledColorParameter centralSpotColorWaiting = new RemoteControlledColorParameter(
			"/nodes/colors/central/waiting", 0, 1, 1);
	RemoteControlledColorParameter outerRegionColorWaiting = new RemoteControlledColorParameter(
			"/nodes/colors/outer/waiting", 0, 1, 1);

	RemoteControlledColorParameter centralSpotColorFiring = new RemoteControlledColorParameter(
			"/nodes/colors/central/fired", 1, 1, 1);
	RemoteControlledColorParameter outerRegionColorFiring = new RemoteControlledColorParameter(
			"/nodes/colors/outer/fired", 1, 1, 1);

	RemoteControlledColorParameter centralSpotColorInactive = new RemoteControlledColorParameter(
			"/nodes/colors/central/inactive", 0.1f, 1, 0.1f);
	RemoteControlledColorParameter outerRegionColorInactive = new RemoteControlledColorParameter(
			"/nodes/colors/outer/inactive", 0.1f, 1, 0.1f);

	RemoteControlledFloatParameter fadeOutGamma; // influences the fadeout towards the sides

	RemoteControlledFloatParameter pulseFrequency;
	RemoteControlledFloatParameter pulseFrequencyRandFraction;

	LedNetworkNodeEffects(String _id, int _nLeds, LedInNetInfo[] _ledNetInfo, ArrayList<LedNetworkNode> _nodes) {
		id = _id;
		nLeds = _nLeds;
		bufferLedColors = LedColor.createColorArray(nLeds);
		ledNetInfo = _ledNetInfo;
		nodes = _nodes;

		fadeOutGamma = new RemoteControlledFloatParameter("/nodes/fadeOutGamma", 2.0f, 0.1f, 10.0f);

		pulseFrequency = new RemoteControlledFloatParameter("/nodes/pulseFrequency", 1.0f, 0.1f, 10.0f); // frequency of
																											// brightness
																											// modulation
																											// (hz)
		pulseFrequencyRandFraction = new RemoteControlledFloatParameter("/nodes/pulseFreqRandFrac", 0.5f, 0.0f, 1.0f); // frequency
																														// of
																														// brightness
																														// modulation
																														// (hz)
	}

	public LedColor[] drawMe() {
		LedColor.set(bufferLedColors, new LedColor(0, 0, 0));
		float gamma = fadeOutGamma.getValue();
		// float curTime=papplet.millis()/1000.0f;
		float randomFraction = pulseFrequencyRandFraction.getValue();
		int nodeIdx = 0;
		for (LedNetworkNode curNode : nodes) {
			// find out state of led
			double timeSinceFire = (System.currentTimeMillis()) / 1000.0 - curNode.lastActivationTime;
			PVector centralColor;
			PVector outerColor;
			float radius;
			// fade from just hit to inactive
			if (timeSinceFire < timeFromFireToInactive.getValue()) {
				float fraction = (float) timeSinceFire / timeFromFireToInactive.getValue();
				centralColor = PVector.lerp(centralSpotColorFiring.getColor(), centralSpotColorInactive.getColor(),
						fraction);
				outerColor = PVector.lerp(outerRegionColorFiring.getColor(), outerRegionColorInactive.getColor(),
						fraction);
				radius = PApplet.map(fraction, 0f, 1f, centralSpotRadiusFiring.getValue(),
						centralSpotRadiusInactive.getValue());
			} else {
				// fade from inactive to normal waiting state
				float fraction = (float) (timeSinceFire - timeFromFireToInactive.getValue())
						/ timeFromInactiveToWait.getValue();
				if (fraction > 1) {
					fraction = 1;
				}

				double curFrequency = pulseFrequency.getValue()
						* (float) (1.0f + Math.sin((float) curNode.id * 0.5f) * randomFraction);
				float curModulation = (float) Math.sin(curFrequency * (double) (System.currentTimeMillis()) / 1000.0)
						* 0.5f + 0.5f;

				centralColor = PVector.lerp(centralSpotColorInactive.getColor(),
						PVector.mult(centralSpotColorWaiting.getColor(), curModulation), fraction);
				outerColor = PVector.lerp(outerRegionColorInactive.getColor(),
						PVector.mult(outerRegionColorWaiting.getColor(), curModulation), fraction);

				radius = PApplet.map(fraction, 0f, 1f, centralSpotRadiusInactive.getValue(),
						centralSpotRadiusWaiting.getValue());
			}

			// pseudo random modulation of pulse Frequency - different for different nodes,
			// but same in all iterations
			// draw all leds that make up the curent node
			for (Integer thisLedIdx : curNode.ledIndices) {
				// make a fadeOut/fadeIn around that led:
				int startIndex = PApplet.constrain(thisLedIdx - (int) (radius + 0.5), 0, nLeds - 1);
				int endIndex = PApplet.constrain(thisLedIdx + (int) (radius + 0.5), 0, nLeds - 1);
				int originalStripeIdx = ledNetInfo[thisLedIdx].stripeIndex;
				for (int ledIdx = startIndex; ledIdx <= endIndex; ledIdx++) {
					if (ledNetInfo[ledIdx].stripeIndex == originalStripeIdx) {
						float fadeFactor = (float) Math
								.pow(PApplet.map(Math.abs(ledIdx - thisLedIdx), 0, radius + 1, 1, 0), gamma);
						bufferLedColors[ledIdx].set(outerColor);
						bufferLedColors[ledIdx].mult(fadeFactor);
					}
					// and a central spot with another color
					bufferLedColors[thisLedIdx].set(centralColor);
				}
			}
		}
		return bufferLedColors;
	}

	public String getName() {
		return name;
	}
}
