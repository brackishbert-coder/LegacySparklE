package arc;


import javax.sound.sampled.*;
import java.util.Arrays;
import java.util.Objects;

class AdultSOMAudioOut implements AutoCloseable {
    private final AdultSOM adult;
    private final SourceDataLine line;
    private final float sampleRate;
    private final int bufferSize;
    private volatile boolean running = true;

    private double[] lastInput = new double[64]; // Store last input for BMU calculation

    public AdultSOMAudioOut(AdultSOM adult, float sampleRate, int bufferSize) throws LineUnavailableException {
        this.adult = adult;
        this.sampleRate = sampleRate;
        this.bufferSize = bufferSize;

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, bufferSize);
    }

    public void start() {
        new Thread(() -> {
            line.start();
            byte[] buffer = new byte[bufferSize];
            while (running) {
                double freq = calculateFrequencyFromAdultSOM();
                for (int i = 0; i < buffer.length / 2; i++) {
                    double time = i / sampleRate;
                    double value = Math.sin(2 * Math.PI * freq * time);
                    short sample = (short) (value * Short.MAX_VALUE);
                    buffer[2 * i] = (byte) (sample & 0xFF);
                    buffer[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
                }
                line.write(buffer, 0, buffer.length);
            }
            line.drain();
            line.stop();
        }).start();
    }

    private double calculateFrequencyFromAdultSOM() {
        int bmuIndex = adult.findBMU(lastInput);
        double[] bmuWeights = adult.getMapCopy()[bmuIndex];
        double avgWeight = Arrays.stream(bmuWeights).average().orElse(0.5);
        return 220 + avgWeight * (880 - 220);
    }

    public void updateInput(double[] input) {
        if (input.length == lastInput.length) {
            System.arraycopy(input, 0, lastInput, 0, input.length);
        }
    }

    @Override
    public void close() {
        running = false;
        line.close();
    }
}
