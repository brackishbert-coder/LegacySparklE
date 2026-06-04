package arc;

public final class FFTFeatures {
    private FFTFeatures() {}

    /**
     * 64-bin magnitude spectrum (0..Nyquist), log-compressed + normalized.
     * Uses a Hann window + naive DFT on a fixed-size frame.
     * Fast enough for experimentation; can swap to a true FFT later.
     */
    public static double[] spectrumBins(double[] samples, int sampleRate, int bins) {
        if (samples == null || samples.length == 0) return new double[bins];

        // Choose a reasonable frame size
        int n = 512;
        if (samples.length >= 1024) n = 1024;
        else if (samples.length >= 512) n = 512;
        else if (samples.length >= 256) n = 256;

        double[] frame = new double[n];

        // Centered-ish copy + Hann window
        int start = Math.max(0, (samples.length - n) / 2);
        for (int i = 0; i < n; i++) {
            double x = (start + i < samples.length) ? samples[start + i] : 0.0;
            double w = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (n - 1)));
            frame[i] = x * w;
        }

        int maxK = n / 2; // 0..Nyquist
        double[] mags = new double[maxK];

        for (int k = 0; k < maxK; k++) {
            double re = 0.0, im = 0.0;
            double ang = -2.0 * Math.PI * k / n;
            for (int t = 0; t < n; t++) {
                double a = ang * t;
                re += frame[t] * Math.cos(a);
                im += frame[t] * Math.sin(a);
            }
            mags[k] = Math.sqrt(re * re + im * im);
        }

        // Downsample into `bins`
        double[] out = new double[bins];
        int perBin = Math.max(1, mags.length / bins);

        for (int b = 0; b < bins; b++) {
            int s = b * perBin;
            int e = Math.min(mags.length, s + perBin);
            double sum = 0.0;
            for (int i = s; i < e; i++) sum += mags[i];
            double avg = sum / Math.max(1, (e - s));
            out[b] = Math.log1p(avg);
        }

        // Normalize to [0,1]
        double max = 1e-12;
        for (double v : out) max = Math.max(max, v);
        for (int i = 0; i < out.length; i++) out[i] /= max;

        return out;
    }
}
