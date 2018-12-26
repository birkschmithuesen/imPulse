
////////////////////////////////////////////////////////////////////////////
//Information about an LED Position in a stripe - intended to be used in an Arraylist and passed to rendering/effect functions
////////////////////////////////////////////////////////////////////////////
class LedInStripeInfo {
  //create an Array of Led-wise information based on 

  public LedInStripeInfo(int prevIndex_, int nextIndex_, int indexInStripe_, int whichStripeIndex_, int stripeLength_)
  {
    prevIndex=prevIndex_;
    nextIndex=nextIndex_;
    indexInStripe=indexInStripe_;
    whichStripeIndex=whichStripeIndex_;
    stripeLength=stripeLength_;
  }

  public int getPrevIndex() {
    return prevIndex;
  }  // returns -1 if the LED is the first of it's stripe


  public int getNextIndex() {
    return nextIndex;
  }  // returns -1 if the LED is the last of it's stripe

  public int prevIndex; // -1 if the LED is the first of it's stripe
  public int nextIndex; // -1 if the LED is the last of it's stripe

  public int indexInStripe;  
  public int whichStripeIndex;  
  public int stripeLength;
};
