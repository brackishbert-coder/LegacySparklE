package arc;

import javax.sound.sampled.*;
import java.io.*;

public class ParadoxBasinWavGen {

    public static void main(String[] args) throws Exception {
        final float sr = 44100f;
        final double f0 = 220.0;           // try 220 or 440
        final double segSec = 0.25;        // 250ms segments (matches your tick cadence)
        final double totalSec = 20.0;      // total length
        final double fadeSec = 0.005;      // prevent clicks
        final double amp = 0.75;           // overall amplitude

        int totalSamples = (int) (totalSec * sr);
        byte[] pcm = new byte[totalSamples * 2]; // mono 16-bit

        int segSamples = (int) (segSec * sr);
        int fadeSamples = Math.max(1, (int) (fadeSec * sr));

        double phase = 0.0;
        double phaseInc = 2.0 * Math.PI * f0 / sr;

        for (int n = 0; n < totalSamples; n++) {
            int seg = n / segSamples;
            boolean modeB = (seg % 2) == 1; // alternate A/B

            // ---------- oscillator ----------
            double x;
            if (!modeB) {
                // Mode A: "smooth" (mostly fundamental)
                // Keep it simple: fundamental + small harmonics
                x = 1.00 * Math.sin(phase)
                  + 0.18 * Math.sin(2 * phase)
                  + 0.10 * Math.sin(3 * phase);
            } else {
                // Mode B: "bright" (saw-ish via harmonic series)
                // Harmonics with 1/k amplitude (odd+even)
                double s = 0.0;
                int H = 12; // number of harmonics
                for (int k = 1; k <= H; k++) {
                    s += (1.0 / k) * Math.sin(k * phase);
                }
                x = s;
            }

            // ---------- normalize-ish & soft clip ----------
            x *= 0.35;               // tame harmonic sum
            x = Math.tanh(x);

            // ---------- segment boundary fade (avoid clicks) ----------
            int posInSeg = n % segSamples;
            double env = 1.0;
            if (posInSeg < fadeSamples) {
                env = posInSeg / (double) fadeSamples;
            } else if (posInSeg > segSamples - fadeSamples) {
                env = (segSamples - posInSeg) / (double) fadeSamples;
            }
            x *= env;

            // ---------- write sample ----------
            short sample = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, (int) (x * amp * Short.MAX_VALUE)));

            pcm[2 * n] = (byte) (sample & 0xFF);
            pcm[2 * n + 1] = (byte) ((sample >> 8) & 0xFF);

            phase += phaseInc;
            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
        }

        AudioFormat fmt = new AudioFormat(sr, 16, 1, true, false);
        ByteArrayInputStream bais = new ByteArrayInputStream(pcm);
        AudioInputStream ais = new AudioInputStream(bais, fmt, totalSamples);

        File out = new File("paradox_basin.wav");
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
        System.out.println("Wrote: " + out.getAbsolutePath());
    }
}
