// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

/** RGB/HSV conversion shared with the picker; no rendering or platform dependencies. */
public final class ColorMath {
    private ColorMath() {}
    public static int hsv(float hue, float saturation, float value) {
        hue = (hue % 1 + 1) % 1;
        saturation = Math.clamp(saturation, 0F, 1F); value = Math.clamp(value, 0F, 1F);
        float h = hue * 6, f = h - (int) h;
        float p = value * (1 - saturation), q = value * (1 - f * saturation), t = value * (1 - (1 - f) * saturation);
        float[] rgb = switch ((int) h % 6) {
            case 0 -> new float[]{value,t,p}; case 1 -> new float[]{q,value,p};
            case 2 -> new float[]{p,value,t}; case 3 -> new float[]{p,q,value};
            case 4 -> new float[]{t,p,value}; default -> new float[]{value,p,q};
        };
        return (Math.round(rgb[0]*255)<<16) | (Math.round(rgb[1]*255)<<8) | Math.round(rgb[2]*255);
    }
    public static float[] toHsv(int rgb) {
        float r = ((rgb>>16)&255)/255F, g = ((rgb>>8)&255)/255F, b = (rgb&255)/255F;
        float max = Math.max(r,Math.max(g,b)), min = Math.min(r,Math.min(g,b)), d = max-min;
        float h = d == 0 ? 0 : max == r ? ((g-b)/d+6)%6 : max == g ? (b-r)/d+2 : (r-g)/d+4;
        return new float[]{h/6, max == 0 ? 0 : d/max, max};
    }
    public static Integer parseHex(String input) {
        String value = input.startsWith("#") ? input.substring(1) : input;
        return value.matches("[0-9a-fA-F]{6}") ? Integer.parseInt(value,16) : null;
    }
}
