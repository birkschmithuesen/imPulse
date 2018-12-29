import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

///////////////////////////////////////////////////////////
// turns on a single hardware LED stripe that can lit up incrementally
///////////////////////////////////////////////////////////

public class LedStripeFullActivationEffect implements runnableLedEffect {
	String name = "Activate Stripe";
	String id;
	LedColor[] bufferLedColors;
	LedInStripeInfo[] stripeInfo;

	public boolean showNodes = false;

	ArrayList<TreeSet<Integer>> manualNodeCrossings = new ArrayList<TreeSet<Integer>>();
	LedInNetInfo[] ledNetInfo;
	ArrayList<LedNetworkNode> listOfNodes;

	float stripesBrightness = 1;
	float stripesBrightnessDelta = 0.1f;
	int numStripes;

	int activatedStripeLength;
	int activatedStripeIndex = 0;
	int ledStripeActivationIndex = -1;

	int activatedBrightStripeLength;
	int brightLedStripeIndex = -1;
	int brightLedStripeActivationIndex = -1;

	int sameStripeFirstNodeFullActivationIndex = -1;

	public static enum StripeChange {
		NONE, ACTIVATE_NEXT_STRIPE_LED, DEACTIVATE_LAST_STRIPE_LED, INCREASE_BRIGHTNESS, DECREASE_BRIGHTNESS,
		PREV_BLACK_STRIPE, NEXT_BLACK_STRIPE, PREV_BRIGHT_STRIPE, NEXT_BRIGHT_STRIPE, ACTIVATE_NEXT_BRIGHT_STRIPE_LED,
		DEACTIVATE_LAST_BRIGHT_STRIPE_LED, ACTIVATE_NEXT_SAME_STRIPE_LED_FIRST_NODE,
		DEACTIVATE_LAST_SAME_STRIPE_LED_FIRST_NODE, ACTIVATE_NEXT_SAME_STRIPE_LED_SECOND_NODE,
		DEACTIVATE_LAST_SAME_STRIPE_LED_SECOND_NODE;
	}

	StripeChange stripeChange = StripeChange.NONE;
	Instant lastChangeTime = Instant.now();

	private int speedSetting = 0;
	private int minMillisBetweenChanges = 100;
	private int[] minMillisBetweenChangesValues = { 100, 10, 0 };

	private static int sameStripeFullActivationOffset = 20;

	LedStripeFullActivationEffect(String _id, LedInStripeInfo[] _stripeInfo, int _numStripes,
			LedInNetInfo[] _ledNetInfo, ArrayList<LedNetworkNode> _listOfNodes) {
		id = _id;
		stripeInfo = _stripeInfo;
		numStripes = _numStripes;
		ledNetInfo = _ledNetInfo;
		listOfNodes = _listOfNodes;
		bufferLedColors = LedColor.createColorArray(stripeInfo.length);
	}

	private boolean activatedStripeLedIsInBounds(int activatedStripeLedCurrentIndex) {
		return (ledStripeActivationIndex - 3) <= activatedStripeLedCurrentIndex
				&& activatedStripeLedCurrentIndex <= (ledStripeActivationIndex + 2);
	}

	private boolean brightStripeLedIsInBounds(int stripeIndex, int brightLedStripeLedCurrentIndex) {
		if (brightLedStripeIndex >= 0) {
			boolean res = stripeIndex == brightLedStripeIndex;
			if (brightLedStripeActivationIndex >= 0) {
				res = res && (brightLedStripeActivationIndex - 3) <= brightLedStripeLedCurrentIndex
						&& brightLedStripeLedCurrentIndex <= (brightLedStripeActivationIndex + 2);
			}
			return res;
		}
		return true;
	}

	public LedColor[] drawMe() {
		for (int i = 0; i < stripeInfo.length; i++) {
			int currentStripeIndex = stripeInfo[i].whichStripeIndex;
			int ledIndexInStripe = stripeInfo[i].indexInStripe;
			bufferLedColors[i] = new LedColor(0, 0, 0);

			if (currentStripeIndex == activatedStripeIndex) {
				LedColor col;
				if (activatedStripeLedIsInBounds(ledIndexInStripe)) {
					col = new LedColor(0, 1, 0);
				} else if (sameStripeFirstNodeFullActivationIndex >= 0
						&& ledIndexInStripe >= sameStripeFirstNodeFullActivationIndex) {
					col = new LedColor(1, 0, 0);
				} else {
					col = new LedColor(0, 0, 0);
				}
				bufferLedColors[i] = col;
			}

			if (brightStripeLedIsInBounds(currentStripeIndex, ledIndexInStripe)
					&& (currentStripeIndex != activatedStripeIndex || (brightLedStripeActivationIndex >= 0))) {
				bufferLedColors[i] = new LedColor(1, 0, 0);
			}
			// display already set nodes if showNodes is set to true
			if (showNodes) {
				for (TreeSet<Integer> nodeCrossing : manualNodeCrossings) {
					Iterator<Integer> itr = nodeCrossing.iterator();
					while (itr.hasNext()) {
						if (itr.next() == i) {
							bufferLedColors[i] = new LedColor(0, 1, 1);
						}
					}
				}
				if (ledNetInfo[i].partOfNode != null) {
					bufferLedColors[i] = new LedColor(1, 0, 1);
				}
			}
			bufferLedColors[i].mult(stripesBrightness);
		}
		return bufferLedColors;
	}

