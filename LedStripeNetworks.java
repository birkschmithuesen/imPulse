import processing.core.*;
import java.util.*;

//represents a connection between multiple stripes
class LedNetworkNode {
  public int id; // unique id
  public TreeSet<Integer> ledIndices; // all indices of leds that are connected here
  public double lastActivationTime=0;
  LedNetworkNode(int id_, TreeSet<Integer> ledIndices_) {
    id=id_;
    ledIndices=ledIndices_;
    lastActivationTime=0;
  }
}

class StripeInfo {
  public int id;
  public int startLedIndex;
  public int endLedIndex;
  StripeInfo(int id_, int startLedIndex_, int endLedIndex_) {
    id=id_;
    startLedIndex=startLedIndex_;
    endLedIndex=endLedIndex_;
  }
  static StripeInfo[] buildStripeInfo(int nStripes, int nLedsPerStripe) {
    StripeInfo[] result=new StripeInfo[nStripes];
    for (int i=0; i<nStripes; i++) {
      result[i]=new StripeInfo(i, i*nLedsPerStripe, (i+1)*nLedsPerStripe-1);
    }
    return result;
  }
};

// information about how an led is embedded in the topolgy of a network.
class LedInNetInfo {
  LedInNetInfo(int stripeIndex_, int indexInStripe_, int stripeLength_) {
    stripeIndex= stripeIndex_;
    indexInStripe=indexInStripe_;
    stripeLength=stripeLength_;
  }
  public int stripeIndex;
  public int indexInStripe;
  public int stripeLength;
  public LedNetworkNode partOfNode; // is this led part of a connecting Node? which one? (set by StripeCrossInfo.buildClusterInfosetClusterInfo)

  public static LedInNetInfo[] buildNetInfo(int numStripes, int numLedsPerStripe) {
    LedInNetInfo[] result= new LedInNetInfo[numStripes*numLedsPerStripe];
    int ledIndex=0;
    for (int stripeIndex=0; stripeIndex<numStripes; stripeIndex++) {
      for (int innerIndex=0; innerIndex<numLedsPerStripe; innerIndex++) {
        result[ledIndex]=new LedInNetInfo(stripeIndex, innerIndex, numLedsPerStripe);
        ledIndex++;
      }
    }
    return result;
  }



  public static ArrayList<LedNetworkNode> buildClusterInfo(LedInNetInfo[] ledNetInfos) {
    ////////////////////////////////////////////////////////////////////////////
    //ToDo: manual go through the leds to define the led-number of the crossing
    ////////////////////////////////////////////////////////////////////////////
    int firstIndex = 1;
    int secondIndex = 100;


    int nLeds=ledNetInfos.length;
    //each led can be member of one connectedcluster that contains all leds that are (also indirectly) connected by distances<thresh
    // clusters are represented by sets of the ledIndices
    ArrayList <TreeSet<Integer>> clusters=new ArrayList <TreeSet<Integer>>(); // here remember all the clusters we have built
    ArrayList <TreeSet<Integer>> clusterOfLed=new ArrayList <TreeSet<Integer>>(nLeds); // here we remember which cluster a led is part of 

    while (clusterOfLed.size()<nLeds)clusterOfLed.add(null);

    //create cluster for first led if it does not exist yet
    if (clusterOfLed.get(firstIndex)==null) {
      TreeSet<Integer> newCluster=new TreeSet<Integer>();
      newCluster.add(firstIndex);
      clusters.add(newCluster);
      clusterOfLed.set(firstIndex, newCluster);
    }
    // add second led to cluster of firstLed
    clusterOfLed.get(firstIndex).add(secondIndex);
    // if the second led is not part of a cluster yet, make it part of the cluster of the first one
    if (clusterOfLed.get(secondIndex)==null)clusterOfLed.set(secondIndex, clusterOfLed.get(firstIndex));

    //merge clusters in the local neighborhood
    int indexInStripeA=ledNetInfos[firstIndex].indexInStripe;
    int indexInStripeB=ledNetInfos[secondIndex].indexInStripe;
    int stripeLengthA=ledNetInfos[firstIndex].stripeLength;
    int stripeLengthB=ledNetInfos[secondIndex].stripeLength;
    // always check if the shifted pos is stilll inside the stripe
    for (int offA=-1; offA<=1; offA++) {
      int relPosA=indexInStripeA+offA;
      if (relPosA>0&&relPosA<stripeLengthA) {
        for (int offB=-1; offB<=1; offB++) {
          int relPosB=indexInStripeB+offB;
          if (relPosB>0&&relPosB<stripeLengthB) {
            int absPosA=firstIndex+offA;
            int absPosB=secondIndex+offB;
            if (clusterOfLed.get(absPosA)!=null &&clusterOfLed.get(absPosB)!=null&&clusterOfLed.get(absPosA)!=clusterOfLed.get(absPosB)) {
              TreeSet<Integer> firstCluster=clusterOfLed.get(absPosA);
              TreeSet<Integer> secondCluster=clusterOfLed.get(absPosB);
              mergeClusters(firstCluster, secondCluster, clusters, clusterOfLed);
            }
          }
        }
      }
    }

    //end merge

    System.out.println("found"+ clusters.size()+"clusters");


    // add the center plus surrounding of the crossing
    ArrayList<LedNetworkNode> nodes= new ArrayList<LedNetworkNode>();
    int curNodeId=0;


    for (TreeSet<Integer> curCluster : clusters) {
      TreeSet<Integer> curatedCluster=new TreeSet<Integer>();
      /*
      // calculate cluster center
       PVector center=new PVector();
       for (Integer ledIdx : curCluster) {
       center.add(ledPositions[ledIdx]);
       }
       
       center.mult(1.0f/(float)curCluster.size());
       */
      nodes.add(new LedNetworkNode(curNodeId, curatedCluster));

      /////////////////////////////prepare output
      //package clusters that were found into a nice wrapper class
      // nodes.sort((n1, n2) -> (int)(10000*(n1.position.x-n2.position.x)));

      //set node info for all the leds:

      for (LedNetworkNode curNode : nodes) {
        curNode.id=curNodeId; //renumber after sorting
        curNodeId++;
        for (Integer thisLedIdx : curNode.ledIndices) {
          ledNetInfos[thisLedIdx].partOfNode=curNode;
        }
      }
    }
    return nodes;
  }

  //merge two clusters
  static void mergeClusters(TreeSet<Integer> firstCluster, TreeSet<Integer> secondCluster, ArrayList <TreeSet<Integer>> clusters, ArrayList <TreeSet<Integer>> clusterOfLed) {
    // put all leds of second cluster into the first
    firstCluster.addAll(secondCluster);
    //make all the leds from the second cluster point to the first

    for (Integer indexToUpdate : secondCluster) {
      clusterOfLed.set(indexToUpdate, firstCluster);
    }
    // remove obsolete second cluster from collection
    clusters.remove(secondCluster);
  }

  static void paintNodes(ArrayList<LedNetworkNode> nodes, LedColor[] ledColors) {
    int clustIdx=0;
    for (LedNetworkNode curNode : nodes) {
      for (Integer thisLedIdx : curNode.ledIndices) {
        ledColors[thisLedIdx].set(new LedColor(
          (float)(Math.sin(clustIdx)*0.5+0.5), 
          (float)(Math.cos(clustIdx*4.1)*0.5+0.5), 
          (float)(Math.sin(clustIdx*0.1+2)*0.5+0.5), 
          1.0f
          ));
      }
      clustIdx++;
    }
  }
}
