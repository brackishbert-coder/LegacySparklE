package arc;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.DecimalFormat;
import java.util.*;

/**
 * ARCControlPanel_WithDeviceSelector (full rewrite)
 *
 * Goals satisfied:
 * - Keeps the existing tabs: Pipeline, Baby SOM, Adult SOM, Musical, Devices, Code
 * - Adds persistence (Save/Load) and runner constants tabs, grouped by pipeline stage.
 * - Any enums become dropdowns (InputMode, etc.)
 * - Adds "all" public static ARCConfig fields (and runner constants, as codegen/persisted)
 *
 * Notes:
 * - ARCConfig fields are applied live via reflection (non-final only).
 * - ARCSystemRunner constants are static final; we persist + generate wiring code, but cannot apply live until you refactor.
 */
public final class ARCControlPanel_WithDeviceSelector extends JFrame {

    // -----------------------------
    // Binding model
    // -----------------------------
    private enum Target { ARC_CONFIG_FIELD, RUNNER_CONST }

    private interface Codec {
        String get(JComponent c);
        void set(JComponent c, String s);
    }

    private static final Codec TEXT = new Codec() {
        @Override public String get(JComponent c) { return ((JTextField) c).getText().trim(); }
        @Override public void set(JComponent c, String s) { ((JTextField) c).setText(s == null ? "" : s); }
    };

    private static final Codec BOOL = new Codec() {
        @Override public String get(JComponent c) { return String.valueOf(((JCheckBox) c).isSelected()); }
        @Override public void set(JComponent c, String s) { ((JCheckBox) c).setSelected(Boolean.parseBoolean(s)); }
    };

    private static final Codec COMBO = new Codec() {
        @Override public String get(JComponent c) {
            Object o = ((JComboBox<?>) c).getSelectedItem();
            return o == null ? "" : String.valueOf(o);
        }
        @Override public void set(JComponent c, String s) {
            JComboBox<?> cb = (JComboBox<?>) c;
            for (int i = 0; i < cb.getItemCount(); i++) {
                Object it = cb.getItemAt(i);
                if (it != null && String.valueOf(it).equals(s)) { cb.setSelectedIndex(i); return; }
            }
        }
    };

    private static final DecimalFormat DF = new DecimalFormat("0.########");

    private static final class Binding {
        final String key;          // properties key
        final Target target;
        final String name;         // ARCConfig field name OR runner const name
        final JComponent comp;
        final Codec codec;
        final String defaultValue; // captured at startup

        Binding(String key, Target target, String name, JComponent comp, Codec codec, String defaultValue) {
            this.key = key;
            this.target = target;
            this.name = name;
            this.comp = comp;
            this.codec = codec;
            this.defaultValue = defaultValue;
        }
    }

    private final java.util.List<Binding> bindings = new ArrayList<>();
    private final Map<String, Binding> byKey = new LinkedHashMap<>();

    // Runner (optional)
    private ARCSystemRunner runner;

    // UI - top bar
    private final JButton btnStartStop = new JButton("Start ARC");
    private final JButton btnApply = new JButton("Apply");
    private final JButton btnDefaults = new JButton("Reset defaults");
    private final JLabel lblStatus = new JLabel("idle");

    // Devices
    private final JComboBox<String> cbIn = new JComboBox<>();
    private final JComboBox<String> cbOut = new JComboBox<>();

    // Devices tab: quick test log
    private final JTextArea taDeviceTest = new JTextArea(7, 70);

    // Persistence
    private final JTextField tfPreset = new JTextField("arc_controlpanel.properties", 28);

    // Code tab
    private final JTextArea taCode = new JTextArea(22, 100);

    public ARCControlPanel_WithDeviceSelector() { this(null); }

    public ARCControlPanel_WithDeviceSelector(ARCSystemRunner runner) {
        super("ARC Control Panel (Device Selector)");
        this.runner = runner;

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        add(buildTopBar(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();

        // existing tabs preserved
        tabs.addTab("Pipeline", wrapScroll(buildPipelineTab()));
        tabs.addTab("Baby SOM", wrapScroll(buildBabyTab()));
        tabs.addTab("Adult SOM", wrapScroll(buildAdultTab()));
        tabs.addTab("Musical", wrapScroll(buildMusicalTab()));
        tabs.addTab("Devices", wrapScroll(buildDevicesTab()));
        tabs.addTab("Code", buildCodeTab());

        // new tabs, grouped by stage
        tabs.addTab("Queue/Feedback", wrapScroll(buildQueueFeedbackTab()));
        tabs.addTab("Gates", wrapScroll(buildGatesTab()));
        tabs.addTab("Runner consts", wrapScroll(buildRunnerConstsTab()));
        tabs.addTab("Persistence", buildPersistenceTab());

        add(tabs, BorderLayout.CENTER);

        refreshDevices();
        loadArcConfigIntoUI(); // initialize UI from ARCConfig
        regenerateCode();

        pack();
        setLocationByPlatform(true);
    }

    // -----------------------------
    // Top bar
    // -----------------------------
    private JComponent buildTopBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));