	private void increaseStripesBrightness() {
		if (changeNotAllowed()) {
			return;
		}
		stripesBrightness += stripesBrightnessDelta;
		stripesBrightness = Math.min(stripesBrightness, 1f);
		stripesBrightness = Math.max(stripesBrightness, 0f);
	}

	private void decreaseStripesBrightness() {
		if (changeNotAllowed()) {
			return;
		}
		stripesBrightness -= stripesBrightnessDelta;
		stripesBrightness = Math.min(stripesBrightness, 1f);
		stripesBrightness = Math.max(stripesBrightness, 0f);
	}

	private void activateNextStripeLed() {
		if (changeNotAllowed()) {
			return;
		}
		ledStripeActivationIndex += 1;
		ledStripeActivationIndex = Math.min(ledStripeActivationIndex, activatedStripeLength);
	}

	private void deactivateLastStripeLed() {
		if (changeNotAllowed()) {
			return;
		}
		ledStripeActivationIndex -= 1;
		ledStripeActivationIndex = Math.max(ledStripeActivationIndex, -1);
	}

	private void activateNextBrightStripeLed() {
		if (changeNotAllowed()) {
			return;
		}
		brightLedStripeActivationIndex++;
		brightLedStripeActivationIndex = Math.min(brightLedStripeActivationIndex, activatedBrightStripeLength);
	}

	private void deactivateLastBrightStripeLed() {
		if (changeNotAllowed()) {
			return;
		}
		brightLedStripeActivationIndex--;
		brightLedStripeActivationIndex = Math.max(brightLedStripeActivationIndex, -1);
	}

	private void activateNextSameStripeLedFirstNode() {
		activateNextStripeLed();
		sameStripeFirstNodeFullActivationIndex = ledStripeActivationIndex + sameStripeFullActivationOffset;
	}

	private void deactivateLastSameStripeLedFirstNode() {
		deactivateLastStripeLed();
		sameStripeFirstNodeFullActivationIndex = ledStripeActivationIndex + sameStripeFullActivationOffset;
	}

	private void activateNextSameStripeLedSecondNode() {
		sameStripeFirstNodeFullActivationIndex = -1;
		brightLedStripeIndex = activatedStripeIndex;
		activateNextBrightStripeLed();
	}

	private void deactivateLastSameStripeLedSecondNode() {
		sameStripeFirstNodeFullActivationIndex = -1;
		brightLedStripeIndex = activatedStripeIndex;
		deactivateLastBrightStripeLed();
	}

	private void nextBlackStripe() {
		setChangesSpeedSlow();
		if (changeNotAllowed()) {
			return;
		}
		int temp = activatedStripeIndex + 1;
		temp = Math.min(temp, numStripes - 1);
		temp = Math.max(temp, 0);
		activatedStripeIndex = temp;
		setActivatedStripeLength();
	}

	private void prevBlackStripe() {
		setChangesSpeedSlow();
		if (changeNotAllowed()) {
			return;
		}
		int temp = activatedStripeIndex - 1;
		temp = Math.min(temp, numStripes - 1);
		temp = Math.max(temp, 0);
		activatedStripeIndex = temp;
		setActivatedStripeLength();
	}

	private void setActivatedStripeLength() {
		activatedStripeLength = 0;
		for (int i = 0; i < stripeInfo.length; i++) {
			if (stripeInfo[i].whichStripeIndex == activatedStripeIndex) {
				activatedStripeLength += 1;
			}
		}
	}

	private void setActivatedBrightStripeLength() {
		activatedBrightStripeLength = 0;
		for (int i = 0; i < stripeInfo.length; i++) {
			if (stripeInfo[i].whichStripeIndex == brightLedStripeIndex) {
				activatedBrightStripeLength += 1;
			}
		}
	}

	private void nextBrightStripe() {
		setChangesSpeedSlow();
		if (changeNotAllowed()) {
			return;
		}
		int temp = brightLedStripeIndex + 1;
		temp = Math.min(temp, numStripes - 1);
		temp = Math.max(temp, 0);
		brightLedStripeIndex = temp;
		setActivatedBrightStripeLength();
	}

	private void prevBrightStripe() {
		setChangesSpeedSlow();
		if (changeNotAllowed()) {
			return;
		}
		int temp = brightLedStripeIndex - 1;
		temp = Math.min(temp, numStripes - 1);
		temp = Math.max(temp, 0);
		brightLedStripeIndex = temp;
		setActivatedBrightStripeLength();
	}

