import java.time.Duration;
import java.time.Instant;


///////////////////////////////////////////////////////////
// turns on a single hardware LED stripe that can lit up incrementally
///////////////////////////////////////////////////////////

public class LedStripeFullActivationEffect implements runnableLedEffect {
  String name = "Activate Stripe";
  String id;
  LedColor[] bufferLedColors;
  LedInStripeInfo[] stripeInfo;
  int activatedStripeIndex = 0;
  int activatedStripeLength = 0;
  float stripesBrightness = 1;
  float stripesBrightnessDelta = 0.1f;
  int ledStripeActivationIndex = -1;
  int numStripes;
  public static enum StripeChange {
    NONE, ACTIVATE_NEXT_STRIPE_LED, DEACTIVATE_LAST_STRIPE_LED, INCREASE_BRIGHTNESS, DECREASE_BRIGHTNESS, PREV_BLACK_STRIPE, NEXT_BLACK_STRIPE;
  }
  StripeChange stripeChange = StripeChange.NONE;
  Instant lastChangeTime = Instant.now(); 
  static int minMillisBetweenChanges = 100;

  LedStripeFullActivationEffect(String _id, LedInStripeInfo[] _stripeInfo, int _numStripes) {
    id = _id;
    stripeInfo = _stripeInfo;
    numStripes = _numStripes;
    bufferLedColors = LedColor.createColorArray(stripeInfo.length);
    for(int i = 0; i < stripeInfo.length; i++){
      if(stripeInfo[i].whichStripeIndex == activatedStripeIndex){
        activatedStripeLength +=1;
      }
    }
  }

  public LedColor[] drawMe() {
    int activatedStripeLedCurrentIndex = 0;
    for(int i = 0; i < stripeInfo.length; i++){
      if(stripeInfo[i].whichStripeIndex == activatedStripeIndex){
        LedColor col;
        if(activatedStripeLedCurrentIndex <= ledStripeActivationIndex) {
          col = new LedColor(1, 1, 1);
        } else {
          col = new LedColor(0, 0, 0);
        }
        bufferLedColors[i] = col;
        activatedStripeLedCurrentIndex += 1;
      } else {
        bufferLedColors[i] = new LedColor(1, 1, 1);
      }
      bufferLedColors[i].mult(stripesBrightness);
    }
    return bufferLedColors;
  }
  
  
  private void increaseStripesBrightness() {
    if(changeNotAllowed()){
      return;
    }
    stripesBrightness += stripesBrightnessDelta;
    stripesBrightness = Math.min(stripesBrightness, 1f);
    stripesBrightness = Math.max(stripesBrightness, 0f);
  }
  
  private void decreaseStripesBrightness() {
    if(changeNotAllowed()){
      return;
    }
    stripesBrightness -= stripesBrightnessDelta;
    stripesBrightness = Math.min(stripesBrightness, 1f);
    stripesBrightness = Math.max(stripesBrightness, 0f);
  }

  private void activateNextStripeLed() {
    if(changeNotAllowed()){
      return;
    }
    ledStripeActivationIndex += 1;
    ledStripeActivationIndex = Math.min(ledStripeActivationIndex, activatedStripeLength);
  }
  
 private void deactivateLastStripeLed() {
    if(changeNotAllowed()){
      return;
    }
    ledStripeActivationIndex -= 1;
    ledStripeActivationIndex = Math.max(ledStripeActivationIndex, -1);
  }
  
  private void nextBlackStripe(){
    if(changeNotAllowed()){
      return;
    }
    int temp = activatedStripeIndex + 1;
    temp = Math.min(temp, numStripes-1);
    temp = Math.max(temp, 0);
    activatedStripeIndex = temp;
  }
  
  private void prevBlackStripe(){
    if(changeNotAllowed()){
      return;
    }
    int temp = activatedStripeIndex - 1;
    temp = Math.min(temp, numStripes-1);
    temp = Math.max(temp, 0);
    activatedStripeIndex = temp;
  }
  
  public void changeStripe(){
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
    }
  }

  public String getName() {
    return name;
  }
  
  private boolean changeNotAllowed(){
    Instant currentTime = Instant.now();
    Duration timeElapsed = Duration.between(lastChangeTime, currentTime);
    if(timeElapsed.toMillis() > minMillisBetweenChanges) {
      lastChangeTime = currentTime;
      return false;
    }
    return true;
  }

}
