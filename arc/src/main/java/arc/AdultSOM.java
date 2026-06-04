package arc;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

public final class AdultSOM {

	private final int w;
	private final int h;
	private final int dim;
	private final double[][][] weights; // [h][w][dim]
	private final double[][][] prevWeights; // [h][w][dim]
	private final Random rng;

	// Feedback bookkeeping
	private double lastEntropy = 0.0;
	private boolean inCooldown = false;
	private int cooldownTicks = 0;

	// TWO1 state
	private double[] lastInputForTwo1 = null;
	private int lastBMUFlatForTwo1 = -1;

	private final double[][] curvTwo1; // [h][w]
	private final double[][] curvGeom; // [h][w]

	// TWO1 counters
	private int two1CountWindow = 0;
	private long two1CountTotal = 0;

	private long two1RawTotal = 0;
	private int two1RawWindow = 0;

	private long two1EffTotal = 0;
	private int two1EffWindow = 0;

	private long two1SameBmuSamples = 0;
	private long two1SameBmuHits = 0;

	private boolean two1JustTriggered = false;

	// BMU history + stability
	private final int[] bmuHistory;
	private int bmuHistoryIndex = 0;
	private int bmuHistoryCount = 0;
	private long lastPullRequestMs = System.currentTimeMillis();

	private int lastBMU = -1;
	private int bmuChanges = 0;
	private int bmuSamples = 0;

	// Adaptive TWO1 threshold stats (same-BMU MSD)
	private double sameBmuMsdMeanEma = 0.0;
	private double sameBmuMsdVarEma = 0.0;
	private long sameBmuMsdCount = 0;

	private static final double TWO1_EMA_ALPHA = 0.02;
	private static final double TWO1_K_STD = 5.0;
	private static final double TWO1_MIN_STD = 1e-6;

	// -----------------------------
	// Small result structs
	// -----------------------------
	public static final class Rank2 {
		public final int bestFlat;
		public final int secondFlat;
		public final double d1; // best dist2
		public final double d2; // second dist2
		public final double margin; // d2 - d1

		Rank2(int bestFlat, int secondFlat, double d1, double d2) {
			this.bestFlat = bestFlat;
			this.secondFlat = secondFlat;
			this.d1 = d1;
			this.d2 = d2;
			this.margin = (Double.isFinite(d1) && Double.isFinite(d2)) ? (d2 - d1) : Double.NaN;
		}
	}

