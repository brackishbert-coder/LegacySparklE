package arc;

import java.util.Map;



public class ARCConfig {

    // === AUDIO DEVICE SELECTION ===
    public static volatile int OUTPUT_DEVICE_INDEX = -1;  // -1 = use system default
    public static volatile int INPUT_DEVICE_INDEX = -1;   // -1 = use system default

    // === AUDIO INPUT PARAMETERS ===
    public static volatile double GAIN_MULTIPLIER = 50.0;
    public static volatile double NOISE_GATE = 0.0001;
    public static volatile int FEATURE_BINS = 64;
    public static volatile int INPUT_BUFFER_SIZE = 2048;
    public static volatile int MIC_SAMPLE_RATE = 44100;

    // === BABY SOM PARAMETERS ===
    public static volatile int BABY_NODES = 16;
    public static volatile int BABY_INPUT_DIM = 64;
    public static volatile double BABY_LEARNING_RATE = 0.10;
    public static volatile double BABY_RADIUS = 0.8;
    public static volatile double BABY_SIGMA = 0.18;
    public static volatile int BABY_EPOCHS = 10;

    // === QUEUE PARAMETERS ===
    public static volatile int QUEUE_CAPACITY = 15;

    // === ADULT SOM PARAMETERS (2-D GRID) ===
    public static volatile int ADULT_W = 8;
    public static volatile int ADULT_H = 8;

    // Adult vector dimensionality. For a coherent pipeline, this should match FEATURE_BINS and BABY_INPUT_DIM.
    public static volatile int ADULT_DIM = 64;

    public static volatile double ADULT_LEARNING_RATE = 0.001;

    // Neighborhood parameters on the 2-D grid
    // We use a Gaussian influence; RADIUS is an optional cutoff in grid distance.
    public static volatile double ADULT_RADIUS = 3.0;
    public static volatile double ADULT_SIGMA = 1.5;

    // === ADAPTIVE QUEUE PULL PARAMETERS ===
    // Adult SOM self-regulates learning based on BMU stability
    public static volatile int ADULT_BMU_HISTORY_SIZE = 50;         // how many recent BMUs to track
    public static volatile double ADULT_STABILITY_THRESHOLD_HIGH = 0.7;  // too stable (stuck) - pull multiple
    public static volatile double ADULT_STABILITY_THRESHOLD_LOW = 0.2;   // too chaotic - slow down
    public static volatile int ADULT_PULL_COUNT_STABLE = 3;         // babies to pull when stuck
    public static volatile int ADULT_PULL_COUNT_NORMAL = 2;         // babies to pull normally
    public static volatile int ADULT_PULL_COUNT_CHAOTIC = 1;        // babies to pull when chaotic
    public static volatile int ADULT_PULL_INTERVAL_CHAOTIC_MS = 2000;  // wait time when chaotic
    public static volatile int ADULT_PULL_INTERVAL_NORMAL_MS = 500;    // wait time normally

    // === FEEDBACK (optional knobs you may already have) ===
    public static volatile double FEEDBACK_NOISE = 0.01; // noise applied during folding
    public static volatile double FEEDBACK_THRESHOLD_BASE = 0.05;
    public static volatile double FEEDBACK_THRESHOLD_RANGE = 0.15;
    public static volatile int FEEDBACK_COOLDOWN_MIN = 5;
    public static volatile double FEEDBACK_COOLDOWN_SCALE = 20.0;
    public static volatile double FEEDBACK_DEQUEUE_RATE_FLOOR = 0.1;

    // === MUSICAL OUTPUT PARAMETERS ===
    public static volatile int OUTPUT_SAMPLE_RATE = 44100;
    public static volatile int OUTPUT_BUFFER_SIZE = 4096;
    public static volatile double BASE_TEMPO = 120.0;
    public static volatile double TEMPO_RANGE = 80.0;
    public static volatile int BEATS_PER_MEASURE = 16;
    public static volatile double OUTPUT_VOLUME = 0.0;
    public static volatile double VIBRATO_DEPTH = 0.005;
    public static volatile double VIBRATO_RATE = 5.0;
    public static volatile double ATTACK_RATE = 0.995;
    public static volatile double RELEASE_RATE = 0.998;
    public static volatile int NUM_OSCILLATORS = 4;

    public static volatile double FUNDAMENTAL_MIX = 0.5;
    public static volatile double HARMONIC_2_MIX = 0.25;
    public static volatile double HARMONIC_3_MIX = 0.125;
    public static volatile double HARMONIC_4_MIX = 0.0625;

    
 // === PROCESSING / TIMING ===
    public static volatile int PROCESS_SLEEP_MS = 0;
 // === TWO 1 (GEODESIC CONVERGENCE) PARAMETERS ===
 // TWO 1 triggers when consecutive inputs map to the same BMU but are far apart in input space.
 // Divergence thresholds are mean squared distance per dimension (MSD).