        btnStartStop.addActionListener(this::onStartStop);
        btnApply.addActionListener(e -> { apply(); regenerateCode(); });
        btnDefaults.addActionListener(e -> { resetDefaults(); regenerateCode(); });

        p.add(btnStartStop);
        p.add(btnApply);
        p.add(btnDefaults);

        p.add(new JLabel("Status:"));
        p.add(lblStatus);

        return p;
    }

    private void onStartStop(ActionEvent e) {
        try {
            // Always commit UI → ARCConfig before (re)starting.
            apply();

            // IMPORTANT: ARCSystemRunner owns a bunch of Swing windows. After stop(), those
            // windows may be disposed and (depending on implementation) not safely restartable.
            // To preserve the original “Start → windows appear, Stop → windows close, Start →
            // windows appear again” behavior, we create a fresh runner instance on every Start.
            if (runner == null || !runner.isRunning()) {
                runner = new ARCSystemRunner();
            }

            if (!runner.isRunning()) {
                // Start on a background thread to keep Swing responsive.
                btnStartStop.setEnabled(false);
                lblStatus.setText("Starting...");
                new Thread(() -> {
                    try {
                        runner.start();
                        SwingUtilities.invokeLater(() -> {
                            lblStatus.setText("Running");
                            btnStartStop.setText("Stop");
                            btnStartStop.setEnabled(true);
                        });
                    } catch (Throwable ex) {
                        SwingUtilities.invokeLater(() -> {
                            lblStatus.setText("Start failed: " + ex.getClass().getSimpleName());
                            btnStartStop.setEnabled(true);
                        });
                        ex.printStackTrace();
                    }
                }, "arc-runner-start").start();
            } else {
                btnStartStop.setEnabled(false);
                lblStatus.setText("Stopping...");
                new Thread(() -> {
                    try {
                        runner.stop();
                        SwingUtilities.invokeLater(() -> {
                            lblStatus.setText("Stopped");
                            btnStartStop.setText("Start ARC");
                            btnStartStop.setEnabled(true);
                            // Force a fresh runner on next start so viewer windows recreate.
                            runner = null;
                        });
                    } catch (Throwable ex) {
                        SwingUtilities.invokeLater(() -> {
                            lblStatus.setText("Stop failed: " + ex.getClass().getSimpleName());
                            btnStartStop.setEnabled(true);
                        });
                        ex.printStackTrace();
                    }
                }, "arc-runner-stop").start();
            }
        } catch (Throwable ex) {
            lblStatus.setText("Error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    // -----------------------------
    // Tab builders (grouped by pipeline stage)
    // -----------------------------
    private JPanel buildPipelineTab() {
        JPanel root = titled("Pipeline");
        addField(root, "INPUT_MODE", "Pipeline / mode");
        addField(root, "PROCESS_SLEEP_MS", "Pipeline / timing");

        addField(root, "TEXT_STEP_MS", "Text");
        addField(root, "TEXT_WORDS_PER_STEP", "Text");
        addField(root, "TEXT_SOURCE_PATH", "Text");
        addField(root, "TEXT_LOOP_FILES", "Text");
        addField(root, "POD_COS_MIN_SIM01", "Text");

        addField(root, "MIC_SAMPLE_RATE", "Audio input");
        addField(root, "INPUT_BUFFER_SIZE", "Audio input");
        addField(root, "GAIN_MULTIPLIER", "Audio input");
        addField(root, "NOISE_GATE", "Audio input");
        addField(root, "FEATURE_BINS", "Audio input");

        // Synthetic input (feeds the pipeline without a live mic/text source)
        addField(root, "SYNTHETIC_INPUT", "Synthetic input");
        addField(root, "SYNTH_MODE", "Synthetic input");

        addField(root, "PRE_BAND_ENABLED", "Pre-band");
        addField(root, "PRE_BAND_LOW_HZ", "Pre-band");
        addField(root, "PRE_BAND_HIGH_HZ", "Pre-band");

        // device indices live in Devices tab but keep as part of "all fields" requirement
        addField(root, "INPUT_DEVICE_INDEX", "Devices");
        addField(root, "OUTPUT_DEVICE_INDEX", "Devices");

        return root;
    }

    private JPanel buildBabyTab() {
        JPanel root = titled("Baby SOM");

        addField(root, "BABY_NODES", "Topology");
        addField(root, "BABY_INPUT_DIM", "Topology");

        addField(root, "BABY_LEARNING_RATE", "Learning");
        addField(root, "BABY_RADIUS", "Learning");
        addField(root, "BABY_SIGMA", "Learning");
        addField(root, "BABY_EPOCHS", "Learning");

        addField(root, "BABY_TWO1_DIVERGENCE_MSD", "TWO1");
        addField(root, "BABY_TWO1_TRACE_BOOST", "TWO1");

        return root;
    }

    private JPanel buildAdultTab() {
        JPanel root = titled("Adult SOM");

        addField(root, "ADULT_W", "Topology");
        addField(root, "ADULT_H", "Topology");
        addField(root, "ADULT_DIM", "Topology");

        addField(root, "ADULT_LEARNING_RATE", "Learning");
        addField(root, "ADULT_RADIUS", "Learning");
        addField(root, "ADULT_SIGMA", "Learning");
        addField(root, "ADULT_LR_FLOOR", "Learning");
        addField(root, "ADULT_SIGMA_FLOOR", "Learning");

        addField(root, "ADULT_BMU_HISTORY_SIZE", "Adaptive pull");
        addField(root, "ADULT_STABILITY_THRESHOLD_HIGH", "Adaptive pull");
        addField(root, "ADULT_STABILITY_THRESHOLD_LOW", "Adaptive pull");
        addField(root, "ADULT_PULL_COUNT_STABLE", "Adaptive pull");
        addField(root, "ADULT_PULL_COUNT_NORMAL", "Adaptive pull");
        addField(root, "ADULT_PULL_COUNT_CHAOTIC", "Adaptive pull");
        addField(root, "ADULT_PULL_INTERVAL_CHAOTIC_MS", "Adaptive pull");
        addField(root, "ADULT_PULL_INTERVAL_NORMAL_MS", "Adaptive pull");

        addField(root, "ADULT_TWO1_DIVERGENCE_MSD", "TWO1");
        addField(root, "ADULT_TWO1_SIGMA_MULT", "TWO1");
        addField(root, "ADULT_TWO1_LR_MULT", "TWO1");
        addField(root, "ADULT_TWO1_CURVATURE_DECAY", "TWO1");

        // private statics (in ARCConfig) are not addressable; we can't reflect them.
        return root;
    }

    private JPanel buildMusicalTab() {
        JPanel root = titled("Musical");

        addField(root, "OUTPUT_SAMPLE_RATE", "Audio out");
        addField(root, "OUTPUT_BUFFER_SIZE", "Audio out");

        addField(root, "BASE_TEMPO", "Tempo");
        addField(root, "TEMPO_RANGE", "Tempo");
        addField(root, "BEATS_PER_MEASURE", "Tempo");

        addField(root, "OUTPUT_VOLUME", "Amp/envelope");
        addField(root, "ATTACK_RATE", "Amp/envelope");
        addField(root, "RELEASE_RATE", "Amp/envelope");
        addField(root, "VIBRATO_DEPTH", "Modulation");
        addField(root, "VIBRATO_RATE", "Modulation");

        addField(root, "NUM_OSCILLATORS", "Oscillators");
        addField(root, "FUNDAMENTAL_MIX", "Mix");
        addField(root, "HARMONIC_2_MIX", "Mix");
        addField(root, "HARMONIC_3_MIX", "Mix");
        addField(root, "HARMONIC_4_MIX", "Mix");

        return root;
    }

    private JPanel buildDevicesTab() {
        JPanel root = titled("Devices");

        JPanel inner = (JPanel) root.getClientProperty("inner");
        GridBagConstraints gc = gbc();
        gc.gridy = 0;

        gc.gridx = 0; inner.add(new JLabel("Input device:"), gc);
        gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        inner.add(cbIn, gc);

        gc.gridx = 0; gc.gridy++;
        gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
        inner.add(new JLabel("Output device:"), gc);

        gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        inner.add(cbOut, gc);

        JButton btnRefresh = new JButton("Refresh");
        btnRefresh.addActionListener(e -> refreshDevices());

        JButton btnApplyDev = new JButton("Apply devices");
        btnApplyDev.addActionListener(e -> {
            set("arc.INPUT_DEVICE_INDEX", String.valueOf(selectedMixerIndex(cbIn)));
            set("arc.OUTPUT_DEVICE_INDEX", String.valueOf(selectedMixerIndex(cbOut)));
            apply();
            regenerateCode();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(btnRefresh);
        buttons.add(btnApplyDev);

        JButton btnTestOut = new JButton("Test output");
        btnTestOut.addActionListener(e -> testOutputDeviceSelected());
        JButton btnTestDefault = new JButton("Test default out");
        btnTestDefault.setToolTipText("Play tone via Java system default (bypasses device selection — always works on PipeWire)");
        btnTestDefault.addActionListener(e -> testDefaultOutput());
        JButton btnTestIn = new JButton("Test input");
        btnTestIn.addActionListener(e -> testInputDeviceSelected());
        JButton btnTestBoth = new JButton("Test both");
        btnTestBoth.addActionListener(e -> { testOutputDeviceSelected(); testInputDeviceSelected(); });
        JButton btnClear = new JButton("Clear log");
        btnClear.addActionListener(e -> taDeviceTest.setText(""));

        buttons.add(btnTestOut);
        buttons.add(btnTestDefault);
        buttons.add(btnTestIn);
        buttons.add(btnTestBoth);
        buttons.add(btnClear);

        root.add(buttons, BorderLayout.SOUTH);

        // Ensure these bindings exist even if you don't edit them in other tabs

        JScrollPane spLog = new JScrollPane(taDeviceTest);
        spLog.setBorder(new TitledBorder("Device test log"));
        spLog.getVerticalScrollBar().setUnitIncrement(16);
        root.add(spLog, BorderLayout.CENTER);

        bindArcField("INPUT_DEVICE_INDEX");
        bindArcField("OUTPUT_DEVICE_INDEX");

        return root;
    }

    private JPanel buildQueueFeedbackTab() {
        JPanel root = titled("Queue / Feedback");

        addField(root, "QUEUE_CAPACITY", "Queue");

        addField(root, "FEEDBACK_NOISE", "Feedback");
        addField(root, "FEEDBACK_THRESHOLD_BASE", "Feedback");
        addField(root, "FEEDBACK_THRESHOLD_RANGE", "Feedback");
        addField(root, "FEEDBACK_COOLDOWN_MIN", "Feedback");
        addField(root, "FEEDBACK_COOLDOWN_SCALE", "Feedback");
        addField(root, "FEEDBACK_DEQUEUE_RATE_FLOOR", "Feedback");

        return root;
    }

    private JPanel buildGatesTab() {
        JPanel root = titled("Gates / TWO1");

        addField(root, "POD_TWO1_MIN", "POD");
        addField(root, "POD_DOCK_MSD", "POD");
        addField(root, "POD_WATER_ENTROPY_DELTA", "POD");
        addField(root, "POD_COLLAPSE_COOLDOWN_MS", "POD");

        return root;
    }

    private JPanel buildRunnerConstsTab() {
        JPanel root = titled("Runner constants (persist + codegen)");

        // scrape ARCSystemRunner static finals that look like knobs
        for (String c : runnerConstants()) {
            addRunnerConst(root, c, guessRunnerDefault(c));
        }

        return root;
    }

    private JPanel buildPersistenceTab() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new TitledBorder("Persistence (Save/Load)"));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        row.add(new JLabel("Preset file:"));
        row.add(tfPreset);

        JButton btnSave = new JButton("Save");
        JButton btnLoad = new JButton("Load");

        btnSave.addActionListener(e -> savePreset(tfPreset.getText().trim()));
        btnLoad.addActionListener(e -> { loadPreset(tfPreset.getText().trim()); regenerateCode(); });

        row.add(btnSave);
        row.add(btnLoad);

        JTextArea help = new JTextArea(
                "This saves/loads ALL fields shown in the panel.\n" +
                "Runner constants are saved too (runner.* keys), but they only take effect once you wire them into ARCSystemRunner.\n" +
                "Fields that are final in ARCConfig cannot be modified live; they are still persisted for convenience + code generation.");
        help.setEditable(false);
        help.setOpaque(false);

        p.add(row, BorderLayout.NORTH);
        p.add(help, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildCodeTab() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new TitledBorder("Code"));

        taCode.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        taCode.setEditable(false);

        JButton btn = new JButton("Regenerate");
        btn.addActionListener(e -> regenerateCode());

        p.add(btn, BorderLayout.NORTH);
        p.add(new JScrollPane(taCode), BorderLayout.CENTER);
        return p;
    }

    // -----------------------------
    // UI primitives
    // -----------------------------
    private static JPanel titled(String title) {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(new TitledBorder(title));
        JPanel inner = new JPanel(new GridBagLayout());
        outer.putClientProperty("inner", inner);
        outer.add(inner, BorderLayout.NORTH);
        return outer;
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 6, 4, 6);
        gc.anchor = GridBagConstraints.WEST;
        return gc;
    }

    private static JComponent wrapScroll(JComponent c) {
        JScrollPane sp = new JScrollPane(c);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private void addField(JPanel outer, String arcFieldName, String group) {
        // Only add if field exists and is public (reflection can see it)
        Field f = getPublicArcField(arcFieldName);
        if (f == null) return;

        JLabel label = new JLabel(arcFieldName + "  [" + group + "]");
        JComponent comp;
        Codec codec;

        Class<?> t = f.getType();
        Object v0 = safeGetField(f);

        if (t == boolean.class) {
            JCheckBox cb = new JCheckBox();
            cb.setSelected(v0 instanceof Boolean && (Boolean) v0);
            comp = cb;
            codec = BOOL;
        } else if (t.isEnum()) {
            @SuppressWarnings("unchecked")
            Class<? extends Enum<?>> et = (Class<? extends Enum<?>>) t;
            Enum<?>[] vals = et.getEnumConstants();
            JComboBox<String> cb = new JComboBox<>();
            for (Enum<?> ev : vals) cb.addItem(ev.name());
            cb.setSelectedItem(v0 == null ? "" : String.valueOf(v0));
            comp = cb;
            codec = COMBO;
        } else {
            JTextField tf = new JTextField(v0 == null ? "" : String.valueOf(v0), 14);
            comp = tf;
            codec = TEXT;
        }

        // add row
        JPanel inner = (JPanel) outer.getClientProperty("inner");
        GridBagConstraints gc = gbc();
        gc.gridy = inner.getComponentCount();

        gc.gridx = 0; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
        inner.add(label, gc);

        gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        inner.add(comp, gc);

        JButton reset = new JButton("R");
        reset.setToolTipText("Reset to startup default");
        String def = v0 == null ? "" : String.valueOf(v0);
        reset.addActionListener(e -> codec.set(comp, def));

        gc.gridx = 2; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
        inner.add(reset, gc);

        // bind
        bind("arc." + arcFieldName, Target.ARC_CONFIG_FIELD, arcFieldName, comp, codec, def);
    }

    private void bindArcField(String arcFieldName) {
        if (byKey.containsKey("arc." + arcFieldName)) return;
        Field f = getPublicArcField(arcFieldName);
        if (f == null) return;
        Object v0 = safeGetField(f);
        JTextField tf = new JTextField(v0 == null ? "" : String.valueOf(v0), 10);
        bind("arc." + arcFieldName, Target.ARC_CONFIG_FIELD, arcFieldName, tf, TEXT, v0 == null ? "" : String.valueOf(v0));
    }

    private void addRunnerConst(JPanel outer, String constName, String defaultVal) {
        JTextField tf = new JTextField(defaultVal == null ? "" : defaultVal, 12);

        JPanel inner = (JPanel) outer.getClientProperty("inner");
        GridBagConstraints gc = gbc();
        gc.gridy = inner.getComponentCount();

        gc.gridx = 0; inner.add(new JLabel(constName), gc);
        gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        inner.add(tf, gc);

        JButton reset = new JButton("R");
        reset.addActionListener(e -> tf.setText(defaultVal == null ? "" : defaultVal));
        gc.gridx = 2; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
        inner.add(reset, gc);

        bind("runner." + constName, Target.RUNNER_CONST, constName, tf, TEXT, defaultVal == null ? "" : defaultVal);
    }

    private void bind(String key, Target target, String name, JComponent comp, Codec codec, String def) {
        Binding b = new Binding(key, target, name, comp, codec, def);
        bindings.add(b);
        byKey.put(key, b);
    }

    // -----------------------------
    // Apply / defaults
    // -----------------------------
    private void apply() {
        // ARCConfig live set
        try {
            for (Binding b : bindings) {
                if (b.target != Target.ARC_CONFIG_FIELD) continue;
                applyArcConfigField(b.name, b.codec.get(b.comp));
            }
            // sync device combos -> indices
            selectByMixerIndex(cbIn, ARCConfig.INPUT_DEVICE_INDEX);
            selectByMixerIndex(cbOut, ARCConfig.OUTPUT_DEVICE_INDEX);

            lblStatus.setText(runner != null && runner.isRunning() ? "running (applied)" : "applied");
        } catch (Exception ex) {
            lblStatus.setText("apply error");
            JOptionPane.showMessageDialog(this, ex.toString(), "Apply error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void resetDefaults() {
        for (Binding b : bindings) b.codec.set(b.comp, b.defaultValue);
        apply();
        lblStatus.setText("defaults");
    }

    private void loadArcConfigIntoUI() {
        for (Binding b : bindings) {
            if (b.target != Target.ARC_CONFIG_FIELD) continue;
            String v = readArcConfigFieldAsString(b.name);
            if (v != null) b.codec.set(b.comp, v);
        }
        selectByMixerIndex(cbIn, ARCConfig.INPUT_DEVICE_INDEX);
        selectByMixerIndex(cbOut, ARCConfig.OUTPUT_DEVICE_INDEX);
    }

    private static void applyArcConfigField(String fieldName, String valueStr) {
        Field f = getPublicArcField(fieldName);
        if (f == null) return;

        try {
            if (Modifier.isFinal(f.getModifiers())) return;

            Class<?> t = f.getType();
            Object v;

            if (t == int.class) v = Integer.parseInt(valueStr.trim());
            else if (t == long.class) v = Long.parseLong(valueStr.trim());
            else if (t == double.class) v = Double.parseDouble(valueStr.trim());
            else if (t == boolean.class) v = Boolean.parseBoolean(valueStr.trim());
            else if (t == String.class) v = valueStr;
            else if (t.isEnum()) {
                @SuppressWarnings({"rawtypes","unchecked"})
                Enum<?> ev = Enum.valueOf((Class<? extends Enum>) t, valueStr.trim());
                v = ev;
            } else return;

            f.set(null, v);
        } catch (Exception ignored) {
        }
    }

    private static String readArcConfigFieldAsString(String fieldName) {
        Field f = getPublicArcField(fieldName);
        if (f == null) return null;
        Object v = safeGetField(f);
        return v == null ? null : String.valueOf(v);
    }

    private static Field getPublicArcField(String name) {
        try { return ARCConfig.class.getField(name); }
        catch (Exception e) { return null; }
    }

    private static Object safeGetField(Field f) {
        try { return f.get(null); }
        catch (Exception e) { return null; }
    }

    // -----------------------------
    // Persistence
    // -----------------------------
    private void savePreset(String path) {
        if (path == null || path.isBlank()) return;
        Properties p = new Properties();

        for (Binding b : bindings) {
            p.setProperty(b.key, b.codec.get(b.comp));
        }
        p.setProperty("ui.inputDeviceName", String.valueOf(cbIn.getSelectedItem()));
        p.setProperty("ui.outputDeviceName", String.valueOf(cbOut.getSelectedItem()));

        try (OutputStream os = new FileOutputStream(path)) {
            p.store(os, "ARCControlPanel preset");
            lblStatus.setText("saved: " + path);
        } catch (Exception ex) {
            lblStatus.setText("save error");
            JOptionPane.showMessageDialog(this, ex.toString(), "Save error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadPreset(String path) {
        if (path == null || path.isBlank()) return;
        Properties p = new Properties();
        try (InputStream is = new FileInputStream(path)) {
            p.load(is);
        } catch (Exception ex) {
            lblStatus.setText("load error");
            JOptionPane.showMessageDialog(this, ex.toString(), "Load error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        for (Binding b : bindings) {
            String v = p.getProperty(b.key);
            if (v != null) b.codec.set(b.comp, v);
        }

        String inName = p.getProperty("ui.inputDeviceName");
        String outName = p.getProperty("ui.outputDeviceName");
        if (inName != null) selectByString(cbIn, inName);
        if (outName != null) selectByString(cbOut, outName);

        apply();
        lblStatus.setText("loaded: " + path);
    }

    // -----------------------------
    // Code generation
    // -----------------------------
    private void regenerateCode() {
        StringBuilder sb = new StringBuilder();

        sb.append("// --- ARCConfig assignments (applied live by this panel)\\n");
        for (Binding b : bindings) {
            if (b.target != Target.ARC_CONFIG_FIELD) continue;
            sb.append("ARCConfig.").append(b.name).append(" = ").append(toLiteralForArcField(b.name, b.codec.get(b.comp))).append(";\\n");
        }

        sb.append("\\n// --- Runner consts (persisted; wire these into ARCSystemRunner)\\n");
        for (Binding b : bindings) {
            if (b.target != Target.RUNNER_CONST) continue;
            sb.append("// ").append(b.name).append(" = ").append(b.codec.get(b.comp)).append("\\n");
        }

        taCode.setText(sb.toString());
        taCode.setCaretPosition(0);
    }

    private static String toLiteralForArcField(String fieldName, String value) {
        Field f = getPublicArcField(fieldName);
        if (f == null) return "\"" + esc(value) + "\"";
        Class<?> t = f.getType();
        if (t == String.class) return "\"" + esc(value) + "\"";
        if (t == long.class) return value.trim() + "L";
        if (t == boolean.class || t == int.class || t == double.class) return value.trim();
        if (t.isEnum()) return "ARCConfig." + t.getSimpleName() + "." + value.trim();
        return "\"" + esc(value) + "\"";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private void devLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            taDeviceTest.append(msg + "\n");
            taDeviceTest.setCaretPosition(taDeviceTest.getDocument().getLength());
        });
    }

    private static int parseIndexFromComboItem(Object item) {
        if (item == null) return -1;
        String s = String.valueOf(item);
        int c = s.indexOf(':');
        if (c <= 0) return -1;
        try { return Integer.parseInt(s.substring(0, c).trim()); }
        catch (Exception e) { return -1; }
    }

    private int selectedMixerIndex(JComboBox<String> cb) {
        return parseIndexFromComboItem(cb.getSelectedItem());
    }

    // Formats to try in order: PipeWire native first, then fallbacks.
    private static final javax.sound.sampled.AudioFormat[] PROBE_FORMATS = {
        new javax.sound.sampled.AudioFormat(48000f, 16, 2, true, false), // PipeWire default
        new javax.sound.sampled.AudioFormat(44100f, 16, 2, true, false), // standard stereo
        new javax.sound.sampled.AudioFormat(44100f, 16, 1, true, false), // legacy mono
    };

    private void testOutputDeviceSelected() {
        int mixerIndex = selectedMixerIndex(cbOut);
        if (mixerIndex < 0) { devLog("[device-test] No output selected."); return; }
        javax.sound.sampled.Mixer.Info[] infos = javax.sound.sampled.AudioSystem.getMixerInfo();
        if (mixerIndex >= infos.length) { devLog("[device-test] Bad output index: " + mixerIndex); return; }
        javax.sound.sampled.Mixer.Info mi = infos[mixerIndex];
        devLog("[device-test] OUTPUT -> " + mixerIndex + ": " + mi.getName());

        new Thread(() -> {
            javax.sound.sampled.Mixer mixer = javax.sound.sampled.AudioSystem.getMixer(mi);
            for (javax.sound.sampled.AudioFormat fmt : PROBE_FORMATS) {
                javax.sound.sampled.DataLine.Info info =
                    new javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine.class, fmt);
                if (!mixer.isLineSupported(info)) {
                    devLog("[device-test]   format not supported: " + fmtStr(fmt));
                    continue;
                }
                devLog("[device-test]   trying: " + fmtStr(fmt));
                try {
                    javax.sound.sampled.SourceDataLine line =
                        (javax.sound.sampled.SourceDataLine) mixer.getLine(info);
                    line.open(fmt, 8192);
                    line.start();
                    playBeep(line, fmt, 440.0, 0.7);
                    line.drain();
                    line.stop();
                    line.close();
                    devLog("[device-test] OUTPUT OK with " + fmtStr(fmt) + " — did you hear it?");
                    return;
                } catch (Throwable ex) {
                    devLog("[device-test]   FAILED (" + fmtStr(fmt) + "): " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
            devLog("[device-test] OUTPUT: no working format found. Try 'Test default out' instead.");
        }, "arc-output-device-test").start();
    }

    private void testDefaultOutput() {
        devLog("[device-test] Testing Java system default output (bypasses device selector)...");
        new Thread(() -> {
            for (javax.sound.sampled.AudioFormat fmt : PROBE_FORMATS) {
                javax.sound.sampled.DataLine.Info info =
                    new javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine.class, fmt);
                if (!javax.sound.sampled.AudioSystem.isLineSupported(info)) continue;
                try {
                    javax.sound.sampled.SourceDataLine line =
                        (javax.sound.sampled.SourceDataLine) javax.sound.sampled.AudioSystem.getLine(info);
                    line.open(fmt, 8192);
                    line.start();
                    devLog("[device-test] Default line opened: " + line.getLineInfo());
                    playBeep(line, fmt, 440.0, 0.7);
                    line.drain();
                    line.stop();
                    line.close();
                    devLog("[device-test] DEFAULT OUTPUT OK with " + fmtStr(fmt) + " — did you hear it?");
                    return;
                } catch (Throwable ex) {
                    devLog("[device-test] Default FAILED (" + fmtStr(fmt) + "): " + ex.getMessage());
                }
            }
            devLog("[device-test] Default output: no format worked.");
        }, "arc-default-output-test").start();
    }

    private static void playBeep(javax.sound.sampled.SourceDataLine line,
                                  javax.sound.sampled.AudioFormat fmt,
                                  double freq, double durationSec) {
        int sr = (int) fmt.getSampleRate();
        int channels = fmt.getChannels();
        int frames = (int) (durationSec * sr);
        byte[] buf = new byte[frames * channels * 2]; // 16-bit
        for (int i = 0; i < frames; i++) {
            double t = i / (double) sr;
            // fade in/out over 50ms to avoid clicks
            double env = Math.min(1.0, Math.min(i / (0.05 * sr), (frames - i) / (0.05 * sr)));
            short s16 = (short) Math.round(Math.sin(2.0 * Math.PI * freq * t) * env * 0.6 * 32767.0);
            for (int ch = 0; ch < channels; ch++) {
                buf[(i * channels + ch) * 2]     = (byte) (s16 & 0xFF);
                buf[(i * channels + ch) * 2 + 1] = (byte) ((s16 >>> 8) & 0xFF);
            }
        }
        line.write(buf, 0, buf.length);
    }

    private static String fmtStr(javax.sound.sampled.AudioFormat f) {
        return (int) f.getSampleRate() + "Hz/" + f.getSampleSizeInBits() + "bit/" +
               (f.getChannels() == 1 ? "mono" : "stereo");
    }

    private void testInputDeviceSelected() {
        int mixerIndex = selectedMixerIndex(cbIn);
        if (mixerIndex < 0) { devLog("[device-test] No input selected."); return; }
        javax.sound.sampled.Mixer.Info[] infos = javax.sound.sampled.AudioSystem.getMixerInfo();
        if (mixerIndex >= infos.length) { devLog("[device-test] Bad input index: " + mixerIndex); return; }
        javax.sound.sampled.Mixer.Info mi = infos[mixerIndex];
        devLog("[device-test] INPUT  -> " + mixerIndex + ": " + mi.getName());

        new Thread(() -> {
            javax.sound.sampled.TargetDataLine line = null;
            try {
                javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(44100f, 16, 1, true, false);
                javax.sound.sampled.Mixer mixer = javax.sound.sampled.AudioSystem.getMixer(mi);
                javax.sound.sampled.DataLine.Info info = new javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine.class, fmt);
                if (!mixer.isLineSupported(info)) {
                    devLog("[device-test] INPUT format not supported: " + fmt);
                    return;
                }
                line = (javax.sound.sampled.TargetDataLine) mixer.getLine(info);
                line.open(fmt, 4096);
                line.start();

                // read ~400ms and compute RMS/peak
                byte[] buf = new byte[4096];
                int reads = 0;
                double sumSq = 0.0;
                double peak = 0.0;
                int samples = 0;

                long end = System.currentTimeMillis() + 400;
                while (System.currentTimeMillis() < end) {
                    int n = line.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    reads++;
                    for (int i = 0; i + 1 < n; i += 2) {
                        int lo = buf[i] & 0xFF;
                        int hi = buf[i + 1];
                        short s16 = (short) ((hi << 8) | lo);
                        double v = s16 / 32768.0;
                        sumSq += v * v;
                        double av = Math.abs(v);
                        if (av > peak) peak = av;
                        samples++;
                    }
                }
                double rms = (samples > 0) ? Math.sqrt(sumSq / samples) : 0.0;
                devLog(String.format("[device-test] INPUT OK (reads=%d rms=%.4f peak=%.4f) — speak/tap while testing.", reads, rms, peak));
            } catch (Throwable ex) {
                devLog("[device-test] INPUT FAILED: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } finally {
                if (line != null) {
                    try { line.stop(); line.close(); } catch (Exception ignored) {}
                }
            }
        }, "arc-input-device-test").start();
    }


    // -----------------------------
    // Devices
    // -----------------------------
    private void refreshDevices() {
        cbIn.removeAllItems();
        cbOut.removeAllItems();

        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (int i = 0; i < mixers.length; i++) {
            Mixer mixer = AudioSystem.getMixer(mixers[i]);
            String label = i + ": " + mixers[i].getName();
            // Only include mixers with actual SourceDataLine (not just Port controls)
            if (hasLineType(mixer.getSourceLineInfo(), SourceDataLine.class)) cbOut.addItem(label);
            // Only include mixers with actual TargetDataLine (not just Port controls)
            if (hasLineType(mixer.getTargetLineInfo(), TargetDataLine.class)) cbIn.addItem(label);
        }

        selectByMixerIndex(cbIn, ARCConfig.INPUT_DEVICE_INDEX);
        selectByMixerIndex(cbOut, ARCConfig.OUTPUT_DEVICE_INDEX);
    }

    private static boolean hasLineType(Line.Info[] lines, Class<?> type) {
        for (Line.Info li : lines) {
            if (type.isAssignableFrom(li.getLineClass())) return true;
        }
        return false;
    }

    /** Select the combo item whose label mixer-index matches {@code mixerIndex}. Falls back to first item. */
    private static void selectByMixerIndex(JComboBox<String> cb, int mixerIndex) {
        for (int i = 0; i < cb.getItemCount(); i++) {
            if (parseIndexFromComboItem(cb.getItemAt(i)) == mixerIndex) {
                cb.setSelectedIndex(i);
                return;
            }
        }
        if (cb.getItemCount() > 0) cb.setSelectedIndex(0);
    }

    private static void selectByString(JComboBox<?> cb, String s) {
        if (s == null) return;
        for (int i = 0; i < cb.getItemCount(); i++) {
            Object it = cb.getItemAt(i);
            if (it != null && String.valueOf(it).equals(s)) { cb.setSelectedIndex(i); return; }
        }
    }

    private void set(String key, String value) {
        Binding b = byKey.get(key);
        if (b == null) return;
        b.codec.set(b.comp, value);
    }

    // -----------------------------
    // Runner constants discovery
    // -----------------------------
    private static java.util.List<String> runnerConstants() {
        java.util.List<String> out = new ArrayList<>();
        try {
            for (Field f : ARCSystemRunner.class.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod)) continue;
                Class<?> t = f.getType();
                if (!(t == int.class || t == long.class || t == double.class || t == boolean.class)) continue;
                out.add(f.getName());
            }
        } catch (Exception ignored) {}
        Collections.sort(out);
        return out;
    }

    private static String guessRunnerDefault(String constName) {
        try {
            Field f = ARCSystemRunner.class.getDeclaredField(constName);
            f.setAccessible(true);
            Object v = f.get(null);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    // -----------------------------
    // Main
    // -----------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ARCControlPanel_WithDeviceSelector().setVisible(true));
    }
}