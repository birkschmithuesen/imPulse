import processing.core.*;
import java.util.*;

//represents a connection between multiple stripes
class LedNetworkNode {
	public int id; // unique id
	public TreeSet<Integer> ledIndices; // all indices of leds that are connected here
	public double lastActivationTime = 0;

	// Draufsicht-Position in Metern, Ursprung Netzmitte. Gesetzt von
	// applyPositions, mitgeschickt an /net/hitNode.
	public float posX = 0;
	public float posY = 0;

	LedNetworkNode(int id_, TreeSet<Integer> ledIndices_) {
		id = id_;
		ledIndices = ledIndices_;
		lastActivationTime = 0;
	}

	// Setzt fuer jeden Knoten den Mittelwert der Positionen seiner LEDs.
	//
	// Ein Knoten ist EIN physischer Punkt mit zwei LEDs auf zwei Stripes. Ist
	// sein Anker gesetzt, liefert die Map fuer beide denselben Wert und der
	// Mittelwert ist genau dieser Anker. Ist der Anker noch offen, weichen die
	// interpolierten Werte der beteiligten Stripes leicht voneinander ab -
	// dann ist der Mittelwert ehrlicher als der erste Eintrag.
	//
	// Setzt (0,0), wenn keine einzige LED des Knotens eine Position hat.
	public static void applyPositions(LedPositionMap map, ArrayList<LedNetworkNode> nodes) {
		for (LedNetworkNode node : nodes) {
			float sumX = 0;
			float sumY = 0;
			int n = 0;
			for (Integer ledIdx : node.ledIndices) {
				int idx = ledIdx.intValue();
				if (map.isDefined(idx)) {
					sumX += map.x(idx);
					sumY += map.y(idx);
					n++;
				}
			}
			if (n > 0) {
				node.posX = sumX / n;
				node.posY = sumY / n;
			} else {
				node.posX = 0;
				node.posY = 0;
			}
		}
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
