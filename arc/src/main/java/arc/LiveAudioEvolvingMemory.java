package arc;

import java.util.Random;

public class LiveAudioEvolvingMemory {
    
    public static void processAudioChunk(AdultSOM adult, double[] rawChunk) {
        // Create BabySOM using config
        Random rng = new Random();
        
        BabySOM baby = new BabySOM(
            ARCConfig.BABY_NODES, 
            ARCConfig.BABY_INPUT_DIM, 
            rng
        );

        // Convert raw chunk to features
        double[] features = FFTFeatures.spectrumBins(rawChunk, ARCConfig.MIC_SAMPLE_RATE, ARCConfig.FEATURE_BINS);


        if (ARCConfig.PRE_BAND_ENABLED) {
            features = applyBandSelect(
                features,
                ARCConfig.MIC_SAMPLE_RATE,
                ARCConfig.PRE_BAND_LOW_HZ,
                ARCConfig.PRE_BAND_HIGH_HZ,
                ARCConfig.PRE_BAND_MODE,
                ARCConfig.PRE_BAND_OUTSIDE_GAIN,
                ARCConfig.PRE_BAND_RENORMALIZE
            );
        }


        // Train for configured epochs
        for (int epoch = 0; epoch < ARCConfig.BABY_EPOCHS; epoch++) {
            baby.train(features);
        }

        // Get the ENTIRE flattened map from the baby
        // Adult will downsample each baby node to a scalar
        double[] babyFlatMap = baby.getFullMapFlat();
        
        // Adult learns from complete baby topology
        adult.learnFromBaby(babyFlatMap);
    }
    private static double[] applyBandSelect(
    	    double[] features,
    	    int sampleRate,
    	    double lowHz,
    	    double highHz,
    	    int mode,
    	    double outsideGain,
    	    boolean renormalize
    	) {
    	    int n = features.length;
    	    double[] out = new double[n];

    	    // Each bin corresponds roughly to a frequency region from 0..Nyquist.
    	    double nyquist = sampleRate / 2.0;

    	    // Convert Hz range to bin indices.
    	    int lowBin = (int) Math.floor((lowHz / nyquist) * (n - 1));
    	    int highBin = (int) Math.ceil((highHz / nyquist) * (n - 1));

    	    lowBin = Math.max(0, Math.min(n - 1, lowBin));
    	    highBin = Math.max(0, Math.min(n - 1, highBin));
    	    if (lowBin > highBin) { int t = lowBin; lowBin = highBin; highBin = t; }

    	    double inSum = 0.0;
    	    double outSum = 0.0;

    	    for (int i = 0; i < n; i++) {
    	        double v = features[i];
    	        inSum += v;

    	        boolean inBand = (i >= lowBin && i <= highBin);

    	        if (mode == 0) { // ZERO_OUTSIDE
    	            out[i] = inBand ? v : 0.0;
    	        } else { // WEIGHT_OUTSIDE
    	            out[i] = inBand ? v : (v * outsideGain);
    	        }

    	        outSum += out[i];
    	    }

    	    if (renormalize) {
    	        // Keep overall energy scale stable after filtering
    	        if (outSum > 1e-12) {
    	            double scale = inSum / outSum;
    	            for (int i = 0; i < n; i++) out[i] *= scale;
    	        }
    	    }

    	    return out;
    	}

    private static double[] audioToFeatures(double[] raw, int bins) {
        double[] features = new double[bins];
        int perBin = Math.max(1, raw.length / bins);

        for (int i = 0; i < bins; i++) {
            int s = i * perBin;
            int e = Math.min(s + perBin, raw.length);

            double sum = 0.0;
            for (int j = s; j < e; j++) {
                sum += Math.abs(raw[j]);
            }
            features[i] = sum / Math.max(1, (e - s));
        }
        return features;
    }
}