	public void saveNodeCrossingsToFile(String nodeCrossingsFilePath) throws IOException {

		FileWriter fileWriter = new FileWriter(nodeCrossingsFilePath, true); // Set true for append mode
		PrintWriter printWriter = new PrintWriter(fileWriter);

		System.out.println("Iterating through manualNodeCrossings!");

		for (TreeSet<Integer> nodeCrossing : manualNodeCrossings) {
			Iterator<Integer> itr = nodeCrossing.iterator();
			String indices = "";
			while (itr.hasNext()) {
				String ledIndexString = Integer.toString(itr.next());
				indices += ledIndexString + " ";
			}
			System.out.println("Saving node crossings to file:");
			System.out.println(indices);
			printWriter.println(indices);
		}

		printWriter.close();

	}

	public void saveCurrentNodeCrossing() {
		System.out.println("Saving node crossing:");
		Integer firstAbsoluteIndex = activatedStripeIndex * stripeInfo[0].stripeLength + ledStripeActivationIndex;
		Integer secondAbsoluteIndex = brightLedStripeIndex * stripeInfo[0].stripeLength
				+ brightLedStripeActivationIndex;
		TreeSet<Integer> nodeCrossing = new TreeSet<Integer>(Arrays.asList(firstAbsoluteIndex, secondAbsoluteIndex));
		System.out.println(nodeCrossing);
		manualNodeCrossings.add(nodeCrossing);
	}

	public void toggleShowNodes() {
		showNodes = !showNodes;
	}

	// call this function when you saved a node
	public void resetCurrentStripeConfig() {
		ledStripeActivationIndex = -1;
		brightLedStripeIndex = -1;
		brightLedStripeActivationIndex = -1;
	}

	public void setStripeChange(StripeChange _stripeChange) {

		if ((_stripeChange == StripeChange.PREV_BRIGHT_STRIPE || _stripeChange == StripeChange.NEXT_BRIGHT_STRIPE)
				&& (stripeChange != StripeChange.PREV_BRIGHT_STRIPE && stripeChange != StripeChange.NEXT_BRIGHT_STRIPE
						&& stripeChange != StripeChange.NONE)) {
			brightLedStripeIndex = 0;
		}
		stripeChange = _stripeChange;
	}

	// return the node crossing
	public void getNodeCrossing() {
		// return ledStripeActivationIndex - 1, brightStripeActivationIndex - 1;
	}

	// cycle through different speed settings
	public void cycleSpeeds() {
		speedSetting++;
		if (speedSetting >= minMillisBetweenChangesValues.length) {
			speedSetting = 0;
		}
		minMillisBetweenChanges = minMillisBetweenChangesValues[speedSetting];
	}

	// set the speed of changes to slowest option
	private void setChangesSpeedSlow() {
		minMillisBetweenChanges = minMillisBetweenChangesValues[0];
	}

	// call this function after setting the desired mode of change any time
	// necessary
	public void changeStripe() {
		if (stripeChange == StripeChange.INCREASE_BRIGHTNESS) {
			this.increaseStripesBrightness();
		} else if (stripeChange == StripeChange.DECREASE_BRIGHTNESS) {
			this.decreaseStripesBrightness();
		} else if (stripeChange == StripeChange.ACTIVATE_NEXT_STRIPE_LED) {
			this.activateNextStripeLed();
		} else if (stripeChange == StripeChange.DEACTIVATE_LAST_STRIPE_LED) {
			this.deactivateLastStripeLed();
		} else if (stripeChange == StripeChange.NEXT_BLACK_STRIPE) {
			this.nextBlackStripe();
		} else if (stripeChange == StripeChange.PREV_BLACK_STRIPE) {
			this.prevBlackStripe();
		} else if (stripeChange == StripeChange.NEXT_BRIGHT_STRIPE) {
			this.nextBrightStripe();
		} else if (stripeChange == StripeChange.PREV_BRIGHT_STRIPE) {
			this.prevBrightStripe();
		} else if (stripeChange == StripeChange.ACTIVATE_NEXT_BRIGHT_STRIPE_LED) {
			this.activateNextBrightStripeLed();
		} else if (stripeChange == StripeChange.DEACTIVATE_LAST_BRIGHT_STRIPE_LED) {
			this.deactivateLastBrightStripeLed();
		} else if (stripeChange == StripeChange.ACTIVATE_NEXT_SAME_STRIPE_LED_FIRST_NODE) {
			this.activateNextSameStripeLedFirstNode();
		} else if (stripeChange == StripeChange.DEACTIVATE_LAST_SAME_STRIPE_LED_FIRST_NODE) {
			this.deactivateLastSameStripeLedFirstNode();
		} else if (stripeChange == StripeChange.ACTIVATE_NEXT_SAME_STRIPE_LED_SECOND_NODE) {
			this.activateNextSameStripeLedSecondNode();
		} else if (stripeChange == StripeChange.DEACTIVATE_LAST_SAME_STRIPE_LED_SECOND_NODE) {
			this.deactivateLastSameStripeLedSecondNode();
		}

	}

	public String getName() {
		return name;
	}

	// ensure changes are only allowed in set time intervals
	// this enables smooth changes when holding down keys
	private boolean changeNotAllowed() {
		Instant currentTime = Instant.now();
		Duration timeElapsed = Duration.between(lastChangeTime, currentTime);
		if (timeElapsed.toMillis() > minMillisBetweenChanges) {
			lastChangeTime = currentTime;
			return false;
		}
		return true;
	}

}
