package dev.sbs.renderer.draw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ColorKitTest {

    @Test
    @DisplayName("pack/unpack roundtrips an ARGB triple")
    void packUnpackRoundtrip() {
        int packed = ColorKit.pack(0x80, 0x12, 0x34, 0x56);
        assertThat(ColorKit.alpha(packed), equalTo(0x80));
        assertThat(ColorKit.red(packed), equalTo(0x12));
        assertThat(ColorKit.green(packed), equalTo(0x34));
        assertThat(ColorKit.blue(packed), equalTo(0x56));
    }

    @Test
    @DisplayName("hsvToArgb produces opaque white for value=1 saturation=0")
    void hsvPureValue() {
        int white = ColorKit.hsvToArgb(0f, 0f, 1f);
        assertThat(ColorKit.alpha(white), equalTo(0xFF));
        assertThat(ColorKit.red(white), equalTo(0xFF));
        assertThat(ColorKit.green(white), equalTo(0xFF));
        assertThat(ColorKit.blue(white), equalTo(0xFF));
    }

    @Test
    @DisplayName("hsvToArgb at hue=0 saturation=1 value=1 produces red")
    void hsvPureRed() {
        int red = ColorKit.hsvToArgb(0f, 1f, 1f);
        assertThat(ColorKit.red(red), equalTo(0xFF));
        assertThat(ColorKit.green(red), equalTo(0x00));
        assertThat(ColorKit.blue(red), equalTo(0x00));
    }

    @Test
    @DisplayName("hsvToArgb at hue=120 saturation=1 value=1 produces green")
    void hsvPureGreen() {
        int green = ColorKit.hsvToArgb(120f, 1f, 1f);
        assertThat(ColorKit.red(green), equalTo(0x00));
        assertThat(ColorKit.green(green), equalTo(0xFF));
        assertThat(ColorKit.blue(green), equalTo(0x00));
    }

    @Test
    @DisplayName("blend NORMAL with opaque src returns src unchanged")
    void blendNormalOpaqueSource() {
        int src = 0xFF123456;
        int dst = 0xFFABCDEF;
        assertThat(ColorKit.blend(src, dst, BlendMode.NORMAL), equalTo(src));
    }

    @Test
    @DisplayName("blend NORMAL with transparent src returns dst unchanged")
    void blendNormalTransparentSource() {
        int src = 0x00FFFFFF;
        int dst = 0xFFABCDEF;
        assertThat(ColorKit.blend(src, dst, BlendMode.NORMAL), equalTo(dst));
    }

    @Test
    @DisplayName("blend MULTIPLY darkens towards black for mid-gray src")
    void blendMultiplyDarkens() {
        int src = 0xFF808080;
        int dst = 0xFFFFFFFF;
        int blended = ColorKit.blend(src, dst, BlendMode.MULTIPLY);
        int r = ColorKit.red(blended);
        assertThat(r, lessThanOrEqualTo(0x81));
    }

    @Test
    @DisplayName("blend ADD saturates at 0xFF")
    void blendAddSaturates() {
        int src = 0xFFFFFFFF;
        int dst = 0xFF808080;
        int blended = ColorKit.blend(src, dst, BlendMode.ADD);
        assertThat(ColorKit.red(blended), equalTo(0xFF));
        assertThat(ColorKit.green(blended), equalTo(0xFF));
        assertThat(ColorKit.blue(blended), equalTo(0xFF));
    }

    @Test
    @DisplayName("sampleColormap returns the pixel at the expected temp/humidity coordinate")
    void sampleColormapReadsCorrectPixel() {
        int[] map = new int[256 * 256];
        map[128 * 256 + 128] = 0xFFAABBCC;
        // For temp=0.5, adjRain = 0.5*0.5 = 0.25 -> y = (1 - 0.25) * 255 = 191 (not 128)
        // Use temp=1.0, downfall=0.0 -> adjRain = 0 -> y = 255. Use temp=0.5, downfall=1.0 -> adjRain = 0.5 -> y = 127, x = 127.
        map[127 * 256 + 127] = 0xFF112233;

        int sampled = ColorKit.sampleColormap(map, 0.5f, 1.0f);
        assertThat(sampled, is(equalTo(0xFF112233)));
    }

    @Test
    @DisplayName("tint multiplies RGB channels and preserves alpha")
    void tintPreservesAlpha() {
        dev.simplified.image.PixelBuffer source = dev.simplified.image.PixelBuffer.of(new int[]{ 0x80FFFFFF }, 1, 1);
        dev.simplified.image.PixelBuffer tinted = ColorKit.tint(source, 0xFFFF0000);
        assertThat(ColorKit.alpha(tinted.getPixel(0, 0)), equalTo(0x80));
        assertThat(ColorKit.red(tinted.getPixel(0, 0)), equalTo(0xFF));
        assertThat(ColorKit.green(tinted.getPixel(0, 0)), equalTo(0x00));
        assertThat(ColorKit.blue(tinted.getPixel(0, 0)), equalTo(0x00));
    }

}
