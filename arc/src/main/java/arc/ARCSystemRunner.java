package arc;

import javax.sound.sampled.*;
import javax.swing.SwingUtilities;



import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * ARCSystemRunner (FULL REWRITE: single entropy pipeline + zero-entropy witness
 * + learning-pause experiment + safe shutdown)
 *
 * Core decisions:
 * - ONE entropy mechanism only: BMU-hit histogram over the metrics window.
 * - No BmuEntropyWindow inside the audio loop (that causes N=1 forever).
 * - Entropy is computed once per metrics tick, after snapshot, then histogram resets.
 * - Entropy EMA ignores NaN.
 */
public final class ARCSystemRunner {
	private String stockSymbol = "UNKNOWN";

	private BufferedWriter vectorOut;


	// Rolling BMU-hit window (ring buffer)
	private static final int ENTROPY_RING_SIZE = 256;  // tune: 128, 256, 512...
	private int[] bmuEntropyRing = null;
	private int entropyRingPos = 0;
	private int entropyRingFill = 0;
	double curvNorm = 1e-3;  // any small positive seed is fine
	// Counts over the rolling window
	private int[] bmuEntropyCounts = null;
	private int entropyHitsRolling = 0;

	// Optional: keep last good entropy (for display)
	private double lastEntropyRolling = 0.0;
	// TWO1 Option A smoothing state (persistent across metrics windows)
	private double two1ExpEma = 0.0;
	private static final double CURV_EPS = 1e-9;
    // -----------------------------
    // Knobs
    // -----------------------------
    private static final int ENQUEUE_INTERVAL_MS = 250;
    private static final int FEEDBACK_CHECK_MS = 400;
    private static final int METRICS_REPORT_MS = 250;
    private static final int BABY_EPOCHS_PER_CHUNK = 1;

    // Curvature gating -> freeze adult learning-from-queue only (not train(features))
    private static final double CURV_MEAN_HOT = 5e-4;
    private static final double CURV_MAX_HOT  = 3e-3;
    private static final int    CURV_FREEZE_MIN_EFF_TWO1 = 8;
    private static final long   CURV_FREEZE_MS = 700;
    private static final long   CURV_FREEZE_COOLDOWN_MS = 900;

    // POD gate (dock-water-dock)
    private static final int    POD_WIN = 3;
    private static final int    POD_WINDOW_MS = 250;
    private static final int    POD_TWO1_MIN = 16;
    private static final double POD_ENTROPY_EPS = 0.01;
    private static final double POD_CURV_SPIKE_RAW = 1.0e-6;
    private static final int    POD_CURV_MIN_SPIKES = 10;

    // Entropy display smoothing
    private static final double ENTROPY_EMA_ALPHA = 0.30;

    // ROI normalization
    private static final double AMP_EMA_ALPHA = 0.12;
    private static final double AMP_NORM = 0.25;
    private static final double DEQ_RATE_NORM = 8.0;
    private static final double CURV_COVER_EPS = 1e-7;
    private static final double MEAN_ABS_CURV_NORM = 9.0e-2;

    // -----------------------------
    // ZERO-ENTROPY WITNESS + LEARNING PAUSE
    // -----------------------------
    private static final double ENTROPY_EPS = 1e-12;

    private static final int WITNESS_K_ZERO = 3;     // consecutive zero windows to arm witness
    private static final int WITNESS_P_PERTURB = 3;  // perturb windows
    private static final int WITNESS_R_RECOVER = 20; // recovery windows
    private static final double WITNESS_NOISE_AMP = 0.05; // features are 0..1

    // When entropy hits 0 => pause all learning
    private static final long ZERO_LEARNING_PAUSE_MS = 2500;
    private static final int  PAUSE_MIN_HITS = 16;

    // Entropy logging
    private static final boolean ENTROPY_LOG = true;
    private static final int ENTROPY_LOG_EVERY = 1;
    private int entropyLogCounter = 0;
    private static final int ENTROPY_ROLLING_SAMPLES = 100; // try 100–400

    // -----------------------------
    // Core state
    // -----------------------------
    private volatile boolean running = false;
    private volatile boolean shuttingDown = false;

    private Thread runnerThread;

    private final Random rng = new Random();
    private final Random witnessRng = new Random(12345);

    private BabySOM currentBaby;
    private final AdultSOM adult;
    private final AnonymizedQueue<double[]> queue;

    private TargetDataLine micLine;
    private final MusicalAdultSOMAudioOut_DeviceSelect audioOut;

    // Observability
    private final ArcBus bus = new ArcBus();
    private final EntropyVisualizer entropyViz = new EntropyVisualizer("ARC Entropy", 2400);

    // Curvature viewers
    private final CurvatureSurfaceViewer two1View = new CurvatureSurfaceViewer();
    private final CurvatureSurfaceViewer geomView = new CurvatureSurfaceViewer();

    // -----------------------------
    // Time bookkeeping
    // -----------------------------
    private long lastEnqueueMs = 0L;
    private long lastFeedbackCheckMs = 0L;
    private long lastMetricsReportMs = System.currentTimeMillis();

    // -----------------------------
    // Work counters
    // -----------------------------
    private int totalChunksProcessed = 0;
    private int totalBabyTrainSteps = 0;
    private int totalAdultLearnSteps = 0;

    // Dequeue-rate tracking (window)
    private long lastDequeueRateWindowMs = System.currentTimeMillis();
    private int dequeuesInWindow = 0;

    // ROI counters (window)
    private int enqCountWin = 0;
    private int deqCountWin = 0;

    // -----------------------------
    // Metrics state
    // -----------------------------
    private double entropyEma = 0.0;

    /**
     * lastEntropyWindow must never be NaN because POD logic uses it.
     * We update it only when we have a valid entropy sample.
     */
    private double lastEntropyWindow = 0.0;

    // Amp EMA
    private double avgAmpEma = 0.0;

    // -----------------------------
    // BMU-hit entropy distribution (window)  <-- SINGLE ENTROPY PIPELINE
    // -----------------------------
    private final int adultNodes = ARCConfig.ADULT_W * ARCConfig.ADULT_H;
    

    private double lastFiniteEntropyN = 0.0;  // or Double.NaN if you want "unknown until first"
    // but Option A usually wants a real number.
    // -----------------------------
    // BMU churn (window)
    // -----------------------------
    private int prevBMU = -1;
    private double bmuJumpSumWin = 0.0;
    private int bmuJumpCountWin = 0;

    // TMR predictor (window)
    private final int[] predNext = new int[adultNodes];
    private final int[] predCount = new int[adultNodes];
    private int lastBMUForPred = -1;
    private int temporalMismatchWin = 0;
    private int temporalTotalWin = 0;

    // Curvature stats
    private double lastMeanAbsTwo1 = 0.0;
    private double dCurvTwo1Scale = 1e-6;
    private double lastAbsDMeanCurv = 0.0;

    // Freeze state (for adult.learnFromBaby only)
    private long freezeAdultUntilMs = 0L;
    private long lastFreezeMs = 0L;

    // Grid normalization for churn
    private final double maxGridDist = Math.hypot(ARCConfig.ADULT_W - 1, ARCConfig.ADULT_H - 1);

    // -----------------------------
    // POD state
    // -----------------------------
    private final double[][] podSnaps = new double[POD_WIN][];
    private final double[] podEntropy = new double[POD_WIN];
    private final int[] podBMU = new int[POD_WIN];
    private int podCount = 0;

    private long podWindowStartMs = System.currentTimeMillis();
    private int two1InPODWindow = 0;
    private int curvSpikeCount = 0;
    private double podEntropyAtWindowStart = 0.0;
    private long lastEffectiveTwo1ForPOD = 0L;

    private double[] podPrev2 = null;
    private double[] podPrev1 = null;
    private double[] podCurr = null;

    // -----------------------------
    // Synth generators (optional)
    // -----------------------------
    private SyntheticParadoxGenerator synth = null;
    private final TemporalParadoxGenerator tempSynth = new TemporalParadoxGenerator(rng, ARCConfig.FEATURE_BINS, 2);
    private final SyntheticParadox curvSynth = new SyntheticParadox(ARCConfig.FEATURE_BINS, 12345L);

