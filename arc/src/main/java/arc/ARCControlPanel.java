package arc;

import javax.swing.*;
import javax.swing.border.*;
import javax.sound.sampled.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class ARCControlPanel extends JFrame {
    private Map<String, Double> parameters = new HashMap<>();
    private Map<String, JLabel> valueLabels = new HashMap<>();
    private Map<String, JSlider> sliders = new HashMap<>();
    private JTextArea codeOutput;
    private JButton startStopButton;
    private JLabel statusLabel;
    private ARCSystemRunner systemRunner;

    public ARCControlPanel() {
        super("ARC System Control Panel");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 850);
        setLayout(new BorderLayout(10, 10));

        initializeDefaults();

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Audio Input", createAudioInputPanel());
        tabbedPane.addTab("BabySOM", createBabySOMapanel());
        tabbedPane.addTab("Queue", createQueuePanel());
        tabbedPane.addTab("AdultSOM", createAdultSOMPanel());
        tabbedPane.addTab("Musical Output", createMusicalOutputPanel());
        tabbedPane.addTab("Code", createCodePanel());

        add(tabbedPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(createStatusPanel(), BorderLayout.NORTH);
        bottomPanel.add(createControlButtons(), BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        applyConfiguration();
        setLocationRelativeTo(null);
    }

    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(new EmptyBorder(5, 10, 5, 10));
        panel.setBackground(new Color(240, 240, 240));

        statusLabel = new JLabel("System Idle");
        statusLabel.setFont(new Font("Arial", Font.BOLD, 14));
        panel.add(statusLabel);

        return panel;
    }

    private void initializeDefaults() {
        parameters.put("gainMultiplier", 50.0);
        parameters.put("noiseGate", 0.001);
        parameters.put("featureBins", 64.0);
        parameters.put("bufferSize", 2048.0);
        parameters.put("micSampleRate", 16000.0);

        parameters.put("babyNodes", 16.0);
        parameters.put("babyInputDim", 64.0);
        parameters.put("babyLearningRate", 0.10);
        parameters.put("babyRadius", 0.8);
        parameters.put("babySigma", 0.18);
        parameters.put("babyEpochs", 10.0);

        parameters.put("queueCapacity", 5.0);

        parameters.put("adultSize", 32.0);
        parameters.put("adultInputDim", 1024.0);
        parameters.put("adultLearningRate", 0.015);
        parameters.put("adultRadius", 1.2);
        parameters.put("adultSigma", 0.8);

        // Adult competition / feedback loop
        parameters.put("adultCompetitionIterations", 5.0);
        parameters.put("feedbackThresholdBase", 0.05);
        parameters.put("feedbackThresholdRange", 0.15);
        parameters.put("feedbackCooldownMin", 5.0);
        parameters.put("feedbackCooldownScale", 20.0);
        parameters.put("feedbackDequeueRateFloor", 0.1);
        parameters.put("feedbackNoise", 0.01);

        // Runner pacing
        parameters.put("processSleepMs", 50.0);

        parameters.put("outputSampleRate", 44100.0);
        parameters.put("outputBufferSize", 4096.0);
        parameters.put("baseTempo", 120.0);
        parameters.put("tempoRange", 80.0);
        parameters.put("beatsPerMeasure", 16.0);
        parameters.put("outputVolume", 0.4);
        parameters.put("vibratoDepth", 0.005);
        parameters.put("vibratoRate", 5.0);
        parameters.put("attackRate", 0.995);
        parameters.put("releaseRate", 0.998);
        parameters.put("numOscillators", 4.0);
        parameters.put("fundamental", 0.5);
        parameters.put("harmonic2", 0.25);
        parameters.put("harmonic3", 0.125);
        parameters.put("harmonic4", 0.0625);
    }

    private void applyConfiguration() {
        ARCConfig.updateFromMap(parameters);
    }

    private JScrollPane createAudioInputPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        panel.add(createSectionLabel("Audio Input Parameters"));
        panel.add(createSlider("gainMultiplier", "Gain Multiplier", 1, 200, 1));
        panel.add(createSlider("noiseGate", "Noise Gate Threshold", 0.0001, 0.01, 0.0001));
        panel.add(createSlider("featureBins", "Feature Bins", 16, 128, 1));
        panel.add(createSlider("bufferSize", "Buffer Size", 512, 8192, 256));
        panel.add(createSlider("micSampleRate", "Mic Sample Rate (Hz)", 8000, 48000, 1000));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createInfoLabel("Gain Multiplier: Amplifies quiet microphone input"));
        panel.add(createInfoLabel("Noise Gate: Minimum amplitude to process (filters silence)"));
        panel.add(createInfoLabel("Feature Bins: Number of frequency bands for analysis"));

        return wrapInScroll(panel);
    }

    private JScrollPane createBabySOMapanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        panel.add(createSectionLabel("BabySOM (Short-Term Memory)"));
        panel.add(createSlider("babyNodes", "Number of Nodes", 4, 64, 1));
        panel.add(createSlider("babyInputDim", "Input Dimensions", 16, 128, 1));
        panel.add(createSlider("babyLearningRate", "Learning Rate", 0.001, 0.5, 0.001));
        panel.add(createSlider("babyRadius", "Neighborhood Radius", 0.1, 2.0, 0.1));
        panel.add(createSlider("babySigma", "Sigma (sigma^2)", 0.01, 1.0, 0.01));
        panel.add(createSlider("babyEpochs", "Training Epochs", 1, 50, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createInfoLabel("Learning Rate: How quickly baby adapts (higher = faster)"));
        panel.add(createInfoLabel("Radius: Size of neighborhood affected by learning"));
        panel.add(createInfoLabel("Sigma: Spread of influence (Gaussian width)"));

        return wrapInScroll(panel);
    }

    private JScrollPane createQueuePanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        panel.add(createSectionLabel("Anonymized Queue"));
        panel.add(createSlider("queueCapacity", "Queue Capacity", 1, 20, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSubsectionLabel("Runtime Pacing"));
        panel.add(createSlider("processSleepMs", "Processing Sleep (ms)", 0, 200, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createInfoLabel("Queue Capacity: Number of BabySOMs kept in memory"));
        panel.add(createInfoLabel("When full, random BabySOM is evicted and teaches AdultSOM"));
        panel.add(createInfoLabel("Processing Sleep: Delay between audio chunks (lower = more CPU)"));

        return wrapInScroll(panel);
    }

    private JScrollPane createAdultSOMPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        panel.add(createSectionLabel("AdultSOM (Long-Term Memory)"));
        panel.add(createSlider("adultSize", "Map Size (Nodes)", 8, 128, 1));
        panel.add(createSlider("adultInputDim", "Input Dimensions", 64, 2048, 64));
        panel.add(createSlider("adultLearningRate", "Learning Rate", 0.001, 0.1, 0.001));
        panel.add(createSlider("adultRadius", "Neighborhood Radius", 0.1, 3.0, 0.1));
        panel.add(createSlider("adultSigma", "Sigma (sigma)", 0.1, 2.0, 0.1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSubsectionLabel("Competition & Feedback Loop"));
        panel.add(createSlider("adultCompetitionIterations", "Competition Iterations", 0, 20, 1));
        panel.add(createSlider("feedbackThresholdBase", "Feedback Threshold Base", 0.0, 0.2, 0.005));
        panel.add(createSlider("feedbackThresholdRange", "Feedback Threshold Range", 0.0, 0.3, 0.005));
        panel.add(createSlider("feedbackCooldownMin", "Feedback Cooldown Min (ticks)", 0, 50, 1));
        panel.add(createSlider("feedbackCooldownScale", "Feedback Cooldown Scale", 1, 100, 1));
        panel.add(createSlider("feedbackDequeueRateFloor", "Dequeue Rate Floor", 0.01, 1.0, 0.01));
        panel.add(createSlider("feedbackNoise", "Feedback Folding Noise", 0.0, 0.05, 0.001));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createInfoLabel("Learning Rate: How quickly adult consolidates (lower = more stable)"));
        panel.add(createInfoLabel("Adult learns from evicted BabySOMs slowly over time"));
        panel.add(createInfoLabel("Competition Iterations: Lateral stabilization after stamping"));
        panel.add(createInfoLabel("Feedback Threshold: Base + Range*(1-queueFillRatio)"));
        panel.add(createInfoLabel("Cooldown: max(min, scale / max(floor, dequeueRate))"));
        panel.add(createInfoLabel("Folding Noise: Adds small variation to feedback baby weights (0 disables)"));

        return wrapInScroll(panel);
    }

    private JScrollPane createMusicalOutputPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        panel.add(createSectionLabel("Musical Synthesis Parameters"));

        panel.add(createSubsectionLabel("Timing & Rhythm"));
        panel.add(createSlider("outputSampleRate", "Output Sample Rate (Hz)", 22050, 48000, 100));
        panel.add(createSlider("outputBufferSize", "Output Buffer Size", 512, 8192, 256));
        panel.add(createSlider("baseTempo", "Base Tempo (BPM)", 40, 200, 1));
        panel.add(createSlider("tempoRange", "Tempo Range Variation", 0, 120, 1));
        panel.add(createSlider("beatsPerMeasure", "Beats Per Measure", 4, 32, 1));

        panel.add(Box.createVerticalStrut(5));
        panel.add(createSubsectionLabel("Envelope & Dynamics"));
        panel.add(createSlider("attackRate", "Attack Rate", 0.9, 0.999, 0.001));
        panel.add(createSlider("releaseRate", "Release Rate", 0.9, 0.999, 0.001));
        panel.add(createSlider("outputVolume", "Output Volume", 0.1, 1.0, 0.05));

        panel.add(Box.createVerticalStrut(5));
        panel.add(createSubsectionLabel("Modulation"));
        panel.add(createSlider("vibratoDepth", "Vibrato Depth", 0.0, 0.02, 0.001));
        panel.add(createSlider("vibratoRate", "Vibrato Rate (Hz)", 1, 10, 0.5));

        panel.add(Box.createVerticalStrut(5));
        panel.add(createSubsectionLabel("Harmonics"));
        panel.add(createSlider("numOscillators", "Number of Oscillators", 1, 8, 1));
        panel.add(createSlider("fundamental", "Fundamental Mix", 0.0, 1.0, 0.05));
        panel.add(createSlider("harmonic2", "2nd Harmonic Mix", 0.0, 1.0, 0.05));
        panel.add(createSlider("harmonic3", "3rd Harmonic Mix", 0.0, 1.0, 0.05));
        panel.add(createSlider("harmonic4", "4th Harmonic Mix", 0.0, 1.0, 0.05));

        return wrapInScroll(panel);
    }

    private JPanel createCodePanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        codeOutput = new JTextArea();
        codeOutput.setFont(new Font("Monospaced", Font.PLAIN, 11));
        codeOutput.setEditable(false);

        JButton generateButton = new JButton("Generate Configuration Code");
        generateButton.addActionListener(e -> generateCode());

        JButton copyButton = new JButton("Copy to Clipboard");
        copyButton.addActionListener(e -> {
            codeOutput.selectAll();
            codeOutput.copy();
            JOptionPane.showMessageDialog(this, "Code copied to clipboard!");
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonPanel.add(generateButton);
        buttonPanel.add(copyButton);

        panel.add(buttonPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(codeOutput), BorderLayout.CENTER);

        return panel;
    }

    private JPanel createControlButtons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        panel.setBorder(new EmptyBorder(5, 5, 5, 5));

        startStopButton = new JButton("START SYSTEM");
        startStopButton.setFont(new Font("Arial", Font.BOLD, 16));
        startStopButton.setPreferredSize(new Dimension(180, 50));
        startStopButton.setBackground(new Color(100, 200, 100));
        startStopButton.setForeground(Color.WHITE);
        startStopButton.setFocusPainted(false);
        startStopButton.addActionListener(e -> toggleSystem());

        JButton testAudioButton = new JButton("Test Audio");
        testAudioButton.setFont(new Font("Arial", Font.BOLD, 14));
        testAudioButton.setPreferredSize(new Dimension(140, 50));
        testAudioButton.setBackground(new Color(100, 150, 200));
        testAudioButton.setForeground(Color.WHITE);
        testAudioButton.setFocusPainted(false);
        testAudioButton.addActionListener(e -> playTestTone());

        JButton applyButton = new JButton("Apply Changes");
        applyButton.setFont(new Font("Arial", Font.BOLD, 14));
        applyButton.setPreferredSize(new Dimension(160, 50));
        applyButton.addActionListener(e -> {
            applyConfiguration();
            JOptionPane.showMessageDialog(
                this,
                "Configuration applied!\nRestart system for all changes to take effect.",
                "Applied",
                JOptionPane.INFORMATION_MESSAGE
            );
        });

        JButton resetButton = new JButton("Reset to Defaults");
        resetButton.setFont(new Font("Arial", Font.PLAIN, 14));
        resetButton.setPreferredSize(new Dimension(160, 50));
        resetButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(
                this,
                "Reset all parameters to defaults?",
                "Confirm Reset",
                JOptionPane.YES_NO_OPTION
            );
            if (result == JOptionPane.YES_OPTION) {
                initializeDefaults();
                applyConfiguration();
                updateAllSliders();
                JOptionPane.showMessageDialog(this, "Reset to defaults!");
            }
        });

        panel.add(startStopButton);
        panel.add(testAudioButton);
        panel.add(applyButton);
        panel.add(resetButton);

        return panel;
    }

    private void playTestTone() {
        new Thread(() -> {
            try {
                System.out.println("Playing test tone (440 Hz for 2 seconds)...");
                AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
                SourceDataLine testLine = AudioSystem.getSourceDataLine(format);
                testLine.open(format);
                testLine.start();

                byte[] buffer = new byte[4096];
                for (int bufNum = 0; bufNum < 100; bufNum++) {
                    for (int i = 0; i < buffer.length / 2; i++) {
                        double time = (bufNum * buffer.length / 2 + i) / 44100.0;
                        double value = Math.sin(2 * Math.PI * 440 * time);
                        short sample = (short) (value * Short.MAX_VALUE * 0.5);
                        buffer[2 * i] = (byte) (sample & 0xFF);
                        buffer[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
                    }
                    testLine.write(buffer, 0, buffer.length);
                }

                testLine.drain();
                testLine.close();

                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(
                        this,
                        "Test tone finished!\nDid you hear a 440Hz tone?",
                        "Test Complete",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                );

            } catch (Exception ex) {
                System.err.println("Test tone failed: " + ex.getMessage());
                ex.printStackTrace();
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(
                        this,
                        "Test tone failed:\n" + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                );
            }
        }).start();
    }

    private void toggleSystem() {
        if (systemRunner == null || !systemRunner.isRunning()) {
            try {
                applyConfiguration();
                systemRunner = new ARCSystemRunner();
                systemRunner.start();
                startStopButton.setText("STOP SYSTEM");
                startStopButton.setBackground(new Color(220, 100, 100));
                statusLabel.setText("System Running");
                statusLabel.setForeground(new Color(0, 150, 0));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                    this,
                    "Error starting system:\n" + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                );
                ex.printStackTrace();
                statusLabel.setText("Error");
                statusLabel.setForeground(Color.RED);
            }
        } else {
            systemRunner.stop();
            systemRunner = null;
            startStopButton.setText("START SYSTEM");
            startStopButton.setBackground(new Color(100, 200, 100));
            statusLabel.setText("System Stopped");
            statusLabel.setForeground(Color.GRAY);
        }
    }

    private JPanel createSlider(String key, String label, double min, double max, double step) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

        JLabel nameLabel = new JLabel(label);
        nameLabel.setPreferredSize(new Dimension(200, 25));

        double currentValue = parameters.get(key);
        int steps = (int) ((max - min) / step);
        int currentStep = (int) ((currentValue - min) / step);

        JSlider slider = new JSlider(0, steps, currentStep);
        slider.setMajorTickSpacing(Math.max(1, steps / 4));
        slider.setPaintTicks(true);

        // Store conversion metadata so we can reposition sliders on reset/apply.
        slider.putClientProperty("min", min);
        slider.putClientProperty("step", step);

        sliders.put(key, slider);

        JLabel valueLabel = new JLabel(formatValue(currentValue));
        valueLabel.setPreferredSize(new Dimension(80, 25));
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        valueLabels.put(key, valueLabel);

        slider.addChangeListener(e -> {
            double value = min + slider.getValue() * step;
            parameters.put(key, value);
            valueLabel.setText(formatValue(value));
        });

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(nameLabel, BorderLayout.WEST);
        topPanel.add(valueLabel, BorderLayout.EAST);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(slider, BorderLayout.CENTER);

        return panel;
    }

    private JLabel createSectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Arial", Font.BOLD, 16));
        label.setBorder(new EmptyBorder(10, 0, 10, 0));
        return label;
    }

    private JLabel createSubsectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Arial", Font.BOLD, 13));
        label.setBorder(new EmptyBorder(5, 0, 5, 0));
        return label;
    }

    private JLabel createInfoLabel(String text) {
        JLabel label = new JLabel("i  " + text);
        label.setFont(new Font("Arial", Font.ITALIC, 11));
        label.setForeground(Color.DARK_GRAY);
        label.setBorder(new EmptyBorder(2, 10, 2, 0));
        return label;
    }

    private JScrollPane wrapInScroll(JPanel panel) {
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private String formatValue(double value) {
        if (value == (long) value) {
            return String.format("%d", (long) value);
        } else if (value < 0.01) {
            return String.format("%.4f", value);
        } else if (value < 1) {
            return String.format("%.3f", value);
        } else {
            return String.format("%.2f", value);
        }
    }

    private void updateAllSliders() {
        for (Map.Entry<String, Double> entry : parameters.entrySet()) {
            JLabel label = valueLabels.get(entry.getKey());
            JSlider slider = sliders.get(entry.getKey());
            if (label != null) {
                label.setText(formatValue(entry.getValue()));
            }
            if (slider != null) {
                Object minObj = slider.getClientProperty("min");
                Object stepObj = slider.getClientProperty("step");
                if (minObj instanceof Double && stepObj instanceof Double) {
                    double min = (Double) minObj;
                    double step = (Double) stepObj;
                    int pos = (int) Math.round((entry.getValue() - min) / step);
                    pos = Math.max(slider.getMinimum(), Math.min(slider.getMaximum(), pos));
                    slider.setValue(pos);
                }
            }
        }
    }

    private void generateCode() {
        StringBuilder sb = new StringBuilder();
        sb.append("// Current ARC Configuration\n\n");
        sb.append("ARCConfig.GAIN_MULTIPLIER = ").append(parameters.get("gainMultiplier")).append(";\n");
        sb.append("ARCConfig.NOISE_GATE = ").append(parameters.get("noiseGate")).append(";\n");
        sb.append("ARCConfig.FEATURE_BINS = ").append(parameters.get("featureBins").intValue()).append(";\n");
        sb.append("ARCConfig.INPUT_BUFFER_SIZE = ").append(parameters.get("bufferSize").intValue()).append(";\n");
        sb.append("ARCConfig.MIC_SAMPLE_RATE = ").append(parameters.get("micSampleRate").intValue()).append(";\n\n");

        sb.append("ARCConfig.BABY_NODES = ").append(parameters.get("babyNodes").intValue()).append(";\n");
        sb.append("ARCConfig.BABY_INPUT_DIM = ").append(parameters.get("babyInputDim").intValue()).append(";\n");
        sb.append("ARCConfig.BABY_LEARNING_RATE = ").append(parameters.get("babyLearningRate")).append(";\n");
        sb.append("ARCConfig.BABY_RADIUS = ").append(parameters.get("babyRadius")).append(";\n");
        sb.append("ARCConfig.BABY_SIGMA = ").append(parameters.get("babySigma")).append(";\n");
        sb.append("ARCConfig.BABY_EPOCHS = ").append(parameters.get("babyEpochs").intValue()).append(";\n\n");

        sb.append("ARCConfig.QUEUE_CAPACITY = ").append(parameters.get("queueCapacity").intValue()).append(";\n\n");

        sb.append("ARCConfig.ADULT_SIZE = ").append(parameters.get("adultSize").intValue()).append(";\n");
        sb.append("ARCConfig.ADULT_INPUT_DIM = ").append(parameters.get("adultInputDim").intValue()).append(";\n");
        sb.append("ARCConfig.ADULT_LEARNING_RATE = ").append(parameters.get("adultLearningRate")).append(";\n");
        sb.append("ARCConfig.ADULT_RADIUS = ").append(parameters.get("adultRadius")).append(";\n");
        sb.append("ARCConfig.ADULT_SIGMA = ").append(parameters.get("adultSigma")).append(";\n\n");

        sb.append("ARCConfig.ADULT_COMPETITION_ITERATIONS = ").append(parameters.get("adultCompetitionIterations").intValue()).append(";\n");
        sb.append("ARCConfig.FEEDBACK_THRESHOLD_BASE = ").append(parameters.get("feedbackThresholdBase")).append(";\n");
        sb.append("ARCConfig.FEEDBACK_THRESHOLD_RANGE = ").append(parameters.get("feedbackThresholdRange")).append(";\n");
        sb.append("ARCConfig.FEEDBACK_COOLDOWN_MIN = ").append(parameters.get("feedbackCooldownMin").intValue()).append(";\n");
        sb.append("ARCConfig.FEEDBACK_COOLDOWN_SCALE = ").append(parameters.get("feedbackCooldownScale")).append(";\n");
        sb.append("ARCConfig.FEEDBACK_DEQUEUE_RATE_FLOOR = ").append(parameters.get("feedbackDequeueRateFloor")).append(";\n");
        sb.append("ARCConfig.FEEDBACK_NOISE = ").append(parameters.get("feedbackNoise")).append(";\n");
        sb.append("ARCConfig.PROCESS_SLEEP_MS = ").append(parameters.get("processSleepMs").intValue()).append(";\n\n");

        sb.append("ARCConfig.OUTPUT_SAMPLE_RATE = ").append(parameters.get("outputSampleRate").intValue()).append(";\n");
        sb.append("ARCConfig.OUTPUT_BUFFER_SIZE = ").append(parameters.get("outputBufferSize").intValue()).append(";\n");
        sb.append("ARCConfig.BASE_TEMPO = ").append(parameters.get("baseTempo")).append(";\n");
        sb.append("ARCConfig.TEMPO_RANGE = ").append(parameters.get("tempoRange")).append(";\n");
        sb.append("ARCConfig.BEATS_PER_MEASURE = ").append(parameters.get("beatsPerMeasure").intValue()).append(";\n");
        sb.append("ARCConfig.OUTPUT_VOLUME = ").append(parameters.get("outputVolume")).append(";\n");
        sb.append("ARCConfig.VIBRATO_DEPTH = ").append(parameters.get("vibratoDepth")).append(";\n");
        sb.append("ARCConfig.VIBRATO_RATE = ").append(parameters.get("vibratoRate")).append(";\n");
        sb.append("ARCConfig.ATTACK_RATE = ").append(parameters.get("attackRate")).append(";\n");
        sb.append("ARCConfig.RELEASE_RATE = ").append(parameters.get("releaseRate")).append(";\n");
        sb.append("ARCConfig.NUM_OSCILLATORS = ").append(parameters.get("numOscillators").intValue()).append(";\n\n");

        sb.append("ARCConfig.FUNDAMENTAL_MIX = ").append(parameters.get("fundamental")).append(";\n");
        sb.append("ARCConfig.HARMONIC_2_MIX = ").append(parameters.get("harmonic2")).append(";\n");
        sb.append("ARCConfig.HARMONIC_3_MIX = ").append(parameters.get("harmonic3")).append(";\n");
        sb.append("ARCConfig.HARMONIC_4_MIX = ").append(parameters.get("harmonic4")).append(";\n");

        codeOutput.setText(sb.toString());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ARCControlPanel panel = new ARCControlPanel();
            panel.setVisible(true);
        });
    }
}