 public static volatile double ADULT_TWO1_DIVERGENCE_MSD = 0.07;  // tune 0.005–0.050
 public static volatile double ADULT_TWO1_SIGMA_MULT = 0.55;        // tighten neighborhood on TWO1
 public static volatile double ADULT_TWO1_LR_MULT = 1.35;           // slightly stronger BMU pull
 public static volatile double ADULT_TWO1_CURVATURE_DECAY = 0.995;  // decay per train step

 public static volatile double BABY_TWO1_DIVERGENCE_MSD = 0.020;    // same units as above
 public static volatile double BABY_TWO1_TRACE_BOOST = 0.15;        // extra eligibility bump at winner
//=== PRE-PROCESS (Band-select before queue/baby training) ===
public static volatile boolean PRE_BAND_ENABLED = false;

//Frequency band to keep (Hz)
public static volatile double PRE_BAND_LOW_HZ = 100.0;
public static volatile double PRE_BAND_HIGH_HZ = 8000.0;

//How to treat bins outside the band:
//0 = ZERO_OUTSIDE (hard gate)
//1 = WEIGHT_OUTSIDE (soft gate, keeps some context)
public static volatile int PRE_BAND_MODE = 0;

//If WEIGHT_OUTSIDE: multiplier applied outside band (0..1)
public static volatile double PRE_BAND_OUTSIDE_GAIN = 0.10;

//Optional normalization so the effective energy scale is stable
public static volatile boolean PRE_BAND_RENORMALIZE = true;
//Preferred mic mixer (substring match). Leave blank to use default.
public static volatile String MIC_MIXER_NAME = "Device_2";
//=== PAIR-OF-DOCKS (POD) SOLVER PARAMETERS ===
//"Dock similarity" threshold: mean squared distance between two snapshots (flat vectors).
public static volatile double POD_DOCK_MSD = 0.0025; // tune: 0.0005 .. 0.02

//"Water" detection: middle entropy must exceed both ends by this delta.
public static volatile double POD_WATER_ENTROPY_DELTA = 0.10; // tune: 0.02 .. 0.30

//Require at least this many TWO1 events (or delta) in the window to treat as turbulent.
public static volatile int POD_TWO1_MIN = 6;
public static volatile boolean SYNTHETIC_INPUT = false;
public enum SynthMode { NONE, GEOMETRIC, TEMPORAL,CURVEATURE }
public static volatile SynthMode SYNTH_MODE = SynthMode.NONE;
    /** Convenience derived size (some code logs this). */
    public static int ADULT_SIZE() {
        return ADULT_W * ADULT_H;
    }