	// -----------------------------
	// Constructors
	// -----------------------------
	public AdultSOM(int w, int h, int dim, Random rng) {
		if (w <= 0 || h <= 0 || dim <= 0)
			throw new IllegalArgumentException("w,h,dim must be > 0");
		this.w = w;
		this.h = h;
		this.dim = dim;
		this.rng = Objects.requireNonNull(rng, "rng");

		this.curvTwo1 = new double[h][w];
		this.curvGeom = new double[h][w];

		this.bmuHistory = new int[ARCConfig.ADULT_BMU_HISTORY_SIZE];

		this.weights = new double[h][w][dim];
		this.prevWeights = new double[h][w][dim];

		// init weights in [0,1)
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				for (int j = 0; j < dim; j++) {
					double v = rng.nextDouble();
					weights[y][x][j] = v;
					prevWeights[y][x][j] = v;
				}
			}
		}

		lastEntropy = entropyOfNodeEnergies();
	}

	// Back-compat
	public AdultSOM(int size, int dim, Random rng) {
		this(Math.max(1, (int) Math.round(Math.sqrt(Math.max(1, size)))), Math.max(1,
				(int) Math.ceil(Math.max(1, size) / Math.max(1, (int) Math.round(Math.sqrt(Math.max(1, size)))))), dim,
				rng);
	}

	// -----------------------------
	// Public getters
	// -----------------------------
	public int getW() {
		return w;
	}

	public int getH() {
		return h;
	}

	public int getDim() {
		return dim;
	}

	public int getWidth() {
		return w;
	}

	public int getHeight() {
		return h;
	}

	public int size() {
		return w * h;
	}

	public int getBmuChanges() {
		return bmuChanges;
	}

	public int getBmuSamples() {
		return bmuSamples;
	}

	public double getEntropy() {
		return entropyOfNodeEnergies();
	}

	public int consumeTwo1EffectiveCountWindow() {
		int v = two1EffWindow;
		two1EffWindow = 0;
		return v;
	}

	public long getTwo1EffectiveTotal() {
		return two1EffTotal;
	}

	public long getTwo1CountTotal() {
		return two1CountTotal;
	}

	public int consumeTwo1CountWindow() {
		int v = two1CountWindow;
		two1CountWindow = 0;
		return v;
	}

	public boolean consumeTwo1JustTriggered() {
		boolean v = two1JustTriggered;
		two1JustTriggered = false;
		return v;
	}

	// -----------------------------
	// PURE BMU scan (no side-effects)
	// -----------------------------
	public Rank2 measureBmuMargin(double[] x) {
		return rank2BMU(x);
	}

	/** Flattened map copy: [w*h][dim] in row-major order (y then x). */
	public double[][] getMapCopy() {
		double[][] flat = new double[w * h][dim];
		int k = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				System.arraycopy(weights[y][x], 0, flat[k], 0, dim);
				k++;
			}
		}
		return flat;
	}

	private Rank2 rank2BMU(double[] x) {
		Objects.requireNonNull(x, "x");
		if (x.length != dim)
			throw new IllegalArgumentException("Input dim mismatch: got " + x.length + " expected " + dim);

		int bestFlat = -1;
		int secondFlat = -1;
		double bestD = Double.POSITIVE_INFINITY;
		double secondD = Double.POSITIVE_INFINITY;

		for (int y = 0; y < h; y++) {
			for (int xx = 0; xx < w; xx++) {
				double d = dist2(x, weights[y][xx]);
				int flat = y * w + xx;

				if (d < bestD) {
					secondD = bestD;
					secondFlat = bestFlat;
					bestD = d;
					bestFlat = flat;
				} else if (d < secondD) {
					secondD = d;
					secondFlat = flat;
				}
			}
		}

		return new Rank2(bestFlat, secondFlat, bestD, secondD);
	}

	// -----------------------------
	// BMU with side effects (history/stability)
	// -----------------------------
	public int observeBMU(double[] input) {
		int bmu = findBMU(input);
		if (lastBMU != -1 && bmu != lastBMU)
			bmuChanges++;
		bmuSamples++;
		lastBMU = bmu;
		return bmu;
	}

	public double getBMUStability() {
		if (bmuSamples == 0)
			return 1.0;
		return 1.0 - (bmuChanges / (double) bmuSamples);
	}

	/** Returns BMU flat index (row-major), and records history counters ONCE. */
	public int findBMU(double[] x) {
		Rank2 r = rank2BMU(x);
		int bmuFlat = r.bestFlat;

		// TWO1 same-BMU bookkeeping needs “which BMU” info; do it ONCE
		two1SameBmuSamples++;
		if (lastBMUFlatForTwo1 == bmuFlat)
			two1SameBmuHits++;

		recordBmu(bmuFlat);
		return bmuFlat;
	}

	public int[] findBMU2D(double[] x) {
		int idx = findBMU(x);
		return new int[] { idx % w, idx / w };
	}

	private void recordBmu(int bmuFlat) {
		bmuHistory[bmuHistoryIndex] = bmuFlat;
		bmuHistoryIndex = (bmuHistoryIndex + 1) % ARCConfig.ADULT_BMU_HISTORY_SIZE;
		if (bmuHistoryCount < ARCConfig.ADULT_BMU_HISTORY_SIZE)
			bmuHistoryCount++;
	}

	// -----------------------------
	// TWO1 trigger bookkeeping
	// -----------------------------
	private void onTwo1Triggered(int bmuFlat, double msd) {
		two1CountWindow++;
		two1CountTotal++;

		two1RawWindow++;
		two1RawTotal++;

		// effective mirrors raw for now
		two1EffWindow++;
		two1EffTotal++;
	}

	// -----------------------------
	// Training (BMU computed exactly once)
	// -----------------------------
	public void train(double[] x) {
		Objects.requireNonNull(x, "x");
		if (x.length != dim)
			throw new IllegalArgumentException("Input dim mismatch: got " + x.length + " expected " + dim);

		// snapshot prev for folding
		snapshotPrev();

		// PURE scan (no history side-effects)
		Rank2 r = rank2BMU(x);
		int bmuFlat = r.bestFlat;
		int bx = bmuFlat % w;
		int by = bmuFlat / w;

		// record BMU once per train call
		recordBmu(bmuFlat);

		boolean sameBMU = (bmuFlat == lastBMUFlatForTwo1);

		// TWO1 detection
		boolean isTwo1 = false;
		double[] midpoint = null;

		if (lastInputForTwo1 != null && sameBMU) {
			double msd = meanSquaredDistance(x, lastInputForTwo1);

			// --- 1) compute threshold from PREVIOUS stats ---
			if (sameBmuMsdCount > 0) {
				double prevMean = sameBmuMsdMeanEma;
				double prevVar = sameBmuMsdVarEma;

				// IMPORTANT: floor should be variance-floor, not "STD"
				double std = Math.sqrt(Math.max(TWO1_MIN_STD, sameBmuMsdVarEma));

				if (msd >= ARCConfig.ADULT_TWO1_DIVERGENCE_MSD) {
					isTwo1 = true;

					midpoint = new double[dim];
					for (int j = 0; j < dim; j++)
						midpoint[j] = 0.5 * (x[j] + lastInputForTwo1[j]);

					onTwo1Triggered(bmuFlat, msd);
					two1JustTriggered = true;

					System.out.printf("TWO1! BMU=%d msd=%.6f thresh=%.6f (mean=%.6f std=%.6f)%n", bmuFlat, msd,
							ARCConfig.ADULT_TWO1_DIVERGENCE_MSD, prevMean, std);
				}
			}

			// --- 2) update EMA stats AFTER decision (or always update) ---
			if (sameBmuMsdCount == 0) {
				sameBmuMsdMeanEma = msd;
				sameBmuMsdVarEma = 0.0;
			} else {
				double dm = msd - sameBmuMsdMeanEma;
				sameBmuMsdMeanEma += TWO1_EMA_ALPHA * dm;
				sameBmuMsdVarEma = (1.0 - TWO1_EMA_ALPHA) * sameBmuMsdVarEma + TWO1_EMA_ALPHA * dm * dm;
			}
			sameBmuMsdCount++;
		}

		double lr = ARCConfig.ADULT_LEARNING_RATE;
		double sigma = ARCConfig.ADULT_SIGMA;

		if (isTwo1) {
			lr *= ARCConfig.ADULT_TWO1_LR_MULT;
			sigma *= ARCConfig.ADULT_TWO1_SIGMA_MULT;
		}

		double sigma2 = sigma * sigma;

		double radius = ARCConfig.ADULT_RADIUS;
		double radius2 = radius * radius;

		for (int y = 0; y < h; y++) {
			int dy = y - by;
			for (int xx = 0; xx < w; xx++) {
				int dx = xx - bx;
				double gridD2 = dx * dx + dy * dy;

				if (radius > 0 && gridD2 > radius2)
					continue;

				double influence = Math.exp(-gridD2 / (2.0 * sigma2));

				double[] target = x;
				if (isTwo1) {
					// smooth transition: BMU moves to midpoint, far nodes move to x
					double blend = influence;
					target = blendedTarget(x, midpoint, blend);
				}

				double[] wv = weights[y][xx];
				for (int j = 0; j < dim; j++) {
					wv[j] += lr * influence * (target[j] - wv[j]);
					if (wv[j] < 0.0)
						wv[j] = 0.0;
					else if (wv[j] > 1.0)
						wv[j] = 1.0;
				}

				// TWO1 surface bookkeeping
				curvTwo1[y][xx] *= ARCConfig.ADULT_TWO1_CURVATURE_DECAY;
				if (isTwo1 && xx == bx && y == by)
					curvTwo1[y][xx] += 1.0;
			}
		}

		// Update TWO1 memory
		if (lastInputForTwo1 == null || lastInputForTwo1.length != dim) {
			lastInputForTwo1 = new double[dim];
		}
		System.arraycopy(x, 0, lastInputForTwo1, 0, dim);
		lastBMUFlatForTwo1 = bmuFlat;
	}

	// -----------------------------
	// Pull policy
	// -----------------------------
	public double calculateBMUStability() {
		if (bmuHistoryCount < 10)
			return 0.5;

		int[] counts = new int[w * h];
		int actualSize = Math.min(bmuHistoryCount, ARCConfig.ADULT_BMU_HISTORY_SIZE);

		for (int i = 0; i < actualSize; i++) {
			int bmu = bmuHistory[i];
			if (bmu >= 0 && bmu < counts.length)
				counts[bmu]++;
		}

		int maxCount = 0;
		for (int c : counts)
			if (c > maxCount)
				maxCount = c;

		return (double) maxCount / actualSize;
	}

	public boolean shouldPullFromQueue() {
		if (bmuHistoryCount < 10)
			return false;

		double stability = calculateBMUStability();
		long timeSinceLastPull = System.currentTimeMillis() - lastPullRequestMs;

		if (stability > ARCConfig.ADULT_STABILITY_THRESHOLD_HIGH)
			return true;
		if (stability < ARCConfig.ADULT_STABILITY_THRESHOLD_LOW) {
			return timeSinceLastPull > ARCConfig.ADULT_PULL_INTERVAL_CHAOTIC_MS;
		}
		return timeSinceLastPull > ARCConfig.ADULT_PULL_INTERVAL_NORMAL_MS;
	}

	public int howManyToPull() {
		double stability = calculateBMUStability();

		if (stability > ARCConfig.ADULT_STABILITY_THRESHOLD_HIGH) {
			lastPullRequestMs = System.currentTimeMillis();
			return ARCConfig.ADULT_PULL_COUNT_STABLE;
		}
		if (stability < ARCConfig.ADULT_STABILITY_THRESHOLD_LOW) {
			lastPullRequestMs = System.currentTimeMillis();
			return ARCConfig.ADULT_PULL_COUNT_CHAOTIC;
		}

		lastPullRequestMs = System.currentTimeMillis();
		return ARCConfig.ADULT_PULL_COUNT_NORMAL;
	}

	// -----------------------------
	// Learn from baby
	// -----------------------------
	public void learnFromBaby(double[] babyFlatMap) {
		Objects.requireNonNull(babyFlatMap, "babyFlatMap");

		int babyNodes = ARCConfig.BABY_NODES;
		int babyDim = ARCConfig.BABY_INPUT_DIM;

		if (babyFlatMap.length != babyNodes * babyDim) {
			throw new IllegalArgumentException(
					"Baby flat map size mismatch: got " + babyFlatMap.length + " expected " + (babyNodes * babyDim));
		}
		if (babyDim != dim) {
			throw new IllegalStateException("Adult dim (" + dim + ") must match BABY_INPUT_DIM (" + babyDim
					+ ") for direct exemplar learning.");
		}

		int k = 0;
		double[] exemplar = new double[dim];

		for (int bn = 0; bn < babyNodes; bn++) {
			for (int j = 0; j < dim; j++)
				exemplar[j] = babyFlatMap[k++];
			train(exemplar);
		}

		recomputeCurvature();
	}

	// -----------------------------
	// Curvature
	// -----------------------------
	public void recomputeCurvature() {
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {

				int xl = Math.max(x - 1, 0);
				int xr = Math.min(x + 1, w - 1);
				int yu = Math.max(y - 1, 0);
				int yd = Math.min(y + 1, h - 1);

				double sum = 0.0;
				for (int k = 0; k < dim; k++) {
					double lap = 4.0 * weights[y][x][k] - weights[y][xl][k] - weights[y][xr][k] - weights[yu][x][k]
							- weights[yd][x][k];
					sum += Math.abs(lap);
				}
				curvGeom[y][x] = sum / dim;
			}
		}
	}

	public double[] getCurvTwo1FlatCopy() {
		double[] flat = new double[h * w];
		int k = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++)
				flat[k++] = curvTwo1[y][x];
		}
		return flat;
	}

	public double[] getCurvGeomFlatCopy() {
		double[] flat = new double[h * w];
		int k = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++)
				flat[k++] = curvGeom[y][x];
		}
		return flat;
	}

	// -----------------------------
	// Feedback trigger
	// -----------------------------
	public boolean shouldTriggerFeedback(int queueSize, int queueCapacity, double dequeueRate) {
		if (inCooldown) {
			cooldownTicks--;
			if (cooldownTicks <= 0)
				inCooldown = false;
			return false;
		}

		double e = entropyOfNodeEnergies();
		double delta = Math.abs(e - lastEntropy);

		double fill = (queueCapacity <= 0) ? 0.0 : (double) queueSize / (double) queueCapacity;
		double threshold = ARCConfig.FEEDBACK_THRESHOLD_BASE + ARCConfig.FEEDBACK_THRESHOLD_RANGE * (1.0 - fill);

		lastEntropy = e;

		if (delta > threshold) {
			int cd = Math.max(ARCConfig.FEEDBACK_COOLDOWN_MIN, (int) (ARCConfig.FEEDBACK_COOLDOWN_SCALE
					/ Math.max(ARCConfig.FEEDBACK_DEQUEUE_RATE_FLOOR, dequeueRate)));
			cooldownTicks = cd;
			inCooldown = true;
			return true;
		}
		return false;
	}

	// -----------------------------
	// Folding baby
	// -----------------------------
	public double[] createFeedbackBaby() {
		int babyNodes = ARCConfig.BABY_NODES;
		int babyDim = ARCConfig.BABY_INPUT_DIM;

		if (babyDim != dim) {
			throw new IllegalStateException(
					"BABY_INPUT_DIM (" + babyDim + ") must match Adult dim (" + dim + ") for folding.");
		}

		double[] out = new double[babyNodes * babyDim];
		int totalNodes = w * h;

		for (int b = 0; b < babyNodes; b++) {
			int i1 = (b * 4) % totalNodes;
			int i2 = (i1 + 1) % totalNodes;

			int x1 = i1 % w, y1 = i1 / w;
			int x2 = i2 % w, y2 = i2 / w;

			for (int j = 0; j < babyDim; j++) {
				int o = b * babyDim + j;
				double t = (babyDim <= 1) ? 0.0 : (double) j / (double) (babyDim - 1);

				double v;
				if ((j & 1) == 0) {
					v = (1.0 - t) * weights[y1][x1][j] + t * weights[y2][x2][j];
				} else {
					v = (1.0 - t) * prevWeights[y1][x1][j] + t * prevWeights[y2][x2][j];
				}

				v += (rng.nextDouble() - 0.5) * ARCConfig.FEEDBACK_NOISE;

				if (v < 0.0)
					v = 0.0;
				else if (v > 1.0)
					v = 1.0;

				out[o] = v;
			}
		}
		return out;
	}

	// -----------------------------
	// Load weights
	// -----------------------------
	public void loadWeightsFresh(double[][][] src, boolean clamp01) {
		Objects.requireNonNull(src, "src");
		if (src.length != h)
			throw new IllegalArgumentException("src h mismatch: " + src.length + " != " + h);

		for (int y = 0; y < h; y++) {
			if (src[y] == null || src[y].length != w) {
				throw new IllegalArgumentException("src[" + y + "] w mismatch");
			}
			for (int x = 0; x < w; x++) {
				if (src[y][x] == null || src[y][x].length != dim) {
					throw new IllegalArgumentException("src[" + y + "][" + x + "] dim mismatch");
				}
				double[] s = src[y][x];
				double[] wv = weights[y][x];
				double[] pv = prevWeights[y][x];

				if (!clamp01) {
					System.arraycopy(s, 0, wv, 0, dim);
					System.arraycopy(s, 0, pv, 0, dim);
				} else {
					for (int j = 0; j < dim; j++) {
						double v = s[j];
						if (v < 0.0)
							v = 0.0;
						else if (v > 1.0)
							v = 1.0;
						wv[j] = v;
						pv[j] = v;
					}
				}
			}
		}

		// reset state
		lastInputForTwo1 = null;
		lastBMUFlatForTwo1 = -1;

		bmuHistoryIndex = 0;
		bmuHistoryCount = 0;
		Arrays.fill(bmuHistory, -1);

		lastBMU = -1;
		bmuChanges = 0;
		bmuSamples = 0;

		sameBmuMsdMeanEma = 0.0;
		sameBmuMsdVarEma = 0.0;
		sameBmuMsdCount = 0;

		two1CountWindow = 0;
		two1CountTotal = 0;
		two1RawWindow = 0;
		two1RawTotal = 0;
		two1EffWindow = 0;
		two1EffTotal = 0;

		for (int yy = 0; yy < h; yy++) {
			Arrays.fill(curvTwo1[yy], 0.0);
		}

		recomputeCurvature();
		lastEntropy = entropyOfNodeEnergies();

		inCooldown = false;
		cooldownTicks = 0;
		lastPullRequestMs = System.currentTimeMillis();
	}

	public void loadWeights(double[][][] loaded, boolean resetState) {
		Objects.requireNonNull(loaded, "loaded");
		if (loaded.length != h)
			throw new IllegalArgumentException("h mismatch");

		for (int y = 0; y < h; y++) {
			if (loaded[y].length != w)
				throw new IllegalArgumentException("w mismatch at y=" + y);
			for (int x = 0; x < w; x++) {
				if (loaded[y][x].length != dim)
					throw new IllegalArgumentException("dim mismatch at (" + x + "," + y + ")");
			}
		}

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				System.arraycopy(loaded[y][x], 0, weights[y][x], 0, dim);
				System.arraycopy(loaded[y][x], 0, prevWeights[y][x], 0, dim);
			}
		}

		if (resetState) {
			loadWeightsFresh(loaded, false);
			return;
		}

		recomputeCurvature();
		lastEntropy = entropyOfNodeEnergies();
	}

	// -----------------------------
	// Internals
	// -----------------------------
	private void snapshotPrev() {
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				System.arraycopy(weights[y][x], 0, prevWeights[y][x], 0, dim);
			}
		}
	}

	private double entropyOfNodeEnergies() {
		double[] energies = new double[w * h];
		int k = 0;
		double sum = 0.0;

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double e = 0.0;
				double[] v = weights[y][x];
				for (int j = 0; j < dim; j++)
					e += v[j];
				e /= dim;
				energies[k++] = e;
				sum += e;
			}
		}

		if (sum <= 1e-12)
			return 0.0;

		double ent = 0.0;
		for (double e : energies) {
			double p = e / sum;
			if (p > 1e-12)
				ent -= p * Math.log(p);
		}

		return ent / Math.log(energies.length);
	}

	private static double dist2(double[] a, double[] b) {
		double s = 0.0;
		for (int i = 0; i < a.length; i++) {
			double d = a[i] - b[i];
			s += d * d;
		}
		return s;
	}

	private static double meanSquaredDistance(double[] a, double[] b) {
		double s = 0.0;
		for (int i = 0; i < a.length; i++) {
			double d = a[i] - b[i];
			s += d * d;
		}
		return s / Math.max(1, a.length);
	}

	private static double[] blendedTarget(double[] x, double[] mid, double blend) {
		double[] out = new double[x.length];
		double a = Math.max(0.0, Math.min(1.0, blend));
		for (int i = 0; i < x.length; i++)
			out[i] = (1.0 - a) * x[i] + a * mid[i];
		return out;
	}

	// For the margin calculation in ARCSystemRunner without copying:
	// this is safe because it's a deep copy snapshot.
	public double[][][] getWeightsCopy() {
		double[][][] copy = new double[weights.length][][];
		for (int y = 0; y < weights.length; y++) {
			copy[y] = new double[weights[y].length][];
			for (int x = 0; x < weights[y].length; x++) {
				copy[y][x] = Arrays.copyOf(weights[y][x], weights[y][x].length);
			}
		}
		return copy;
	}
	
	/** Returns a COPY of the BMU weight vector (length = dim). */
	public double[] getWeightsFlatCopy(int bmuFlat) {
	    if (bmuFlat < 0 || bmuFlat >= w * h) {
	        throw new IllegalArgumentException("bmuFlat out of range: " + bmuFlat);
	    }
	    int x = bmuFlat % w;
	    int y = bmuFlat / w;
	    return Arrays.copyOf(weights[y][x], dim);
	}

}
