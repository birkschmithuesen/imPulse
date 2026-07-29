import processing.core.*;
import java.util.*;

//represents a connection between multiple stripes
class LedNetworkNode {
	public int id; // unique id
	public TreeSet<Integer> ledIndices; // all indices of leds that are connected here
	public double lastActivationTime = 0;

	LedNetworkNode(int id_, TreeSet<Integer> ledIndices_) {
		id = id_;
		ledIndices = ledIndices_;
		lastActivationTime = 0;
	}
}

// information about how an led is embedded in the topolgy of a network.
class LedInNetInfo {
	LedInNetInfo(int stripeIndex_, int indexInStripe_, int stripeLength_) {
		stripeIndex = stripeIndex_;
		indexInStripe = indexInStripe_;
		stripeLength = stripeLength_;
	}

	public int stripeIndex;
	public int indexInStripe;
	public int stripeLength;
	public LedNetworkNode partOfNode; // is this led part of a connecting Node? which one? (set by
										// LedInNetInfo.applyCrossings)

	public static LedInNetInfo[] buildNetInfo(int numStripes, int numLedsPerStripe) {
		LedInNetInfo[] result = new LedInNetInfo[numStripes * numLedsPerStripe];
		int ledIndex = 0;
		for (int stripeIndex = 0; stripeIndex < numStripes; stripeIndex++) {
			for (int innerIndex = 0; innerIndex < numLedsPerStripe; innerIndex++) {
				result[ledIndex] = new LedInNetInfo(stripeIndex, innerIndex, numLedsPerStripe);
				ledIndex++;
			}
		}
		return result;
	}

	// Baut die Node-Struktur aus einer Liste von Kreuzungen neu auf. Wird beim
	// Start und beim Neuladen waehrend der Kalibrierung benutzt.
	//
	// Die Zielliste wird in-place geaendert, weil LedNetworkTransportEffect und
	// LedNetworkNodeEffects dieselbe Instanz halten und die Aenderung sonst nicht
	// mitbekaemen.
	public static void applyCrossings(java.util.List<TreeSet<Integer>> crossings,
			LedInNetInfo[] ledNetInfos, ArrayList<LedNetworkNode> target) {
		// alte Zuordnung vollstaendig loeschen, sonst bleiben LEDs an
		// zurueckgenommenen Nodes haengen
		for (int i = 0; i < ledNetInfos.length; i++) {
			ledNetInfos[i].partOfNode = null;
		}
		target.clear();

		int nodeId = 0;
		for (TreeSet<Integer> cluster : crossings) {
			LedNetworkNode node = new LedNetworkNode(nodeId, new TreeSet<Integer>(cluster));
			target.add(node);
			for (Integer ledIdx : node.ledIndices) {
				if (ledIdx >= 0 && ledIdx < ledNetInfos.length) {
					ledNetInfos[ledIdx].partOfNode = node;
				}
			}
			nodeId++;
		}
		System.out.println(target.size() + " Nodes uebernommen");
	}
}