    public static void updateFromMap(Map<String, Double> params) {
        // Device selection
        if (params.containsKey("outputDeviceIndex")) {
            OUTPUT_DEVICE_INDEX = params.get("outputDeviceIndex").intValue();
        }
        if (params.containsKey("inputDeviceIndex")) {
            INPUT_DEVICE_INDEX = params.get("inputDeviceIndex").intValue();
        }
        
        // Audio input
        GAIN_MULTIPLIER = params.getOrDefault("gainMultiplier", GAIN_MULTIPLIER);
        NOISE_GATE = params.getOrDefault("noiseGate", NOISE_GATE);
        FEATURE_BINS = params.getOrDefault("featureBins", (double) FEATURE_BINS).intValue();
        INPUT_BUFFER_SIZE = params.getOrDefault("bufferSize", (double) INPUT_BUFFER_SIZE).intValue();
        MIC_SAMPLE_RATE = params.getOrDefault("micSampleRate", (double) MIC_SAMPLE_RATE).intValue();

        // Baby
        BABY_NODES = params.getOrDefault("babyNodes", (double) BABY_NODES).intValue();
        BABY_INPUT_DIM = params.getOrDefault("babyInputDim", (double) BABY_INPUT_DIM).intValue();
        BABY_LEARNING_RATE = params.getOrDefault("babyLearningRate", BABY_LEARNING_RATE);
        BABY_RADIUS = params.getOrDefault("babyRadius", BABY_RADIUS);
        BABY_SIGMA = params.getOrDefault("babySigma", BABY_SIGMA);
        BABY_EPOCHS = params.getOrDefault("babyEpochs", (double) BABY_EPOCHS).intValue();

        // Queue
        QUEUE_CAPACITY = params.getOrDefault("queueCapacity", (double) QUEUE_CAPACITY).intValue();

        // Adult 2-D
        ADULT_W = params.getOrDefault("adultW", (double) ADULT_W).intValue();
        ADULT_H = params.getOrDefault("adultH", (double) ADULT_H).intValue();
        ADULT_DIM = params.getOrDefault("adultDim", (double) ADULT_DIM).intValue();
        ADULT_LEARNING_RATE = params.getOrDefault("adultLearningRate", ADULT_LEARNING_RATE);
        ADULT_RADIUS = params.getOrDefault("adultRadius", ADULT_RADIUS);
        ADULT_SIGMA = params.getOrDefault("adultSigma", ADULT_SIGMA);

        // Adaptive queue pull
        ADULT_BMU_HISTORY_SIZE = params.getOrDefault("adultBmuHistorySize", (double) ADULT_BMU_HISTORY_SIZE).intValue();
        ADULT_STABILITY_THRESHOLD_HIGH = params.getOrDefault("adultStabilityThresholdHigh", ADULT_STABILITY_THRESHOLD_HIGH);
        ADULT_STABILITY_THRESHOLD_LOW = params.getOrDefault("adultStabilityThresholdLow", ADULT_STABILITY_THRESHOLD_LOW);
        ADULT_PULL_COUNT_STABLE = params.getOrDefault("adultPullCountStable", (double) ADULT_PULL_COUNT_STABLE).intValue();
        ADULT_PULL_COUNT_NORMAL = params.getOrDefault("adultPullCountNormal", (double) ADULT_PULL_COUNT_NORMAL).intValue();
        ADULT_PULL_COUNT_CHAOTIC = params.getOrDefault("adultPullCountChaotic", (double) ADULT_PULL_COUNT_CHAOTIC).intValue();
        ADULT_PULL_INTERVAL_CHAOTIC_MS = params.getOrDefault("adultPullIntervalChaoticMs", (double) ADULT_PULL_INTERVAL_CHAOTIC_MS).intValue();
        ADULT_PULL_INTERVAL_NORMAL_MS = params.getOrDefault("adultPullIntervalNormalMs", (double) ADULT_PULL_INTERVAL_NORMAL_MS).intValue();

        // Feedback (optional)
        FEEDBACK_NOISE = params.getOrDefault("feedbackNoise", FEEDBACK_NOISE);
        FEEDBACK_THRESHOLD_BASE = params.getOrDefault("feedbackThresholdBase", FEEDBACK_THRESHOLD_BASE);
        FEEDBACK_THRESHOLD_RANGE = params.getOrDefault("feedbackThresholdRange", FEEDBACK_THRESHOLD_RANGE);
        FEEDBACK_COOLDOWN_MIN = params.getOrDefault("feedbackCooldownMin", (double) FEEDBACK_COOLDOWN_MIN).intValue();
        FEEDBACK_COOLDOWN_SCALE = params.getOrDefault("feedbackCooldownScale", FEEDBACK_COOLDOWN_SCALE);
        FEEDBACK_DEQUEUE_RATE_FLOOR = params.getOrDefault("feedbackDequeueRateFloor", FEEDBACK_DEQUEUE_RATE_FLOOR);

        // Musical output
        OUTPUT_SAMPLE_RATE = params.getOrDefault("outputSampleRate", (double) OUTPUT_SAMPLE_RATE).intValue();
        OUTPUT_BUFFER_SIZE = params.getOrDefault("outputBufferSize", (double) OUTPUT_BUFFER_SIZE).intValue();
        BASE_TEMPO = params.getOrDefault("baseTempo", BASE_TEMPO);
        TEMPO_RANGE = params.getOrDefault("tempoRange", TEMPO_RANGE);
        BEATS_PER_MEASURE = params.getOrDefault("beatsPerMeasure", (double) BEATS_PER_MEASURE).intValue();
        OUTPUT_VOLUME = params.getOrDefault("outputVolume", OUTPUT_VOLUME);
        VIBRATO_DEPTH = params.getOrDefault("vibratoDepth", VIBRATO_DEPTH);
        VIBRATO_RATE = params.getOrDefault("vibratoRate", VIBRATO_RATE);
        ATTACK_RATE = params.getOrDefault("attackRate", ATTACK_RATE);
        RELEASE_RATE = params.getOrDefault("releaseRate", RELEASE_RATE);
        NUM_OSCILLATORS = params.getOrDefault("numOscillators", (double) NUM_OSCILLATORS).intValue();

        FUNDAMENTAL_MIX = params.getOrDefault("fundamental", FUNDAMENTAL_MIX);
        HARMONIC_2_MIX = params.getOrDefault("harmonic2", HARMONIC_2_MIX);
        HARMONIC_3_MIX = params.getOrDefault("harmonic3", HARMONIC_3_MIX);
        HARMONIC_4_MIX = params.getOrDefault("harmonic4", HARMONIC_4_MIX);
        
     // TWO1
        ADULT_TWO1_DIVERGENCE_MSD = params.getOrDefault("adultTwo1DivergenceMsd", ADULT_TWO1_DIVERGENCE_MSD);
        ADULT_TWO1_SIGMA_MULT = params.getOrDefault("adultTwo1SigmaMult", ADULT_TWO1_SIGMA_MULT);
        ADULT_TWO1_LR_MULT = params.getOrDefault("adultTwo1LrMult", ADULT_TWO1_LR_MULT);
        ADULT_TWO1_CURVATURE_DECAY = params.getOrDefault("adultTwo1CurvatureDecay", ADULT_TWO1_CURVATURE_DECAY);

        BABY_TWO1_DIVERGENCE_MSD = params.getOrDefault("babyTwo1DivergenceMsd", BABY_TWO1_DIVERGENCE_MSD);
        BABY_TWO1_TRACE_BOOST = params.getOrDefault("babyTwo1TraceBoost", BABY_TWO1_TRACE_BOOST);

    }
}