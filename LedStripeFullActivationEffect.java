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
  float stripesBrightness = 1;
  float stripesBrightnessDelta = 0.1f;
  int numStripes;
  
  int activatedStripeLength;
  int activatedStripeIndex = 0;
  int ledStripeActivationIndex = -1;
  
  int activatedBrightStripeLength;
  int brightLedStripeIndex = -1;
  int brightLedStripeActivationIndex = -1;
 
  public static enum StripeChange {
    NONE, ACTIVATE_NEXT_STRIPE_LED, DEACTIVATE_LAST_STRIPE_LED, INCREASE_BRIGHTNESS,
    DECREASE_BRIGHTNESS, PREV_BLACK_STRIPE, NEXT_BLACK_STRIPE, PREV_BRIGHT_STRIPE, NEXT_BRIGHT_STRIPE,
    ACTIVATE_NEXT_BRIGHT_STRIPE_LED, DEACTIVATE_LAST_BRIGHT_STRIPE_LED;
  }
  StripeChange stripeChange = StripeChange.NONE;
  Instant lastChangeTime = Instant.now(); 
  static int minMillisBetweenChanges = 100;

  LedStripeFullActivationEffect(String _id, LedInStripeInfo[] _stripeInfo, int _numStripes) {
    id = _id;
    stripeInfo = _stripeInfo;
    numStripes = _numStripes;
    bufferLedColors = LedColor.createColorArray(stripeInfo.length);
  }
  
  private boolean activatedStripeLedIsInBounds(int activatedStripeLedCurrentIndex){
    return activatedStripeLedCurrentIndex <= ledStripeActivationIndex && activatedStripeLedCurrentIndex >= (ledStripeActivationIndex - 3);
  }
  
  private boolean brightStripeLedIsInBounds(int stripeIndex, int brightLedStripeLedCurrentIndex){
    if(brightLedStripeIndex >= 0){
      boolean res = stripeIndex == brightLedStripeIndex;
      if(brightLedStripeActivationIndex >= 0){
       res = res && (brightLedStripeLedCurrentIndex <= brightLedStripeActivationIndex && brightLedStripeLedCurrentIndex >= (brightLedStripeActivationIndex - 3));
      }
      return res;
    }
    return true;
  }

  public LedColor[] drawMe() {
    int activatedStripeLedCurrentIndex = 0;
    for(int i = 0; i < stripeInfo.length; i++){
      int currentStripeIndex = stripeInfo[i].whichStripeIndex;
      int ledIndexInStripe = stripeInfo[i].indexInStripe;
      if(currentStripeIndex == activatedStripeIndex){
        LedColor col;
        if(activatedStripeLedIsInBounds(ledIndexInStripe)) {
          col = new LedColor(1, 1, 1);
        } else {
          col = new LedColor(0, 0, 0);
        }
        bufferLedColors[i] = col;
        activatedStripeLedCurrentIndex++;
      } else if(brightStripeLedIsInBounds(currentStripeIndex, ledIndexInStripe)) {
        bufferLedColors[i] = new LedColor(1, 1, 1);
      } else {
        bufferLedColors[i] = new LedColor(0, 0, 0);
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
  
  private void activateNextBrightStripeLed() {
    if(changeNotAllowed()){
      return;
    }
    brightLedStripeActivationIndex++;
    brightLedStripeActivationIndex = Math.min(brightLedStripeActivationIndex, activatedBrightStripeLength);
  }
  
 private void deactivateLastBrightStripeLed() {
    if(changeNotAllowed()){
      return;
    }
    brightLedStripeActivationIndex--;
    brightLedStripeActivationIndex = Math.max(brightLedStripeActivationIndex, -1);
  }
  
  private void nextBlackStripe(){
    if(changeNotAllowed()){
      return;
    }
    int temp = activatedStripeIndex + 1;
    temp = Math.min(temp, numStripes-1);
    temp = Math.max(temp, 0);
    activatedStripeIndex = temp;
    setActivatedStripeLength();
  }
  
  private void prevBlackStripe(){
    if(changeNotAllowed()){
      return;
    }
    int temp = activatedStripeIndex - 1;
    temp = Math.min(temp, numStripes-1);
    temp = Math.max(temp, 0);
    activatedStripeIndex = temp;
    setActivatedStripeLength();
  }
  
  private void setActivatedStripeLength(){
     activatedStripeLength = 0;
     for(int i = 0; i < stripeInfo.length; i++){
      if(stripeInfo[i].whichStripeIndex == activatedStripeIndex){
        activatedStripeLength +=1;
      }
    }
  }
 
  private void setActivatedBrightStripeLength(){
     activatedBrightStripeLength = 0;
     for(int i = 0; i < stripeInfo.length; i++){
      if(stripeInfo[i].whichStripeIndex == brightLedStripeIndex){
        activatedBrightStripeLength +=1;
      }
    }
  }
  
  private void nextBrightStripe(){
    if(changeNotAllowed()){
      return;
    }
    int temp = brightLedStripeIndex + 1;
    temp = Math.min(temp, numStripes-1);
    temp = Math.max(temp, 0);
    brightLedStripeIndex = temp;
    setActivatedBrightStripeLength();
  }
  
  private void prevBrightStripe(){
    if(changeNotAllowed()){
      return;
    }
    int temp = brightLedStripeIndex - 1;
    temp = Math.min(temp, numStripes-1);
    temp = Math.max(temp, 0);
    brightLedStripeIndex = temp;
    setActivatedBrightStripeLength();
  }
  
  //activate all bright Leds on stripe, necessary when getting out of cycling bright stripes
  public void activateAllBrightStripes(){
    brightLedStripeIndex = -1;
  }
  
 public void setStripeChange(StripeChange _stripeChange) {
     
    if((_stripeChange == StripeChange.PREV_BRIGHT_STRIPE || _stripeChange == StripeChange.NEXT_BRIGHT_STRIPE) 
      && (stripeChange != StripeChange.PREV_BRIGHT_STRIPE && stripeChange != StripeChange.NEXT_BRIGHT_STRIPE && stripeChange != StripeChange.NONE)){
      brightLedStripeIndex = 0;
    }
    stripeChange = _stripeChange;
  }
  
  //call this function after setting the desired mode of change any time necessary
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
    } else if (stripeChange == StripeChange.NEXT_BRIGHT_STRIPE) {
      this.nextBrightStripe();
    } else if (stripeChange == StripeChange.PREV_BRIGHT_STRIPE) {
      this.prevBrightStripe();
    } else if (stripeChange == StripeChange.ACTIVATE_NEXT_BRIGHT_STRIPE_LED) {
      this.activateNextBrightStripeLed();
    } else if (stripeChange == StripeChange.DEACTIVATE_LAST_BRIGHT_STRIPE_LED) {
      this.deactivateLastBrightStripeLed();
    }
    
  }

  public String getName() {
    return name;
  }
  
  //ensure changes are only allowed in set time intervals
  //this enables smooth changes when holding down keys
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