    // -----------------------------
    // CSV
    // -----------------------------
    private final Object csvLock = new Object();
    private BufferedWriter metricsOut;
    private long lastFeedbackTickMs = -1L;
    private final String metricsCsvPath = "arc_metrics_" + Instant.now().toString().replace(":", "-") + ".csv";

    // -----------------------------
    // Checkpoint autosave
    // -----------------------------
    private static final boolean CHECKPOINT_ENABLED = true;
    private static final long CHECKPOINT_EVERY_MS = 30_000;
    private static final String CHECKPOINT_DIR = "arc_checkpoints";
    private static final boolean CHECKPOINT_CLAMPED_01 = true;
    private static final String CHECKPOINT_WEIGHT_CLAMP_STR = "0..1";
    private long lastCheckpointMs = 0L;

    private final ExecutorService checkpointExec = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ARC-CheckpointWriter");
            t.setDaemon(true);
            return t;
        }
    });

    // -----------------------------
    // TWO1-aligned TMR averaging
    // -----------------------------
    private final Two1AlignedTmrPlotPanel alignedTmrPlot = new Two1AlignedTmrPlotPanel();
    private final int pre = 20;
    private final int post = 20;
    private final Two1AlignedTmrAverager avg = new Two1AlignedTmrAverager(pre, post, 30,.9);

    // -----------------------------
    // Validators / probes
    // -----------------------------
    private final Two1TmrValidatorControls two1TmrValidator =
            new Two1TmrValidatorControls(
                    600, 20, 50, 200,
                    0.0, 6,
                    null,
                    40, 40
            );

    // -----------------------------
    // Witness state
    // -----------------------------
    private int witnessConsecZero = 0;
    private boolean witnessActive = false;
    private int witnessPerturbLeft = 0;
    private int witnessRecoverLeft = 0;

    // Volatile so runLoop sees it
    private volatile boolean witnessInjectNoise = false;

    // Learning pause experiment state
    private volatile long learningPausedUntilMs = 0L;
    private boolean learningPauseActive = false;
    private int pauseUniqueBefore = -1;
    private double pauseEntropyBefore = Double.NaN;
    private long pauseStartedAtMs = 0L;

    // Last input features observed by SOM (post-perturb)
    private volatile double[] lastFeatures = null;
    private volatile FeatureStats lastFeatStats = new FeatureStats(0.0, 0.0, 0, 0);


    // -----------------------------
    // Debug structures
    // -----------------------------
    static final class EntropyDebug {
        final int windowN, K, nonZero, domIdx, domCount;
        final double domP, rawH, hNorm, kEff;
        EntropyDebug(int windowN, int K, int nonZero, int domIdx, int domCount,
                     double domP, double rawH, double hNorm, double kEff) {
            this.windowN = windowN;
            this.K = K;
            this.nonZero = nonZero;
            this.domIdx = domIdx;
            this.domCount = domCount;
            this.domP = domP;
            this.rawH = rawH;
            this.hNorm = hNorm;
            this.kEff = kEff;
        }
    }


    static EntropyDebug computeEntropyDebug(int[] hist, boolean logBase2, boolean normalizeByLogK) {
        int K = hist.length;
        int N = 0;
        int nonZero = 0;
        int domIdx = -1;
        int domCount = -1;

        for (int i = 0; i < K; i++) {
            int c = hist[i];
            if (c > 0) nonZero++;
            N += c;
            if (c > domCount) { domCount = c; domIdx = i; }
        }
        if (N <= 0) return new EntropyDebug(0, K, 0, -1, 0, 0.0, 0.0, 0.0, 0.0);

        double H = 0.0;
        for (int i = 0; i < K; i++) {
            int c = hist[i];
            if (c <= 0) continue;
            double p = (double) c / (double) N;
            double lp = logBase2 ? (Math.log(p) / Math.log(2.0)) : Math.log(p);
            H -= p * lp;
        }

        double denom = normalizeByLogK
                ? (logBase2 ? (Math.log(K) / Math.log(2.0)) : Math.log(K))
                : 1.0;

        double hNorm = (denom > 0.0) ? (H / denom) : 0.0;
        double kEff  = logBase2 ? Math.pow(2.0, H) : Math.exp(H);
        double domP  = (double) domCount / (double) N;

        return new EntropyDebug(N, K, nonZero, domIdx, domCount, domP, H, hNorm, kEff);
    }

    static void printEntropyDebug(String tag, long t, EntropyDebug d, int[] hist) {
        System.out.printf(
                "%s t=%d N=%d K=%d nonZero=%d Keff=%.3f rawH=%.6f Hnorm=%.6f dom=(%d:%d, p=%.3f)%n",
                tag, t, d.windowN, d.K, d.nonZero, d.kEff, d.rawH, d.hNorm,
                d.domIdx, d.domCount, d.domP
        );

        int shown = 0;
        for (int i = 0; i < hist.length && shown < 8; i++) {
            if (hist[i] > 0) {
                System.out.printf("  bin[%d]=%d%n", i, hist[i]);
                shown++;
            }
        }
    }
 // Rolling BMU entropy (FIX A)

    private final RollingBmuEntropy bmuEntropy = new RollingBmuEntropy(adultNodes, ENTROPY_ROLLING_SAMPLES);

 // BMU-hit entropy distribution (window)  <-- ORIGINAL STYLE
    private final int[] bmuHitCounts = new int[adultNodes];
    private int bmuHitsInWindow = 0;

    // -----------------------------
    // Constructor
    // -----------------------------
    public ARCSystemRunner() throws LineUnavailableException {
        currentBaby = new BabySOM(ARCConfig.BABY_NODES, ARCConfig.BABY_INPUT_DIM, rng);
        adult = new AdultSOM(ARCConfig.ADULT_W, ARCConfig.ADULT_H, ARCConfig.ADULT_DIM, rng);
        queue = new AnonymizedQueue<>(ARCConfig.QUEUE_CAPACITY, rng);

        synth = new SyntheticParadoxGenerator(adult, ARCConfig.FEATURE_BINS, rng);

        audioOut = createAudioOutput();
        audioOut.start();

        java.util.Arrays.fill(predNext, -1);

        openMetricsCsv();

        bus.addTelemetryObserver(new AudioObserver(audioOut));
        bus.addTelemetryObserver(new EntropyVizObserver(entropyViz));

        // G1 probe
        G1PathCoherenceProbe g1 = new G1PathCoherenceProbe(ARCConfig.ADULT_W, ARCConfig.ADULT_H, 4096, 8);
        bus.addTelemetryObserver(g1);
        bus.addEventObserver(g1);

        // Viewers
        SwingUtilities.invokeLater(() -> {
            CurvatureSurfaceViewer.showInFrame("curvTwo1 surface", two1View);
            CurvatureSurfaceViewer.showInFrame("curvGeom surface", geomView);
            Two1AlignedTmrPlotFrame.show("TWO1-aligned TMR average", alignedTmrPlot);

            two1View.setRenderMode(CurvatureSurfaceViewer.RenderMode.DELTA_ABS);
            two1View.setLogScale(true);
            two1View.setLogK(50.0);

            geomView.setRenderMode(CurvatureSurfaceViewer.RenderMode.RAW);
            geomView.setLogScale(true);
            geomView.setLogK(30.0);
            geomView.setPalette(CurvatureSurfaceViewer.Palette.DIVERGING);
        });

            openMicrophone();
      
        printConfigSummary();

        reseedBabyFromAdultAndEnqueue(true);
        try {
            vectorOut = new BufferedWriter(new FileWriter("adult_vector.csv"));
            vectorOut.write("t_ms,bmu");   // header
            for (int i = 0; i < adult.getDim(); i++) {
                vectorOut.write(",w" + i);
            }
            vectorOut.write("\n");
            vectorOut.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open adult_vector.csv", e);
        }

        System.out.println("Processing started\n");
    }
    

    
    

    // -----------------------------
    // Public API
    // -----------------------------
    public void start() {
        if (running) return;
        shuttingDown = false;
        running = true;

        runnerThread = new Thread(this::runLoop, "ARCSystemRunner");
        runnerThread.setDaemon(true);
        runnerThread.start();
    }

    public void stop() {
        shuttingDown = true;
        running = false;

        try {
            if (micLine != null) {
                micLine.stop();
                micLine.close();
            }
        } catch (Exception ignored) {}

        try {
            if (runnerThread != null) runnerThread.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try { audioOut.close(); } catch (Exception ignored) {}

        try {
            checkpointExec.shutdown();
            checkpointExec.awaitTermination(3000, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}

        synchronized (csvLock) {
            try {
                if (metricsOut != null) {
                    metricsOut.flush();
                    metricsOut.close();
                    metricsOut = null;
                }
            } catch (Exception ignored) {}
        }
        if (vectorOut != null) {
            try { vectorOut.close(); } catch (IOException ignored) {}
        }

        System.out.println("System stopped");
    }

    public boolean isRunning() { return running; }

    // -----------------------------
    // Main loop
    // -----------------------------
    private void runLoop() {
        final byte[] bytes = new byte[ARCConfig.INPUT_BUFFER_SIZE];

   

        while (running) {
            int n;
            try {
                n = micLine.read(bytes, 0, bytes.length);
            } catch (Exception e) {
                break; // mic closed during shutdown
            }
            if (!running) break;
            if (n <= 0) continue;

            final long now = System.currentTimeMillis();
            final boolean learningPausedNow = (now < learningPausedUntilMs);

            // ---- features ----
            final double[] features;
            final double avgAmp;
            final boolean gate;

            if (ARCConfig.SYNTH_MODE == ARCConfig.SynthMode.TEMPORAL) {
                features = tempSynth.next(now);
                avgAmp = 1.0;
                gate = true;
            } else if (ARCConfig.SYNTH_MODE == ARCConfig.SynthMode.CURVEATURE) {
                features = curvSynth.next(now);
                avgAmp = 1.0;
                gate = true;
            } else if (ARCConfig.SYNTH_MODE == ARCConfig.SynthMode.GEOMETRIC) {
                features = synth.next(now);
                avgAmp = 1.0;
                gate = true;
            } else {
                double[] raw = pcm16ToDoubles(bytes, n);
                features = audioToFeatures(raw, ARCConfig.FEATURE_BINS);
                avgAmp = avgAbs(raw);
                gate = avgAmp > ARCConfig.NOISE_GATE;
                if ((totalChunksProcessed % 200) == 0) {
                    System.out.printf(
                        "GATEDBG t=%d avgAmp=%.6f gate=%s%n",
                        System.currentTimeMillis(),
                        avgAmp,
                        gate ? "OPEN" : "CLOSED"
                    );
                }

            }

            // Amp EMA
            avgAmpEma = (totalChunksProcessed < 5) ? avgAmp
                    : (AMP_EMA_ALPHA * avgAmp + (1.0 - AMP_EMA_ALPHA) * avgAmpEma);

            // Witness perturb injection point
            if (witnessInjectNoise) {
                injectNoiseInPlace01(features, WITNESS_NOISE_AMP);
            }

            // record what SOM actually saw
            lastFeatures = features;
            lastFeatStats = computeFeatureStats(features);

            // Train adult unless paused
            if (!learningPausedNow) {
                adult.train(features);
            }

            // Observe BMU once
            final int currentBMU = adult.observeBMU(features);
            writeAdultVectorCsv(now, currentBMU);

            if (currentBMU < 0 || currentBMU >= adultNodes) {
                System.err.println("BUG: observeBMU returned non-grid index: " + currentBMU +
                                   " (expected 0.." + (adultNodes-1) + ")");
            } else {
                bmuHitCounts[currentBMU]++;
                bmuHitsInWindow++;
             // ✅ rolling window update (independent of metrics tick timing)
                bmuEntropy.add(currentBMU);
            }


            // fire TWO1 event if it occurred
            boolean two1Now = adult.consumeTwo1JustTriggered();
            if (two1Now) {
                bus.emitEvent(new ArcEvent(
                        now, ArcEventType.TWO1_TRIGGERED,
                        "adult.consumeTwo1JustTriggered()",
                        ArcEvent.ctx("bmu", currentBMU)
                ));
            }

            // bookkeeping
            updateTmrPrediction(currentBMU);
            updateBmuChurn(currentBMU);

            // audio gets latest input features
            audioOut.updateInput(features);

            // Train baby gated (unless paused)
            if (gate && !learningPausedNow) {
                for (int e = 0; e < BABY_EPOCHS_PER_CHUNK; e++) {
                    currentBaby.train(features);
                    totalBabyTrainSteps++;
                }
            }

            totalChunksProcessed++;

            // enqueue snapshot periodically
            if (now - lastEnqueueMs >= ENQUEUE_INTERVAL_MS) {
                lastEnqueueMs = now;
                enqueueBabySnapshotWithPodLogic(currentBMU, now);
            }

            // adult pulls from queue (unless frozen or paused)
            if (adult.shouldPullFromQueue()) {
                int pullCount = adult.howManyToPull();
                for (int i = 0; i < pullCount; i++) {
                    if (queue.isEmpty()) break;

                    double[] pulled = queue.dequeue();
                    deqCountWin++;
                    dequeuesInWindow++;

                    if (now < freezeAdultUntilMs) continue;
                    if (learningPausedNow) continue;

                    adult.learnFromBaby(pulled);
                    totalAdultLearnSteps++;
                }
            }

            // feedback check
            if (now - lastFeedbackCheckMs >= FEEDBACK_CHECK_MS) {
                lastFeedbackCheckMs = now;

                double dequeueRate = computeDequeueRatePerSec(now);
                boolean trigger = adult.shouldTriggerFeedback(queue.size(), queue.capacity(), dequeueRate);
                boolean hardOverride = queue.size() >= queue.capacity() - 1;

                if (trigger || hardOverride) {
                    entropyViz.markFeedbackEvent(now);
                    reseedBabyFromAdultAndEnqueue(false);
                    lastFeedbackTickMs = now;

                    bus.emitEvent(new ArcEvent(
                            now, ArcEventType.FEEDBACK_TRIGGERED,
                            hardOverride ? "hardOverride(queue near full)" : "adult.shouldTriggerFeedback",
                            ArcEvent.ctx("queueSize", queue.size(), "queueCap", queue.capacity(), "dequeueRate", dequeueRate)
                    ));
                }
            }

            // metrics
            if (now - lastMetricsReportMs >= METRICS_REPORT_MS) {
                long windowMs = now - lastMetricsReportMs;
                reportMetrics(now, windowMs);
                lastMetricsReportMs = now;
            }

            maybeCheckpoint(now);

            try {
                Thread.sleep(Math.max(0, ARCConfig.PROCESS_SLEEP_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -----------------------------
    // TMR predictor (window accounting)
    // -----------------------------
    private void updateTmrPrediction(int currentBMU) {
        if (currentBMU < 0) {
            lastBMUForPred = -1;
            return;
        }

        if (lastBMUForPred >= 0) {
            int predicted = predNext[lastBMUForPred];
            if (predicted >= 0) {
                temporalTotalWin++;
                if (currentBMU != predicted) temporalMismatchWin++;
            }

            if (predCount[lastBMUForPred] < 20 || rng.nextDouble() < 0.02) {
                predNext[lastBMUForPred] = currentBMU;
            }
            predCount[lastBMUForPred]++;
        }

        lastBMUForPred = currentBMU;
    }

    // -----------------------------
    // BMU churn
    // -----------------------------
    private void updateBmuChurn(int currentBMU) {
        if (prevBMU >= 0 && currentBMU >= 0) {
            int w = ARCConfig.ADULT_W;
            int x1 = prevBMU % w;
            int y1 = prevBMU / w;
            int x2 = currentBMU % w;
            int y2 = currentBMU / w;

            double dist = Math.hypot(x2 - x1, y2 - y1);
            bmuJumpSumWin += dist;
            bmuJumpCountWin++;
        }
        prevBMU = currentBMU;
    }
    private void writeAdultVectorCsv(long nowMs, int bmuFlat) {
        try {
            double[] w = adult.getWeightsFlatCopy(bmuFlat); // <-- already available in ARC

            vectorOut.write(Long.toString(nowMs));
            vectorOut.write(",");
            vectorOut.write(Integer.toString(bmuFlat));

            for (double v : w) {
                vectorOut.write(",");
                vectorOut.write(Double.toString(v));
            }
            vectorOut.write("\n");
        } catch (IOException e) {
            System.err.println("CSV write failed: " + e.getMessage());
        }
    }

    // -----------------------------
    // POD snapshot + collapse
    // -----------------------------
    private void enqueueBabySnapshotWithPodLogic(int currentBMU, long now) {
        double[] snap = currentBaby.getFullMapFlat();
        double eNow = lastEntropyWindow;

        int idx = podCount % POD_WIN;
        podSnaps[idx] = snap;
        podEntropy[idx] = eNow;
        podBMU[idx] = currentBMU;
        podCount++;

        podPrev2 = podPrev1;
        podPrev1 = podCurr;
        podCurr = snap;

        if (podCount < POD_WIN) {
            queue.enqueue(snap);
            enqCountWin++;
            return;
        }

        int i0 = (podCount - 3) % POD_WIN;
        int i1 = (podCount - 2) % POD_WIN;
        int i2 = (podCount - 1) % POD_WIN;

        double[] s0 = podSnaps[i0];
        double[] s1 = podSnaps[i1];
        double[] s2 = podSnaps[i2];

        double e0 = podEntropy[i0], e1 = podEntropy[i1], e2 = podEntropy[i2];
        int b0 = podBMU[i0], b2 = podBMU[i2];

        boolean docksSimilar = (b0 == b2) || (msd(s0, s2) < ARCConfig.POD_DOCK_MSD);
        boolean middleIsWater = (e1 > e0 + ARCConfig.POD_WATER_ENTROPY_DELTA)
                && (e1 > e2 + ARCConfig.POD_WATER_ENTROPY_DELTA);

        long effTotalNow = adult.getTwo1EffectiveTotal();
        boolean two1HotQuick = (effTotalNow - lastEffectiveTwo1ForPOD) >= POD_TWO1_MIN;

        if (docksSimilar && middleIsWater && two1HotQuick) {
            double[] collapsed = blend(s0, s2, 0.5);
            queue.enqueue(collapsed);
            enqCountWin++;

            bus.emitEvent(new ArcEvent(
                    now, ArcEventType.POD_COLLAPSE,
                    "dock-water-dock + TWO1 hot",
                    ArcEvent.ctx("b0", b0, "b2", b2, "msdDock", msd(s0, s2))
            ));
        } else {
            queue.enqueue(snap);
            enqCountWin++;
        }
    }

    private boolean shouldCollapsePOD(double currentEntropyWindow, long effTwo1TotalNow, long nowMs) {
        if (nowMs - podWindowStartMs > POD_WINDOW_MS) {
            podWindowStartMs = nowMs;
            two1InPODWindow = 0;
            curvSpikeCount = 0;
            podEntropyAtWindowStart = currentEntropyWindow;
            lastEffectiveTwo1ForPOD = effTwo1TotalNow;
            return false;
        }

        if (lastAbsDMeanCurv > POD_CURV_SPIKE_RAW) curvSpikeCount++;

        long delta = effTwo1TotalNow - lastEffectiveTwo1ForPOD;
        if (delta > 0) {
            two1InPODWindow += (int) Math.min(delta, 8);
            lastEffectiveTwo1ForPOD = effTwo1TotalNow;
        }

        double dH = Math.abs(currentEntropyWindow - podEntropyAtWindowStart);
        boolean entropyFlat = dH <= POD_ENTROPY_EPS;

        boolean two1Hot = two1InPODWindow >= POD_TWO1_MIN;
        boolean curvHot = curvSpikeCount >= POD_CURV_MIN_SPIKES;

        return entropyFlat && (two1Hot || curvHot);
    }

    // -----------------------------
    // Metrics + freeze + telemetry
    // -----------------------------
    private void reportMetrics(long nowMs, long windowMs) {
        if (!running || shuttingDown) return;


        final long wm = Math.max(1L, windowMs);
        final double windowSec = wm / 1000.0;

        // Safe snapshot
        final FeatureStats fs = (lastFeatStats != null) ? lastFeatStats : new FeatureStats(0.0, 0.0, 0, 0);

        // ----------------------------
        // TMR (and reset)
        // ----------------------------
        final int tTot = temporalTotalWin;
        final int tMis = temporalMismatchWin;
        temporalTotalWin = 0;
        temporalMismatchWin = 0;

        final double tmrRaw = (tTot <= 0) ? 0.0 : 1-(tMis / (double) tTot);
        final double tmr01 = clamp01(tmrRaw);
     // ---- TMR DEBUG ----
        boolean tmrNoData = (tTot <= 0);

        System.out.printf(
            "TMRDBG t=%d rawTmr=%.6f tmr01=%.6f tTot=%d tMis=%d noData=%s%n",
            nowMs,
            tmrRaw,
            tmr01,
            tTot,
            tMis,
            tmrNoData ? "Y" : "N"
        );

        // ROI amp
        final double amp01 = clamp01(avgAmpEma / AMP_NORM);

        // ----------------------------
        // Snapshot BMU hits BEFORE reset
        // ----------------------------
     // Snapshot BMU hits BEFORE reset (for witness/pause)
        final BmuHitWitness hw = snapshotBmuHits(); // uses bmuEntropy.hist + bmuEntropy.size


        // Entropy RAW(window) + EMA(display)  <-- ORIGINAL STYLE
        final double entropyRaw = calculateBmuHitEntropyRolling();
        lastEntropyWindow = entropyRaw;



        final double entropyDisplay = entropyRaw;
        final boolean isZeroEntropy = (entropyRaw <= ENTROPY_EPS);


        // BMU margin snapshot
        final BmuMargin bm = computeBmuMarginSnapshot();

        // Learning pause + witness
        try { maybeStartOrEvaluateLearningPause(nowMs, isZeroEntropy, hw, entropyRaw, fs, bm); }
        catch (Throwable t) { System.err.println("learning pause logic failed: " + t.getMessage()); }

        try { updateZeroEntropyWitness(nowMs, isZeroEntropy, hw, amp01, entropyRaw, fs, bm); }
        catch (Throwable t) { System.err.println("witness logic failed: " + t.getMessage()); }

        // BMU stability
        final double bmuStability = adult.getBMUStability();

        // Churn (and reset)
        boolean churnNoData = (bmuJumpCountWin <= 0) || (bmuJumpSumWin <= 0.0);


        double rawChurn = 0.0;
        if (!churnNoData) {
            rawChurn = bmuJumpSumWin / (bmuJumpCountWin * Math.max(1e-12, maxGridDist));
        }

        double churn01 = clamp01(rawChurn);

        // ---- CHURN DEBUG ----
        System.out.printf(
            "CHURNDBG t=%d rawChurn=%.6f churn01=%.6f jumpCount=%d noData=%s%n",
            nowMs,
            rawChurn,
            churn01,
            bmuJumpCountWin,
            churnNoData ? "Y" : "N"
        );

        // reset window
        bmuJumpSumWin = 0.0;
        bmuJumpCountWin = 0;


        // Curvature (two1)
        final double[] curvTwo1 = adult.getCurvTwo1FlatCopy();
        double sumAbs = 0.0;
        double maxAbs = 0.0;
        int active = 0;

        for (double v : curvTwo1) {
            final double a = Math.abs(v);
            sumAbs += a;
            if (a > maxAbs) maxAbs = a;
            if (a > CURV_COVER_EPS) active++;
        }

        final double meanAbs = sumAbs / Math.max(1, curvTwo1.length);
       
        final double CURV_ALPHA = 0.01;
        final double EPS = 1e-9;

        // persistent state (field), initialized once
        

        // ...
        final double meanAbsCurv = meanAbs;   // whatever your computed meanAbs is


        curvNorm = ema(curvNorm, meanAbsCurv, CURV_ALPHA);
        curvNorm = Math.max(curvNorm, CURV_EPS);

        final double ratio = meanAbsCurv / (curvNorm + CURV_EPS);
        final double curv01 = ratio / (1.0 + ratio);

        System.out.printf(
            "meanAbsCurv=%.6f curvNorm=%.6f ratioRaw=%.6f curv01=%.6f%n",
            meanAbsCurv, curvNorm, ratio, curv01
        );

        
        

        final double dMeanAbs = meanAbs - lastMeanAbsTwo1;
        lastMeanAbsTwo1 = meanAbs;
        lastAbsDMeanCurv = Math.abs(dMeanAbs);

        dCurvTwo1Scale = 0.98 * dCurvTwo1Scale + 0.02 * lastAbsDMeanCurv;
        final double dCurv01 = clamp01(lastAbsDMeanCurv / (dCurvTwo1Scale + 1e-12));

        final double curvCoverage01 = clamp01(active / (double) Math.max(1, curvTwo1.length));

        double curvEntropy01 = 0.0;
        if (sumAbs > 0.0) {
            double H = 0.0;
            for (double v : curvTwo1) {
                final double a = Math.abs(v);
                if (a <= 0.0) continue;
                final double p = a / sumAbs;
                H -= p * Math.log(p);
            }
            curvEntropy01 = clamp01(H / Math.log(Math.max(2, curvTwo1.length)));
        }

        // Curvature (geom) + viewers
        final double[] curvGeom = adult.getCurvGeomFlatCopy();
        final int W = adult.getW();
        final int H = adult.getH();

        SwingUtilities.invokeLater(() -> {
            try {
                two1View.setSurfaceFromFlat(curvTwo1, H, W);
                geomView.setSurfaceFromFlat(curvGeom, H, W);
            } catch (Throwable t) {
                System.err.println("viewer update failed: " + t.getMessage());
            }
        });

        // TWO1 rates (and reset windows)
        final int two1RawWindow = adult.consumeTwo1CountWindow();
        final int two1EffWindow = adult.consumeTwo1EffectiveCountWindow();
        final long two1EffTotal = adult.getTwo1EffectiveTotal();
        // ---- TWO1 Option A: exponential squash + EMA ----
        final double TWO1_K = 8.0;        // tune: 6–12 typical
        final double TWO1_ALPHA = 0.20;   // smoothing: 0.1–0.3 typical
        final double two1RawRate = two1RawWindow / windowSec;
        final double two1EffRate = two1EffWindow / windowSec;

        final double two1Instant01 = 1.0 - Math.exp(-two1RawRate / TWO1_K);
        two1ExpEma = TWO1_ALPHA * two1Instant01 + (1.0 - TWO1_ALPHA) * two1ExpEma;
        final double two1Exp01 = clamp01(two1ExpEma);

        // if you still need these:
        final double two1RawRate01 = two1Instant01;
        final double two1EffRate01 = clamp01(two1EffRate / 0.5);
       // or change the 0.5 norm

        
     // ---- TWO1 DEBUG ----
     System.out.printf(
         "TWO1DBG t=%d rawEvents=%d effEvents=%d windowSec=%.3f rawRate=%.6f effRate=%.6f exp01=%.6f%n",
         nowMs, two1RawWindow, two1EffWindow, windowSec, two1RawRate, two1EffRate, two1Exp01
     );


        // ROI dequeue rate (and reset)
        final double deqRate = deqCountWin / windowSec;
        deqCountWin = 0;
        final double deqRate01 = clamp01(deqRate / DEQ_RATE_NORM);
     // ✅ end-of-window reset for witness-only histogram
        java.util.Arrays.fill(bmuHitCounts, 0);
        bmuHitsInWindow = 0;

        // Feedback tick
        final int tick = (lastFeedbackTickMs >= 0 && (nowMs - lastFeedbackTickMs) <= wm) ? 1 : 0;

        // Freeze decision (learnFromBaby only)
        final boolean curvHot = (meanAbs > CURV_MEAN_HOT) || (maxAbs > CURV_MAX_HOT);
        final boolean effTwo1Present = two1EffWindow >= CURV_FREEZE_MIN_EFF_TWO1;

        if (curvHot && effTwo1Present) {
            if ((nowMs - lastFreezeMs) > CURV_FREEZE_COOLDOWN_MS) {
                freezeAdultUntilMs = Math.max(freezeAdultUntilMs, nowMs + CURV_FREEZE_MS);
                lastFreezeMs = nowMs;

                bus.emitEvent(new ArcEvent(
                        nowMs,
                        ArcEventType.FREEZE_STARTED,
                        "curvHot && effTwo1Present",
                        ArcEvent.ctx("meanAbsCurv", meanAbs, "maxAbsCurv", maxAbs, "two1EffWindow", two1EffWindow)
                ));
            }
        }

        // POD gate
        final double podEntropySample = lastEntropyWindow;

        if (shouldCollapsePOD(podEntropySample, two1EffTotal, nowMs)) {
            final double[] collapsed = collapseDockPair(podPrev2, podCurr);
            if (collapsed != null) {
                queue.enqueue(collapsed);
                enqCountWin++;

                bus.emitEvent(new ArcEvent(
                        nowMs,
                        ArcEventType.POD_COLLAPSE,
                        "POD gate (entropy flat && (two1Hot || curvHot))",
                        ArcEvent.ctx("entropyRaw", podEntropySample, "two1EffTotal", (double) two1EffTotal)
                ));
            }
        }

        // Validator
        two1TmrValidator.addWindow(new Two1TmrValidatorControls.Window(
        	    nowMs, windowMs,
        	    tmr01,
        	    two1Exp01,      // ✅ use smooth TWO1
        	    entropyRaw,
        	    churn01,
        	    dCurv01,
        	    curv01
        	));

        two1TmrValidator.maybeReport();

        // Telemetry emit
        final ArcTelemetry telem = new ArcTelemetry(
        	    nowMs, wm,
        	    entropyDisplay,
        	    bmuStability,
        	    churn01,
        	    dCurv01, curv01,
        	    two1Exp01,     // ✅ was two1EffRate01
        	    tmr01,
        	    tick,

        	    amp01,
        	    deqRate01,
        	    curvCoverage01,
        	    curvEntropy01,
        	    two1RawRate01,     // ✅ was two1RawRate01

        	    ratio,
        	    meanAbs,
        	    dMeanAbs,

        	    two1EffRate,
        	    two1RawRate,
        	    tmrRaw,

        	    queue.size(),
        	    queue.capacity(),
        	    prevBMU
        	);

        bus.emitTelemetry(telem);

        // CSV
        synchronized (csvLock) {
            if (!shuttingDown && metricsOut != null) {
                try {
                    metricsOut.write(
                    		nowMs + "," +
                    				entropyDisplay + "," +
                    				churn01 + "," +
                    				dCurv01 + "," +
                    				ratio + "," +
                    				two1EffRate01 + "," +
                    				tmr01 + "," +
                    				tick + "," +
                    				amp01 + "," +
                    				deqRate01 + "," +
                    				curvCoverage01 + "," +
                    				curvEntropy01 + "," +
                    				two1RawRate01 + "," +
                    				two1Exp01 + "," +          // ✅ new
                    				bmuStability + "," +
                    				queue.size() + "," +
                    				queue.capacity() + "\n"
                    );
                    metricsOut.flush();
                } catch (IOException e) {
                    System.err.println("CSV write failed: " + e.getMessage());
                }
            }
        }

        // Aligned plot
        final Two1AlignedTmrAverager.AlignedAverageResult ar =
                avg.computeFrom(two1TmrValidator.getWindowsSnapshot());

        if (alignedTmrPlot != null && ar.count > 0) {
            final int zeroIndex = pre;
            SwingUtilities.invokeLater(() -> {
                try { alignedTmrPlot.update(ar, zeroIndex); }
                catch (Throwable t) { System.err.println("aligned plot update failed: " + t.getMessage()); }
            });
        }

        // End-of-window resets
        enqCountWin = 0;
    }
    private BmuHitWitness snapshotBmuHits() {
        RollingBmuEntropy re = bmuEntropy;

        int hits = re.size;
        int unique = 0;
        int maxC = 0;
        int sum = hits;

        for (int i = 0; i < re.hist.length; i++) {
            int c = re.hist[i];
            if (c > 0) {
                unique++;
                if (c > maxC) maxC = c;
            }
        }

        return new BmuHitWitness(hits, unique, maxC, sum);
    }

    // -----------------------------
    // Feedback fold
    // -----------------------------
    private void reseedBabyFromAdultAndEnqueue(boolean startup) {
        long now = System.currentTimeMillis();
        entropyViz.markFeedbackEvent(now);
        lastFeedbackTickMs = now;

        double[] feedbackFlat = adult.createFeedbackBaby();
        BabySOM feedbackBaby = new BabySOM(ARCConfig.BABY_NODES, ARCConfig.BABY_INPUT_DIM, rng);
        feedbackBaby.initializeFromFlat(feedbackFlat);
        currentBaby = feedbackBaby;

        double[] snap = currentBaby.getFullMapFlat();

        podPrev2 = podPrev1;
        podPrev1 = podCurr;
        podCurr = snap;

        long effTwo1Now = adult.getTwo1EffectiveTotal();
        double eNow = lastEntropyWindow;

        if (shouldCollapsePOD(eNow, effTwo1Now, now)) {
            double[] collapsed = collapseDockPair(podPrev2, podCurr);
            if (collapsed != null) {
                queue.enqueue(collapsed);
                enqCountWin++;
            }
        } else {
            queue.enqueue(snap);
            enqCountWin++;
        }
    }

    // -----------------------------
    // Audio / Mic
    // -----------------------------
    private MusicalAdultSOMAudioOut_DeviceSelect createAudioOutput() throws LineUnavailableException {
        int deviceIndex = ARCConfig.OUTPUT_DEVICE_INDEX;
        System.out.println("Creating audio output, deviceIndex=" + deviceIndex);
        return new MusicalAdultSOMAudioOut_DeviceSelect(adult, deviceIndex);
    }

    private void openMicrophone() {
        try {
            AudioFormat format = new AudioFormat(ARCConfig.MIC_SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            int deviceIndex = ARCConfig.INPUT_DEVICE_INDEX;
            if (deviceIndex == -1) {
                micLine = (TargetDataLine) AudioSystem.getLine(info);
            } else {
                Mixer.Info[] mixers = AudioSystem.getMixerInfo();
                if (deviceIndex >= mixers.length || deviceIndex < 0) {
                    throw new RuntimeException("Invalid input device index: " + deviceIndex);
                }
                Mixer mixer = AudioSystem.getMixer(mixers[deviceIndex]);
                if (!mixer.isLineSupported(info)) {
                    throw new RuntimeException("Device " + deviceIndex + " does not support audio input");
                }
                micLine = (TargetDataLine) mixer.getLine(info);
            }

            micLine.open(format, ARCConfig.INPUT_BUFFER_SIZE);
            micLine.start();
            System.out.println("Microphone opened successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to open microphone: " + e.getMessage(), e);
        }
    }

    // -----------------------------
    // CSV
    // -----------------------------
    private void openMetricsCsv() {
        synchronized (csvLock) {
            try {
                metricsOut = new BufferedWriter(new FileWriter(metricsCsvPath, true));
                if (new File(metricsCsvPath).length() == 0) {
                	metricsOut.write(
                			  "t_ms,entropy_ema,churn01,dCurv01,meanAbsCurv01," +
                			  "two1_eff_rate01,tmr01,feedback_tick," +
                			  "roi_amp01,roi_deqRate01,roi_curvCover01,roi_curvEntropy01,roi_two1RawRate01," +
                			  "two1_exp01," +                 // ✅ ADD THIS
                			  "bmu_stability,queue_size,queue_cap\n"
                			);

                    metricsOut.flush();
                }
                System.out.println("CSV logging to: " + metricsCsvPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to open metrics CSV: " + e.getMessage(), e);
            }
        }
    }

    // -----------------------------
    // Utilities
    // -----------------------------
    private double computeDequeueRatePerSec(long nowMs) {
        long dt = nowMs - lastDequeueRateWindowMs;
        if (dt < 250) return Math.max(ARCConfig.FEEDBACK_DEQUEUE_RATE_FLOOR, 0.0);

        double rate = (dequeuesInWindow * 1000.0) / Math.max(1.0, dt);
        lastDequeueRateWindowMs = nowMs;
        dequeuesInWindow = 0;
        return Math.max(ARCConfig.FEEDBACK_DEQUEUE_RATE_FLOOR, rate);
    }



    private static double[] pcm16ToDoubles(byte[] buf, int bytesRead) {
        int samples = bytesRead / 2;
        double[] out = new double[samples];

        for (int i = 0; i < samples; i++) {
            int lo = buf[2 * i] & 0xFF;
            int hi = buf[2 * i + 1];
            short s = (short) ((hi << 8) | lo);
            out[i] = s / 32768.0;
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
            for (int j = s; j < e; j++) sum += Math.abs(raw[j]);
            features[i] = sum / Math.max(1, (e - s));
        }

        double max = 1e-12;
        for (double v : features) if (v > max) max = v;
        for (int i = 0; i < features.length; i++) features[i] = Math.min(1.0, features[i] / max);

        return features;
    }

    private static double avgAbs(double[] x) {
        double s = 0.0;
        for (double v : x) s += Math.abs(v);
        return s / Math.max(1, x.length);
    }

    private static double msd(double[] a, double[] b) {
        if (a == null || b == null) return Double.POSITIVE_INFINITY;
        int n = Math.min(a.length, b.length);
        if (n <= 0) return Double.POSITIVE_INFINITY;

        double s = 0.0;
        for (int i = 0; i < n; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return s / n;
    }

    private static double[] blend(double[] a, double[] b, double alpha) {
        if (a == null || b == null) return null;
        double[] out = new double[Math.min(a.length, b.length)];
        double t = clamp01(alpha);
        for (int i = 0; i < out.length; i++) out[i] = (1.0 - t) * a[i] + t * b[i];
        return out;
    }

    private static double[] collapseDockPair(double[] dockA, double[] dockB) {
        if (dockA == null || dockB == null) return null;
        int n = Math.min(dockA.length, dockB.length);
        double[] out = new double[n];

        for (int i = 0; i < n; i++) {
            double v = 0.5 * (dockA[i] + dockB[i]);
            if (v < 0.0) v = 0.0;
            else if (v > 1.0) v = 1.0;
            out[i] = v;
        }
        return out;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private void printConfigSummary() {
        System.out.println("Config Summary:");
        System.out.println("  Audio Output Device: " + ARCConfig.OUTPUT_DEVICE_INDEX);
        System.out.println("  Mic Input Device: " + ARCConfig.INPUT_DEVICE_INDEX);
        System.out.println("  Queue Capacity: " + ARCConfig.QUEUE_CAPACITY);
        System.out.println("  BabySOM: " + ARCConfig.BABY_NODES + " nodes x " + ARCConfig.BABY_INPUT_DIM + " dims");
        System.out.println("  AdultSOM: " + (ARCConfig.ADULT_W * ARCConfig.ADULT_H) + " nodes x " + ARCConfig.ADULT_DIM + " dims");
        System.out.println("  Tempo: " + ARCConfig.BASE_TEMPO + " +/- " + ARCConfig.TEMPO_RANGE + " BPM");
        System.out.println();
    }

    // -----------------------------
    // Checkpoint
    // -----------------------------
    private void maybeCheckpoint(long nowMs) {
        if (!CHECKPOINT_ENABLED) return;
        if (shuttingDown) return;

        if (lastCheckpointMs == 0L) lastCheckpointMs = nowMs;
        if (nowMs - lastCheckpointMs < CHECKPOINT_EVERY_MS) return;
        lastCheckpointMs = nowMs;

        final long createdAtMs = nowMs;
        final int w = adult.getW();
        final int h = adult.getH();
        final int dim = adult.getDim();
        final double[][][] W3 = adult.getWeightsCopy();

        checkpointExec.submit(() -> {
            try {
                File dir = new File(CHECKPOINT_DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    System.err.println("Checkpoint mkdir failed: " + dir.getAbsolutePath());
                    return;
                }

                String base = "adult_" + createdAtMs;
                File jsonFile = new File(dir, base + ".json");
                File binFile = new File(dir, base + ".bin");

                AdultSOMCheckpointIO.writeBinary(W3, w, h, dim, binFile, createdAtMs, CHECKPOINT_CLAMPED_01);
                AdultSOMCheckpointIO.writeJsonManifest(jsonFile, createdAtMs, w, h, dim,
                        "row-major", "little", CHECKPOINT_WEIGHT_CLAMP_STR, binFile.getName());

                System.out.println("Checkpoint saved: " + jsonFile.getPath() + " + " + binFile.getPath());
            } catch (Exception e) {
                System.err.println("Checkpoint write failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    // -----------------------------
    // Witness helpers
    // -----------------------------
    private static final class BmuHitWitness {
        final int hits, unique, maxCount, sumCounts;
        BmuHitWitness(int hits, int unique, int maxCount, int sumCounts) {
            this.hits = hits;
            this.unique = unique;
            this.maxCount = maxCount;
            this.sumCounts = sumCounts;
        }
        double dominantFrac() { return hits <= 0 ? 0.0 : (maxCount / (double) hits); }
    }

    private static final class FeatureStats {
        final double rms, std;
        final int nanCount, n;
        FeatureStats(double rms, double std, int nanCount, int n) {
            this.rms = rms;
            this.std = std;
            this.nanCount = nanCount;
            this.n = n;
        }
    }

    private static final class BmuMargin {
        final int bmu, second;
        final double d1, d2, margin;
        BmuMargin(int bmu, int second, double d1, double d2) {
            this.bmu = bmu;
            this.second = second;
            this.d1 = d1;
            this.d2 = d2;
            this.margin = d2 - d1;
        }
        static BmuMargin none() { return new BmuMargin(-1, -1, Double.NaN, Double.NaN); }
    }



    private static FeatureStats computeFeatureStats(double[] v) {
        if (v == null || v.length == 0) return new FeatureStats(0.0, 0.0, 0, 0);

        int nan = 0;
        double sum = 0.0;
        double sumSq = 0.0;

        for (double x : v) {
            if (Double.isNaN(x) || Double.isInfinite(x)) {
                nan++;
                continue;
            }
            sum += x;
            sumSq += x * x;
        }

        int n = v.length;
        int m = Math.max(1, n - nan);

        double mean = sum / m;
        double var = (sumSq / m) - (mean * mean);
        if (var < 0.0) var = 0.0;

        double rms = Math.sqrt(sumSq / m);
        double std = Math.sqrt(var);

        return new FeatureStats(rms, std, nan, n);
    }

    private BmuMargin computeBmuMarginSnapshot() {
        double[] x = lastFeatures;
        if (x == null) return BmuMargin.none();

        double[][][] W = adult.getWeightsCopy();
        int w = adult.getW();
        int h = adult.getH();
        int dim = adult.getDim();

        if (dim <= 0 || x.length < dim) return BmuMargin.none();

        double best = Double.POSITIVE_INFINITY;
        double second = Double.POSITIVE_INFINITY;
        int bestIdx = -1;
        int secondIdx = -1;

        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                double s = 0.0;
                double[] ww = W[yy][xx];
                for (int i = 0; i < dim; i++) {
                    double d = x[i] - ww[i];
                    s += d * d;
                }
                double dist = Math.sqrt(s);

                int idx = yy * w + xx;
                if (dist < best) {
                    second = best;
                    secondIdx = bestIdx;
                    best = dist;
                    bestIdx = idx;
                } else if (dist < second) {
                    second = dist;
                    secondIdx = idx;
                }
            }
        }

        if (!Double.isFinite(best) || !Double.isFinite(second)) return BmuMargin.none();
        return new BmuMargin(bestIdx, secondIdx, best, second);
    }

    private void maybeStartOrEvaluateLearningPause(
            long nowMs, boolean isZeroEntropy, BmuHitWitness hw,
            double entropyRaw, FeatureStats fs, BmuMargin margin
    ) {
        if (learningPauseActive && nowMs >= learningPausedUntilMs) {
            learningPauseActive = false;

            boolean evidenceOk = hw.hits >= PAUSE_MIN_HITS;
            boolean diversityReturned = evidenceOk && (hw.unique > Math.max(1, pauseUniqueBefore));

            System.out.printf(
                    "LEARN_PAUSE_END t=%d durMs=%d evidenceOk=%s RESULT=%s " +
                            "uniqueBefore=%d uniqueAfter=%d entBefore=%.6g entAfter=%.6g " +
                            "feat(rms=%.6g std=%.6g nan=%d/%d) " +
                            "margin(bmu=%d d1=%.6g 2nd=%d d2=%.6g m=%.6g)%n",
                    nowMs, (nowMs - pauseStartedAtMs), evidenceOk ? "Y" : "N",
                    diversityReturned ? "OVER_REINFORCING" : "PIPELINE_COLLAPSED",
                    pauseUniqueBefore, hw.unique,
                    pauseEntropyBefore, entropyRaw,
                    fs.rms, fs.std, fs.nanCount, fs.n,
                    margin.bmu, margin.d1, margin.second, margin.d2, margin.margin
            );
            return;
        }

        if (!learningPauseActive && isZeroEntropy && hw.hits >= PAUSE_MIN_HITS) {
            learningPauseActive = true;
            pauseStartedAtMs = nowMs;
            learningPausedUntilMs = nowMs + ZERO_LEARNING_PAUSE_MS;

            pauseUniqueBefore = hw.unique;
            pauseEntropyBefore = entropyRaw;

            System.out.printf(
                    "LEARN_PAUSE_START t=%d durMs=%d hits=%d unique=%d ent=%.6g " +
                            "feat(rms=%.6g std=%.6g nan=%d/%d) " +
                            "margin(bmu=%d d1=%.6g 2nd=%d d2=%.6g m=%.6g)%n",
                    nowMs, ZERO_LEARNING_PAUSE_MS,
                    hw.hits, hw.unique, entropyRaw,
                    fs.rms, fs.std, fs.nanCount, fs.n,
                    margin.bmu, margin.d1, margin.second, margin.d2, margin.margin
            );
        }
    }

    private void updateZeroEntropyWitness(
            long nowMs, boolean isZeroEntropy, BmuHitWitness hw, double amp01,
            double entropyRaw, FeatureStats fs, BmuMargin margin
    ) {
        if (hw.hits > 0 && isZeroEntropy) witnessConsecZero++;
        else witnessConsecZero = 0;

        if (!witnessActive && witnessConsecZero >= WITNESS_K_ZERO) {
            witnessActive = true;
            witnessPerturbLeft = WITNESS_P_PERTURB;
            witnessRecoverLeft = WITNESS_R_RECOVER;
            witnessInjectNoise = true;

            System.out.printf(
                    "WITNESS_START t=%d hits=%d unique=%d dom=%.3f amp01=%.3f ent=%.6g " +
                            "feat(rms=%.6g std=%.6g nan=%d/%d) " +
                            "margin(bmu=%d d1=%.6g 2nd=%d d2=%.6g m=%.6g)%n",
                    nowMs, hw.hits, hw.unique, hw.dominantFrac(), amp01, entropyRaw,
                    fs.rms, fs.std, fs.nanCount, fs.n,
                    margin.bmu, margin.d1, margin.second, margin.d2, margin.margin
            );
            return;
        }

        if (!witnessActive) return;

        if (witnessPerturbLeft > 0) {
            System.out.printf(
                    "PERTURB_OBS t=%d hits=%d unique=%d dom=%.3f amp01=%.3f ent=%.6g " +
                            "feat(rms=%.6g std=%.6g nan=%d/%d) " +
                            "margin(bmu=%d d1=%.6g 2nd=%d d2=%.6g m=%.6g)%n",
                    nowMs, hw.hits, hw.unique, hw.dominantFrac(), amp01, entropyRaw,
                    fs.rms, fs.std, fs.nanCount, fs.n,
                    margin.bmu, margin.d1, margin.second, margin.d2, margin.margin
            );

            witnessPerturbLeft--;
            if (witnessPerturbLeft == 0) {
                witnessInjectNoise = false;
                System.out.printf("WITNESS_PERTURB_END t=%d%n", nowMs);
            }
            return;
        }

        if (witnessRecoverLeft > 0) {
            if (hw.hits > 0 && hw.unique == 1 && isZeroEntropy && amp01 > 0.02) {
                System.out.printf(
                        "ZERO_ENTROPY_WITNESSED t=%d recovered-to-zero AFTER perturb " +
                                "hits=%d dom=%.3f amp01=%.3f ent=%.6g " +
                                "feat(rms=%.6g std=%.6g nan=%d/%d) " +
                                "margin(bmu=%d d1=%.6g 2nd=%d d2=%.6g m=%.6g)%n",
                        nowMs, hw.hits, hw.dominantFrac(), amp01, entropyRaw,
                        fs.rms, fs.std, fs.nanCount, fs.n,
                        margin.bmu, margin.d1, margin.second, margin.d2, margin.margin
                );

                witnessActive = false;
                witnessInjectNoise = false;
                witnessConsecZero = 0;
                return;
            }

            System.out.printf(
                    "RECOVERY_OBS t=%d hits=%d unique=%d dom=%.3f amp01=%.3f ent=%.6g " +
                            "feat(rms=%.6g std=%.6g nan=%d/%d) " +
                            "margin(bmu=%d d1=%.6g 2nd=%d d2=%.6g m=%.6g)%n",
                    nowMs, hw.hits, hw.unique, hw.dominantFrac(), amp01, entropyRaw,
                    fs.rms, fs.std, fs.nanCount, fs.n,
                    margin.bmu, margin.d1, margin.second, margin.d2, margin.margin
            );

            witnessRecoverLeft--;
            if (witnessRecoverLeft == 0) {
                System.out.printf("WITNESS_END_NO_RECOVERY t=%d did not return to zero%n", nowMs);
                witnessActive = false;
                witnessInjectNoise = false;
                witnessConsecZero = 0;
            }
        }
    }

    private void injectNoiseInPlace01(double[] v, double amp) {
        if (v == null) return;
        for (int i = 0; i < v.length; i++) {
            double x = v[i] + amp * witnessRng.nextGaussian();
            if (x < 0.0) x = 0.0;
            else if (x > 1.0) x = 1.0;
            v[i] = x;
        }
    }
    
    private static final class RollingBmuEntropy {
        private final int K;
        private final int window;
        private final int[] hist;
        private final int[] ring;
        private int head = 0;
        private int size = 0;

        RollingBmuEntropy(int K, int window) {
            this.K = K;
            this.window = window;
            this.hist = new int[K];
            this.ring = new int[window];
        }

        void add(int bmu) {
            if (bmu < 0 || bmu >= K) return;

            if (size == window) {
                int old = ring[head];
                hist[old]--;
            } else {
                size++;
            }

            ring[head] = bmu;
            hist[bmu]++;
            head = (head + 1) % window;
        }

        double entropyLocal() {
            if (size < 2) return Double.NaN;

            double H = 0.0;
            int nonZero = 0;

            for (int c : hist) {
                if (c <= 0) continue;
                nonZero++;
                double p = c / (double) size;
                H -= p * Math.log(p);
            }

            // TRUE zero entropy when all mass in one BMU
            if (nonZero <= 1) return 0.0;

            // ✅ normalize by log(K), not log(nonZero)
            return H / Math.log(K);
        }


        EntropyDebug debug() {
            int nonZero = 0, domIdx = -1, domCount = -1;
            for (int i = 0; i < K; i++) {
                int c = hist[i];
                if (c > 0) nonZero++;
                if (c > domCount) { domCount = c; domIdx = i; }
            }
            double H = 0.0;
            for (int c : hist) {
                if (c <= 0) continue;
                double p = c / (double) size;
                H -= p * Math.log(p);
            }
            return new EntropyDebug(
                size, K, nonZero, domIdx, domCount,
                domCount <= 0 ? 0.0 : domCount / (double) size,
                H,
                nonZero >= 2 ? H / Math.log(K) : 0.0
,
                Math.exp(H)
            );
        }
    }
 // Call once per window/tick (same cadence as your other metric rings)




    private void recordEntropyEachWindow(long nowMs, double entropyN) {
        double v = entropyN;

        // Carry forward last finite value
        if (!Double.isFinite(v)) {
            v = Double.isFinite(lastFiniteEntropyN) ? lastFiniteEntropyN : 0.0;
        } else {
            lastFiniteEntropyN = v;
        }

        // Clamp
        if (v < 0.0) v = 0.0;
        if (v > 1.0) v = 1.0;

        // USE entropy value, NOT bmuEntropy object
        EntropyVisualizer.Sample s = new EntropyVisualizer.Sample(
            nowMs,                     // timestamp
            v,                         // ✅ entropy (0..1)
            adult.getBMUStability(),   // stability
            queue.size(),              // queue size
            queue.capacity()           // queue capacity
        );

        entropyViz.addSample(s);
    }

    
    public static final class EntropyResult {
        public final boolean ready; // true when window has enough samples
        public final double H;       // raw entropy if you want it
        public final double Hn;      // normalized entropy in [0..1]

        public EntropyResult(boolean ready, double H, double Hn) {
            this.ready = ready;
            this.H = H;
            this.Hn = Hn;
        }
    }


    
 

    /**
     * Fixed-size circular buffer for double values.
     * Overwrites oldest when full.
     */
    public final class DoubleRingBuffer {
        private final double[] buf;
        private int head = 0;   // next write index
        private int size = 0;

        public DoubleRingBuffer(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
            this.buf = new double[capacity];
        }

        public int capacity() { return buf.length; }
        public int size() { return size; }
        public boolean isEmpty() { return size == 0; }
        public boolean isFull() { return size == buf.length; }

        /** Adds a value; overwrites oldest if full. */
        public void add(double v) {
            buf[head] = v;
            head = (head + 1) % buf.length;
            if (size < buf.length) size++;
        }

        /** Gets element by age: index 0 = oldest, index size-1 = newest. */
        public double get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            int oldest = (head - size + buf.length) % buf.length;
            int i = (oldest + index) % buf.length;
            return buf[i];
        }

        /** Returns newest element. */
        public double latest() {
            if (size == 0) throw new IllegalStateException("empty");
            int i = (head - 1 + buf.length) % buf.length;
            return buf[i];
        }

        /** Copies current contents oldest->newest into a new array. */
        public double[] toArray() {
            double[] out = new double[size];
            for (int i = 0; i < size; i++) out[i] = get(i);
            return out;
        }

        public void clear() {
            head = 0;
            size = 0;
        }
        public void push(double v) {
            add(v);
        }

    }
    private BmuHitWitness snapshotBmuHitsWindow() {
        int hits = bmuHitsInWindow;
        int unique = 0;
        int maxC = 0;

        for (int c : bmuHitCounts) {
            if (c > 0) {
                unique++;
                if (c > maxC) maxC = c;
            }
        }
        return new BmuHitWitness(hits, unique, maxC, hits);
    }

    
    private double calculateBmuHitEntropyAndReset() {
        if (bmuHitsInWindow <= 0) return 0.0;

        double entropy = 0.0;
        for (int i = 0; i < bmuHitCounts.length; i++) {
            int c = bmuHitCounts[i];
            if (c <= 0) continue;

            double p = c / (double) bmuHitsInWindow;
            entropy -= p * Math.log(p);

            // reset bin
            bmuHitCounts[i] = 0;
        }
        bmuHitsInWindow = 0;

        // normalize into [0..1] by log(K)
        return entropy / Math.log(bmuHitCounts.length);
    }
 // ✅ Rolling entropy (does NOT reset counts; uses bmuEntropy's ring)
    private double calculateBmuHitEntropyRolling() {
        double e = bmuEntropy.entropyLocal();  // may be NaN when size < 2
        if (!Double.isFinite(e)) {
            // carry forward last finite value so you don't "snap to 0" due to warmup
            return Double.isFinite(lastFiniteEntropyN) ? lastFiniteEntropyN : 0.0;
        }
        // clamp and remember
        e = clamp01(e);
        lastFiniteEntropyN = e;
        return e;
    }
    private static double ema(double prev, double x, double alpha) {
        // alpha in (0,1]; smaller = slower
        return prev + alpha * (x - prev);
    }

}
