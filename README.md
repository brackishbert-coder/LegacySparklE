# LegacySparklE

**SparklE** is a real-time, self-organizing audio cognition experiment. It listens to a live microphone, turns the sound into a frequency "fingerprint" many times a second, and feeds that stream into a two-stage memory hierarchy of Self-Organizing Maps (SOMs) that continuously reshape themselves to model the structure of whatever they are hearing. As it learns, it watches its own internal geometry for a signature event the project calls **TWO1** — the moment two genuinely different inputs collapse onto the same memory location, which the system treats as a "fold" or paradox in its world-model. It renders all of this live as a musical audio output and a set of curvature/entropy visualizers, and it periodically checkpoints its learned state to disk.

The codebase is also known internally as **ARC** (the Java package is `arc`, and the design notes call the engine "the ARC"). Think of SparklE as the product and ARC as the engine inside it. This repository is the original SparklE.

This is a research / art instrument, not a product with a fixed task. It does not classify, transcribe, or predict anything you ask it to. It is an always-on listening organism whose "behavior" is the way its memory surface bends, stabilizes, and destabilizes in response to the room around it.

---

## Table of contents

- [What it actually does](#what-it-actually-does)
- [The core idea: a two-stage SOM memory](#the-core-idea-a-two-stage-som-memory)
- [Architecture](#architecture)
  - [The signal pipeline](#the-signal-pipeline)
  - [The observer / event bus](#the-observer--event-bus)
  - [Component map](#component-map)
- [Key concepts](#key-concepts)
  - [BabySOM (short-term memory with STDP)](#babysom-short-term-memory-with-stdp)
  - [The AnonymizedQueue (consolidation buffer)](#the-anonymizedqueue-consolidation-buffer)
  - [AdultSOM (long-term 2-D memory)](#adultsom-long-term-2-d-memory)
  - [TWO1: geodesic convergence detection](#two1-geodesic-convergence-detection)
  - [Curvature, entropy, churn, and TMR](#curvature-entropy-churn-and-tmr)
  - [Feedback folding](#feedback-folding)
  - [The Witness: zero-entropy stress test](#the-witness-zero-entropy-stress-test)
  - [Musical output](#musical-output)
  - [Checkpoints](#checkpoints)
- [Building and running](#building-and-running)
  - [Requirements](#requirements)
  - [Dependencies](#dependencies)
  - [Build](#build)
  - [Run from Eclipse](#run-from-eclipse)
  - [Entry points and diagnostics](#entry-points-and-diagnostics)
- [Configuration](#configuration)
- [Output files](#output-files)
- [Project layout](#project-layout)
- [Roadmap](#roadmap)
- [License](#license)

---

## What it actually does

On every audio chunk read from the microphone (a few hundred times per second), SparklE:

1. Converts the raw PCM samples into a 64-bin, log-compressed, normalized frequency spectrum (a 64-dimensional feature vector in the range 0..1).
2. Trains the **AdultSOM** (long-term memory) on that vector and records which grid node "won" (the Best Matching Unit, or BMU).
3. Trains the **BabySOM** (short-term memory) on the same vector, but only if the input is loud enough to pass a noise gate.
4. Periodically snapshots the BabySOM's learned map and pushes it into a fixed-capacity **AnonymizedQueue**.
5. Lets the AdultSOM **pull** snapshots back out of the queue and consolidate them, at a rate that self-regulates based on how stable or chaotic the AdultSOM currently is.
6. Watches for **TWO1** events, curvature spikes, entropy collapse, and prediction mismatches, emitting them on an internal event bus.
7. Streams a bundle of normalized metrics to a musical synthesizer (so you hear the system's internal state) and to live Swing visualizers (so you see its curvature surfaces and entropy trace).
8. Every 30 seconds, writes a checkpoint of the AdultSOM's full weight tensor to disk.

The whole thing runs on a dedicated daemon thread driven by the audio capture loop, with Swing windows for visualization and a control panel for live parameter tuning.

---

## The core idea: a two-stage SOM memory

A Self-Organizing Map is a grid of "neurons", each holding a weight vector the same size as the input. When an input arrives, the closest neuron (the BMU) and its neighbors are nudged toward that input. Over time the grid arranges itself so that nearby neurons respond to similar inputs — it learns a topology-preserving map of the input space.

SparklE uses **two** SOMs in a deliberate cognitive metaphor:

- **BabySOM** is a small, fast, 1-dimensional SOM (16 nodes by default) that adapts quickly to the immediate moment. It is "short-term memory". It uses a spike-timing-dependent-plasticity (STDP) twist on the normal update rule, so recent, predictive activations are reinforced and stale ones decay.
- **AdultSOM** is a larger, slow, 2-dimensional grid (8×8 by default) that changes only gradually. It is "long-term memory". It never trains directly on the raw queue snapshots in bulk; instead it consolidates Baby maps a few at a time, the way a brain is thought to replay and consolidate experience during rest.

Between them sits the **AnonymizedQueue**: a capacity-limited buffer that holds recent Baby snapshots and hands them to the Adult on demand. It is called "anonymized" because both insertion and eviction are random — when the queue is full, a randomly chosen old snapshot is dropped, which decorrelates the temporal order of experience so the Adult is not just memorizing the most recent burst.

So the data flow is: **live audio → BabySOM (fast) → random queue (buffer) → AdultSOM (slow) → metrics → sound + visuals**, with a feedback path that can "fold" the Adult's learned pattern back into a fresh Baby.

---

## Architecture

### The signal pipeline

```
 microphone (javax.sound.sampled.TargetDataLine)
        │  PCM16 bytes
        ▼
 pcm16ToDoubles ──► FFTFeatures.spectrumBins ──► 64-d feature vector (0..1)
        │
        ├──► AdultSOM.train(features)         (long-term, unless paused/frozen)
        │      └─ observeBMU, TWO1 detection, curvature update
        │
        ├──► BabySOM.train(features)          (short-term, gated by noise floor)
        │
        ├──► every 250 ms: snapshot Baby map ──► AnonymizedQueue.enqueue
        │
        ├──► when Adult is "stuck" or on a timer: AnonymizedQueue.dequeue
        │                                          └─► AdultSOM.learnFromBaby
        │
        ├──► every 400 ms: feedback check ──► maybe reseed Baby from folded Adult
        │
        └──► every 250 ms: reportMetrics ──► ArcTelemetry ──► ArcBus
                                                   ├─► AudioObserver  (musical output)
                                                   ├─► EntropyViz / CurvatureViewers
                                                   └─► G1PathCoherenceProbe
```

All of this lives in `ARCSystemRunner.runLoop()`, the single hot loop that pulls audio and orchestrates every stage.

### The observer / event bus

`ARCSystemRunner` owns an `ArcBus` that carries two kinds of message:

- **Telemetry** (`ArcTelemetry`) — a dense, immutable snapshot of every normalized metric for one reporting window (entropy, BMU stability, churn, curvature delta, TWO1 rates, TMR, queue fill, current BMU, and more). Emitted roughly every 250 ms.
- **Events** (`ArcEvent`) — discrete, named occurrences with a reason string and a small numeric context map. The event types are `TWO1_TRIGGERED`, `FEEDBACK_TRIGGERED`, `FREEZE_STARTED`, `POD_COLLAPSE`, and `JITTER_APPLIED`.

Observers register on the bus and react. The bus is thread-safe (`CopyOnWriteArrayList`) and swallows observer exceptions so a misbehaving viewer can never crash the core processing thread. This is the seam through which audio output, visualization, and probes are all decoupled from the learning core.

### Component map

| Area | Classes |
|---|---|
| Orchestration | `ARCSystemRunner` (the hot loop, ~1900 lines), `ARCConfig` (the global tunable parameter surface) |
| Memory | `BabySOM`, `AdultSOM`, `AnonymizedQueue`, `EvolvingMemory`, `LiveAudioEvolvingMemory` |
| Event bus | `ArcBus`, `ArcEvent`, `ArcEventType`, `ArcTelemetry`, `EventObserver`, `TelemetryObserver`, `EventLoggerObserver` |
| Audio in / features | `FFTFeatures`, `BiquadFilter`, `DeltaAudioLayer`, `SomaticHeartbeat` |
| Audio out (musical) | `MusicalAdultSOMAudioOut`, `MusicalAdultSOMAudioOut_DeviceSelect`, `RichAdultSOMAudioOut`, `AdultSOMAudioOut`, `AudioObserver` |
| Metrics & probes | `RollingBmuEntropy`, `BmuEntropyWindow`, `EntropyWindow`, `G1PathCoherenceProbe`, `Two1AlignedTmrAverager`, `Two1TmrValidatorControls`, `ARCValidator`, `TrulyHonestMetrics` |
| Visualization (Swing) | `EntropyVisualizer`, `EntropyVizObserver`, `CurvatureSurfaceViewer`, `CurvatureViewerObserver`, `Two1AlignedTmrPlotFrame/Panel` |
| Control panels (GUI) | `ARCControlPanel`, `ARCControlPanel_WithDeviceSelector` |
| Synthetic input | `SyntheticParadox`, `SyntheticParadoxGenerator`, `TemporalParadoxGenerator`, `ParadoxBasinWavGen` |
| Witness / stress test | `arc.witness.Witness`, `arc.witness.CharTrigramCoherence`, `ZeroEntropyWitnessTest` |
| Persistence | `AdultSOMCheckpointIO` |
| Audio device diagnostics | `AudioDeviceDiagnostic`, `ComprehensiveAudioDiagnostic`, `DeepAudioDiagnostic`, `MicrophoneDebugger`, `SimplestAudioTest`, `TestEachDevice`, `TestEachMicrophone`, `TestSpecificDevice` |

---

## Key concepts

### BabySOM (short-term memory with STDP)

`BabySOM` is a 1-D SOM of `BABY_NODES` neurons (default 16), each holding a 64-d weight vector. Beyond the standard "move the winner and neighbors toward the input" rule, it adds:

- **STDP modulation.** Each node tracks its previous activation. If a node's activation rose since the last step it gets a long-term-potentiation boost; if it fell, a long-term-depression penalty. This biases learning toward connections that *predicted* the current input.
- **Eligibility traces.** A decaying per-node trace records recent involvement, used to bump the winner on a Baby-level TWO1 event.
- **Baby-level TWO1.** If two consecutive inputs land on the same winner but are far apart (mean-squared distance above `BABY_TWO1_DIVERGENCE_MSD`), the Baby trains on the *midpoint* of the two inputs and adds a trace boost at the winner — a small local version of the same fold-detection the Adult does.

A Baby can also be reseeded from a flat map via `initializeFromFlat`, which is how the feedback path injects a folded Adult pattern into a fresh Baby.

### The AnonymizedQueue (consolidation buffer)

`AnonymizedQueue<double[]>` is a fixed-capacity slot array (default 15). Both `enqueue` and `dequeue` pick slots at random: enqueue evicts a random occupied slot when full and returns the evicted item; dequeue removes a random live item. Random ordering decorrelates temporal bursts so the Adult consolidates a representative sample of recent experience rather than a contiguous run. `viewLive()` exposes a snapshot of current contents for probes.

### AdultSOM (long-term 2-D memory)

`AdultSOM` is a `w`×`h` grid (default 8×8) of 64-d weight vectors, plus a parallel `prevWeights` tensor used for folding. Highlights:

- **Pure vs. recording BMU scans.** `rank2BMU` finds the best and second-best matching nodes with no side effects (and exposes the margin between them, used by probes). `findBMU`/`observeBMU` additionally record BMU history and churn counters.
- **Self-regulated consolidation.** `calculateBMUStability` measures how concentrated recent BMUs are. `shouldPullFromQueue` and `howManyToPull` use that stability against `ADULT_STABILITY_THRESHOLD_HIGH/LOW` to decide whether the Adult is "stuck" (pull more snapshots to perturb it), "chaotic" (pull rarely), or "normal". This is the homeostatic heart of the system.
- **Curvature surfaces.** It maintains two per-node scalar fields: `curvGeom` (a discrete Laplacian of the weight surface — geometric roughness) and `curvTwo1` (an accumulator that spikes where TWO1 folds happen and decays over time).

### TWO1: geodesic convergence detection

TWO1 is the project's central, original idea. The name means roughly "two inputs, one location" — a geodesic convergence or fold.

During `AdultSOM.train`, if the current input lands on the **same BMU** as the previous input, but the two inputs are far apart in feature space (mean-squared distance ≥ `ADULT_TWO1_DIVERGENCE_MSD`), that is a TWO1 event. The interpretation: the memory surface has folded two genuinely different experiences onto one point — a paradox or crease in the world-model.

When TWO1 fires, the Adult:

- Trains toward a **blended target** — the BMU node is pulled toward the *midpoint* of the two inputs while distant nodes move toward the raw input, smoothing the fold rather than tearing it.
- Temporarily **tightens its neighborhood** (`ADULT_TWO1_SIGMA_MULT`) and **strengthens the pull** (`ADULT_TWO1_LR_MULT`).
- Bumps the `curvTwo1` accumulator at that node.
- Emits a `TWO1_TRIGGERED` event on the bus.

The system tracks raw, effective, and windowed TWO1 counts, and keeps a running EMA of the same-BMU divergence distribution so the notion of "far apart" can be calibrated against recent history.

### Curvature, entropy, churn, and TMR

These are the live "vital signs" reported every window in `ArcTelemetry`:

- **Entropy** — how evenly the Adult's node energies (or rolling BMU hit distribution) are spread. Low entropy means the system has collapsed onto a few nodes; high entropy means it is exercising the whole map.
- **BMU stability / churn** — how often the winning node changes from step to step. Stable = settled representation; high churn = thrashing.
- **Curvature delta** — the change in mean absolute curvature of the weight surface, a proxy for how violently the manifold is reshaping.
- **TMR (Temporal Mismatch Rate)** — the Adult keeps a simple next-BMU predictor (`predNext`). TMR is the fraction of steps where the actual next BMU differed from the predicted one — a "surprise" signal.

`Two1AlignedTmrAverager` and `Two1TmrValidatorControls` exist specifically to study whether TWO1 events are *causally* aligned with TMR surprise, residualizing against confounders like churn and entropy.

### Feedback folding

Every `FEEDBACK_CHECK_MS` (400 ms), the Adult checks whether its entropy has jumped by more than an adaptive, queue-fill-dependent threshold (`shouldTriggerFeedback`). If so — or if the queue is about to overflow — the system **folds**: `createFeedbackBaby` weaves the Adult's current and previous weight surfaces into a new flat Baby map (alternating even/odd dimensions between current and previous weights, plus a little `FEEDBACK_NOISE`), reseeds the Baby from it, enqueues it, and emits `FEEDBACK_TRIGGERED`. This is a self-referential loop: the long-term memory feeds a distilled version of itself back into the short-term stream.

There is also a **freeze** mechanism: when curvature stays "hot" for too long after a fold, the Adult's learning is frozen for a short window (`FREEZE_STARTED`) so a structural paradox cannot run away and tear the whole surface.

### The Witness: zero-entropy stress test

If the rolling BMU entropy collapses to zero for several consecutive windows (the map has gone catatonic, mapping everything to one node), the **Witness** arms. It injects controlled noise into the feature stream for a few windows and watches how many windows the system takes to recover. This is an active liveness probe: it distinguishes "the room is genuinely silent/constant" from "the map is stuck". `ZeroEntropyWitnessTest` and the `arc.witness` package implement and exercise this.

### Musical output

The telemetry stream is sonified. `MusicalAdultSOMAudioOut_DeviceSelect` is a multi-oscillator additive synthesizer whose tempo, harmonic mix, vibrato, and envelope are driven live by entropy, churn, TMR, curvature delta, and the TWO1 rate, with a `SomaticHeartbeat` low-frequency oscillator modulating envelopes like a physiological clock. Note that `OUTPUT_VOLUME` defaults to `0.0` — you must raise it (via the control panel or config) to actually hear anything.

### Checkpoints

Every `CHECKPOINT_EVERY_MS` (30 s) the Adult's weights are written to the `arc_checkpoints/` directory as a pair of files per snapshot: a small self-describing `.json` manifest and a `.bin` little-endian, row-major (`[h][w][dim]`) weight blob, optionally clamped to 0..1 and hashed for corruption detection. `AdultSOMCheckpointIO` handles read/write; this is how a learned memory surface survives a restart.

---

## Building and running

### Requirements

- **JDK 11** (the `maven-compiler-plugin` is pinned to source/target 11).
- **Apache Maven** (or just build it through Eclipse's embedded Maven — see below).
- A **working microphone** and an **audio output device**. The system opens a `TargetDataLine` on startup; with no usable input line it cannot run. The bundled diagnostic mains exist precisely to sort out audio device problems.
- A **graphical display**. The control panel and the curvature/entropy viewers are Swing windows; this is not a headless program.

### Dependencies

**None beyond the JDK.** The `pom.xml` declares no third-party dependencies — everything uses `java.*` and `javax.sound.sampled` / `javax.swing`. There is nothing to download, no API keys, no native libraries. The only build plugin is the standard compiler plugin.

### Build

From the `arc/` directory:

```
mvn compile
```

That compiles all sources to `arc/target/classes`. There are currently no automated unit tests wired into the build (the `src/test` tree is empty), so a plain `compile` is the meaningful check.

### Run from Eclipse

This project is set up as an Eclipse project (`.classpath` / `.project` live in `arc/`) and is intended to be run from within Eclipse:

1. Import `arc/` as an existing Maven project (or existing Eclipse project).
2. Open `src/main/java/arc/ARCControlPanel.java`.
3. Run it as a Java Application. The control panel window appears.
4. Press **START SYSTEM**. That constructs an `ARCSystemRunner`, opens the microphone, spawns the processing thread, and brings up the curvature and entropy viewer windows.
5. Tune parameters live with the sliders and press **Apply Changes** to push them into `ARCConfig`. Raise **Output Volume** if you want to hear the musical output.

> Note: most `ARCConfig` fields apply live, but a handful of structural constants in `ARCSystemRunner` are `static final` and only take effect on a fresh run.

### Entry points and diagnostics

The primary entry point is **`arc.ARCControlPanel`** (`public static void main`). `arc.ARCControlPanel_WithDeviceSelector` is a richer variant that adds explicit input/output device selection and config import/export.

If audio does not work, run one of the diagnostic mains directly — each has its own `main` and prints what it finds:

- `arc.SimplestAudioTest` — minimal "can I make a sound at all" check.
- `arc.AudioDeviceDiagnostic`, `arc.ComprehensiveAudioDiagnostic`, `arc.DeepAudioDiagnostic` — enumerate and probe mixers/lines in increasing depth.
- `arc.MicrophoneDebugger`, `arc.TestEachMicrophone`, `arc.TestEachDevice`, `arc.TestSpecificDevice` — exercise capture devices.
- `arc.TrulyHonestMetrics` — offline metric / loopback fidelity checks.
- `arc.ParadoxBasinWavGen` — generates synthetic "paradox" WAV input for experiments.

There is intentionally **no single `main` for the whole engine** other than the control panels; the runner is meant to be driven by a GUI.

---

## Configuration

Almost every knob lives in `ARCConfig` as a `public static volatile` field, grouped by stage. The defaults that define the standard pipeline:

| Field | Default | Meaning |
|---|---|---|
| `FEATURE_BINS` | 64 | Frequency bins extracted per chunk |
| `BABY_NODES` | 16 | Neurons in the short-term SOM |
| `BABY_INPUT_DIM` | 64 | Baby input dimensionality |
| `BABY_LEARNING_RATE` | 0.10 | Baby adaptation speed |
| `QUEUE_CAPACITY` | 15 | Consolidation buffer size |
| `ADULT_W` × `ADULT_H` | 8 × 8 | Long-term grid shape |
| `ADULT_DIM` | 64 | Adult input dimensionality |
| `ADULT_LEARNING_RATE` | 0.001 | Adult adaptation speed (slow) |
| `ADULT_SIGMA` | 1.5 | Adult neighborhood width |
| `ADULT_STABILITY_THRESHOLD_HIGH/LOW` | 0.7 / 0.2 | "Stuck" vs. "chaotic" bands for pull policy |
| `ADULT_TWO1_DIVERGENCE_MSD` | 0.07 | How far apart same-BMU inputs must be to count as TWO1 |
| `OUTPUT_VOLUME` | 0.0 | Musical output gain (raise to hear it) |
| `GAIN_MULTIPLIER` | 50.0 | Microphone input amplification |
| `NOISE_GATE` | 0.0001 | Minimum amplitude to train the Baby |

**Pipeline invariant:** `FEATURE_BINS`, `BABY_INPUT_DIM`, and `ADULT_DIM` must all be equal (default 64). The Adult learns Baby exemplars directly, so a dimension mismatch throws at runtime. There are also full parameter blocks for the musical synthesizer, the pre-processing band filter, the Pair-of-Docks (POD) collapse detector, and synthetic input modes. `ARCConfig.updateFromMap` is how the control panel pushes a slider map into these fields live.

---

## Output files

When run, the working directory accumulates:

- `arc_checkpoints/adult_<timestamp>.json` and `.bin` — periodic AdultSOM weight snapshots.
- `adult_vector.csv` — per-step log of timestamp, BMU, and the full Adult vector.
- a metrics CSV (opened by `openMetricsCsv`) — per-window telemetry for offline analysis.

These are runtime artifacts; the checked-in copies under `arc/arc_checkpoints/` are sample captures from earlier sessions.

---

## Project layout

```
LegacySparklE/
├── LICENSE
├── README.md                ← this file
└── arc/                      ← the Maven/Eclipse project
    ├── pom.xml               ← Java 11, no external dependencies
    ├── src/main/java/arc/    ← all engine + GUI source
    │   └── witness/          ← zero-entropy Witness probe
    ├── arc_checkpoints/      ← sample AdultSOM checkpoints
    ├── *.md / *.txt          ← design notes and roadmaps
    └── target/               ← build output (generated)
```

The `arc/` folder also carries the original design essays: `The ARC Thirteen Comprehensive.txt`, `10 Actionable Items to.md`, `Item by Item Technical.txt`, and `Below is a concrete ablation.txt`. They describe where the author wants the system to go and are the source of the roadmap below.

---

## Roadmap

Drawn from the in-repo design notes. These are aspirational directions, not implemented features:

- **Entropy-weighted consolidation** — replace random queue eviction with priority based on surprise/information, so the most structurally significant Baby maps are retained.
- **Adaptive sub-grids ("manifold blooming")** — let a persistently "braided" node split into a local higher-resolution sub-grid where the data is genuinely complex.
- **Multi-band perception** — parallel low/mid/high-frequency BabySOMs converging into one Adult, mimicking tonotopic organization.
- **Predictive coding & multi-step TMR** — move from predicting the next BMU to predicting several steps ahead, surfacing rhythm and meter.
- **Topological persistence / "personality drift"** — diff two weight manifolds across time to quantify how much the world-model has shifted.
- **Meta-controller / hyperparameter gardening** — a slow outer loop that watches diversity and curvature and gently retunes the many parameters to keep the system in a healthy homeostatic state.
- **Structural-integrity "immune response"** — a topological scrubber that locally re-initializes a runaway high-curvature hot-spot from its healthy neighbors before it tears the surface.
- **Benchmarks, ablations, and a web dashboard** — to move the project from "interesting demo" to validated, shareable research.

See the design notes in `arc/` for the full thirteen-item and ten-item plans.

---

## License

See [LICENSE](LICENSE).
