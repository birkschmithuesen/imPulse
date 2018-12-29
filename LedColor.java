import processing.core.*;
import java.awt.Color;

///////////////////////////////////////////////////////////
// LedColor class  -uses x,y,z as r, g, b components 
// all colors range from 0 to 1 
///////////////////////////////////////////////////////////
public class LedColor extends PVector {

	float a = 1; // alpha value

	public enum LedAlphaMode {
		ADD, SUBSTRACT, MULTIPLY, BLEND, NORMAL, REPLACE
	}

	LedColor() {
	}

	;

	LedColor(float r, float g, float b) {
		x = r;
		y = g;
		z = b;
		a = 1;
	}

	LedColor(float r, float g, float b, float _a) {
		x = r;
		y = g;
		z = b;
		a = _a;
	}

	void mixWithAlpha(LedColor newColor, LedAlphaMode mode, float fraction) {
		switch (mode) {
		case ADD:
			x += newColor.x * fraction * newColor.a;
			y += newColor.y * fraction * newColor.a;
			z += newColor.z * fraction * newColor.a;
			a += fraction * newColor.a;
			break;
		case SUBSTRACT:
			x -= newColor.x * fraction * newColor.a;
			y -= newColor.y * fraction * newColor.a;
			z -= newColor.z * fraction * newColor.a;
			break;
		case MULTIPLY:
			multiplyComponentsBy(newColor);
			break;
		case BLEND:
			if (newColor.a + a > 0) {
				lerp(newColor, fraction * (newColor.a / (newColor.a + a)));
			} else {
				lerp(newColor, fraction);
			}
			break;
		case NORMAL:
			a = (a - newColor.a * fraction);
			if (a < 0) {
				a = 0;
			}
			x = x * a + newColor.x * newColor.a * fraction;
			y = y * a + newColor.y * newColor.a * fraction;
			z = z * a + newColor.z * newColor.a * fraction;
			a = a + newColor.a * fraction;
			/*
			 * if (newColor.a+a>0) { lerp(newColor, fraction*(newColor.a/(newColor.a+a))); }
			 * else { lerp(newColor, fraction); }
			 */
			break;
		case REPLACE:
			set(newColor);
			break;
		default:
			set(newColor);
		}
	}

	void multiplyComponentsBy(LedColor other) {
		x *= other.x;
		y *= other.y;
		z *= other.z;
		a *= other.a;
	}

	// make sure the color is in a valid range
	void clamp() {
		if (x > 1) {
			x = 1;
		}
		if (y > 1) {
			y = 1;
		}
		if (z > 1) {
			z = 1;
		}
		if (a > 1) {
			a = 1;
		}

		if (x < 0) {
			x = 0;
		}
		if (y < 0) {
			y = 0;
		}
		if (z < 0) {
			z = 0;
		}
		if (a < 0) {
			a = 0;
		}
	}

	void set(LedColor c) {
		x = c.x;
		y = c.y;
		z = c.z;
		a = c.a;
	}

	void setAlpha(float _a) {
		a = _a;
	}
	// get a color value that can be used for processing functions such as "stroke"

	int getAsInt32Color(int a) {
		int r = (int) (x * 255);
		int g = (int) (y * 255);
		int b = (int) (z * 255);
		if (r > 255) {
			r = 255;
		}
		if (g > 255) {
			g = 255;
		}
		if (b > 255) {
			b = 255;
		}

		if (r < 0) {
			r = 0;
		}
		if (g < 0) {
			g = 0;
		}
		if (b < 0) {
			b = 0;
		}

		return (b | g << 8 | r << 16 | a << 24);
	}

	// set from "processing style" integer (such as those you get from color() or an
	// image)
	void setFromInt32Color(int newColor) {
		x = ((float) ((newColor >> 16) | 0xFF)) / 255.0f;
		y = ((float) ((newColor >> 8) | 0xFF)) / 255.0f;
		z = ((float) ((newColor) | 0xFF)) / 255.0f;
	}

	// set from HSB values (must be float between 0 and 1)
	void setFromHSB(float hue, float saturation, float brightness) {
		float[] RGBColor = Color.getHSBColor(hue, saturation, brightness).getRGBComponents(null);
		x = RGBColor[0];
		y = RGBColor[1];
		z = RGBColor[2];
		// Color.getHSBColor(hue, saturation, brightness).getRGBComponents(null);
		// System.out.println(Color.getHSBColor(hue, saturation,
		// brightness).getRGBComponents(null)[0]+","+Color.getHSBColor(hue, saturation,
		// brightness).getRGBComponents(null)[1]+","+Color.getHSBColor(hue, saturation,
		// brightness).getRGBComponents(null)[2]);
	}

	public boolean isNotBlack() {
		return ((x > 0.001 || y > 0.001 || z > 0.001));
	}

	///////////////////////////////////////////////
	// some convenience functions for manipulating arrays of LedColor
	///////////////////////////////////////////////
	// build an array of preinitialized LED color objects
	public static LedColor[] createColorArray(int nLeds) {
		LedColor[] ledColors = new LedColor[nLeds];
		for (int i = 0; i < nLeds; i++) {
			ledColors[i] = new LedColor();
		}
		return ledColors;
	}

	// build an two dimensional array of preinitialized LED color objects
	public static LedColor[][] createColorArrayFrames(int frames, int nLeds) {
		LedColor[][] ledColors = new LedColor[frames][nLeds];
		for (int i = 0; i < frames; i++) {
			for (int j = 0; j < nLeds; j++) {
				ledColors[i][j] = new LedColor();
			}
		}
		return ledColors;
	}

	// mix colors from two arrays
	public static void mixWithAlpha(LedColor[] outputColors, LedColor[] inputColors, LedAlphaMode alphamode,
			float fraction) {
		for (int i = 0; i < outputColors.length; i++) {
			outputColors[i].mixWithAlpha(inputColors[i], alphamode, fraction);
		}
	}

	// copy colors form array to array
	public static void set(LedColor[] outputColors, LedColor[] inputColors) {
		for (int i = 0; i < outputColors.length; i++) {
			outputColors[i].set(inputColors[i]);
		}
	}

	// copy colors from one color to array
	public static void set(LedColor[] outputColors, LedColor inputColor) {
		for (int i = 0; i < outputColors.length; i++) {
			outputColors[i].set(inputColor);
		}
	}

	// set all leds to black
	public static void setAllBlack(LedColor[] outputColors) {
		for (int i = 0; i < outputColors.length; i++) {
			outputColors[i].set(new LedColor(0, 0, 0));
		}
	}

	// multiply all colors in an array by a constant
	public static void mult(LedColor[] colors, float p) {
		for (int i = 0; i < colors.length; i++) {
			colors[i].mult(p);
		}
	}

	// multiply all colors in an array by a constant
	public static void mult(LedColor[] colors, LedColor otherCcolor) {
		for (int i = 0; i < colors.length; i++) {
			colors[i].multiplyComponentsBy(otherCcolor);
		}
	}

	// substract a color from all colors in Array
	public static void substract(LedColor[] colors, LedColor s) {
		for (int i = 0; i < colors.length; i++) {
			colors[i].sub(s);
		}
	}

	// clamp all colors in array
	public static void clamp(LedColor[] colors) {
		for (int i = 0; i < colors.length; i++) {
			colors[i].clamp();
		}
	}
}
