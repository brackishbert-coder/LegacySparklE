package arc;

import javax.sound.sampled.*;

public class MicrophoneDebugger {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Audio System Debug ===\n");
        
        // List all available mixers
        System.out.println("Available Audio Mixers:");
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (int i = 0; i < mixers.length; i++) {
            System.out.println(i + ": " + mixers[i].getName());
            System.out.println("   " + mixers[i].getDescription());
        }
        System.out.println();
        AudioFormat aformat = new AudioFormat(44100, 16, 1, true, false);
        testAllMixers(aformat, 4096, 3);

        // Try to open microphone with different formats
        AudioFormat[] formatsToTry = {
            new AudioFormat(44100, 16, 1, true, false),  // CD quality
            new AudioFormat(16000, 16, 1, true, false),  // Your current format
            new AudioFormat(8000, 16, 1, true, false),   // Lower quality
            new AudioFormat(44100, 16, 2, true, false),  // Stereo
        };
        
        for (AudioFormat format : formatsToTry) {
            System.out.println("Trying format: " + format);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            
            if (AudioSystem.isLineSupported(info)) {
                System.out.println("  ✓ Format is supported");
                
                try {
                    TargetDataLine mic = (TargetDataLine) AudioSystem.getLine(info);
                    mic.open(format, 4096);
                    mic.start();
                    
                    System.out.println("  ✓ Microphone opened successfully");
                    System.out.println("  Buffer size: " + mic.getBufferSize());
                    System.out.println("  Reading audio for 3 seconds...\n");
                    
                    byte[] buffer = new byte[4096];
                    long startTime = System.currentTimeMillis();
                    int totalBytesRead = 0;
                    double maxAmplitude = 0;
                    
                    while (System.currentTimeMillis() - startTime < 3000) {
                        int bytesRead = mic.read(buffer, 0, buffer.length);
                        totalBytesRead += bytesRead;
                        
                        // Calculate amplitude
                        double sum = 0;
                        for (int i = 0; i < bytesRead - 1; i += 2) {
                            int low = buffer[i] & 0xFF;
                            int high = buffer[i + 1];
                            int sample = (high << 8) | low;
                            double normalized = sample / 32768.0;
                            sum += Math.abs(normalized);
                            maxAmplitude = Math.max(maxAmplitude, Math.abs(normalized));
                        }
                        double avgAmp = sum / (bytesRead / 2);
                        
                        System.out.printf("  Bytes: %5d | Avg Amp: %.6f | Max Amp: %.6f%n", 
                                         bytesRead, avgAmp, maxAmplitude);
                        
                        Thread.sleep(100);
                    }
                    
                    mic.stop();
                    mic.close();
                    
                    System.out.println("\n  Total bytes read: " + totalBytesRead);
                    System.out.println("  Maximum amplitude detected: " + maxAmplitude);
                    
                    if (maxAmplitude < 0.001) {
                        System.out.println("  ⚠ WARNING: Very low amplitude - mic may not be working or muted");
                    } else {
                        System.out.println("  ✓ Audio detected successfully!");
                    }
                    
                    System.out.println("\n" + "=".repeat(50) + "\n");
                    return; // Exit after first successful format
                    
                } catch (LineUnavailableException e) {
                    System.out.println("  ✗ Could not open line: " + e.getMessage());
                }
            } else {
                System.out.println("  ✗ Format not supported");
            }
            System.out.println();
        }
        
        System.out.println("Could not find a working audio configuration.");
        System.out.println("\nTroubleshooting tips:");
        System.out.println("1. Check if your microphone is plugged in");
        System.out.println("2. Check system audio settings/permissions");
        System.out.println("3. Try running with: java -Djavax.sound.sampled.Clip=com.sun.media.sound.DirectAudioDeviceProvider");
        System.out.println("4. On macOS, check System Preferences > Security & Privacy > Microphone");
        System.out.println("5. On Linux, check that pulseaudio/ALSA is configured");
    }
    
    private static void testAllMixers(AudioFormat format, int bufferSize, int seconds) {
        Mixer.Info[] infos = AudioSystem.getMixerInfo();

        for (int i = 0; i < infos.length; i++) {
            Mixer.Info info = infos[i];
            Mixer mixer = AudioSystem.getMixer(info);

            DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
            if (!mixer.isLineSupported(lineInfo)) {
                System.out.printf("%d: %s  (no TargetDataLine for %s)%n", i, info.getName(), format);
                continue;
            }

            System.out.printf("%n=== Mixer %d: %s ===%n", i, info.getName());
            System.out.println("    " + info.getDescription());

            try (TargetDataLine line = (TargetDataLine) mixer.getLine(lineInfo)) {
                line.open(format, bufferSize);
                line.start();

                byte[] buf = new byte[bufferSize];
                long end = System.currentTimeMillis() + seconds * 1000L;

                double maxAmp = 0.0;
                while (System.currentTimeMillis() < end) {
                    int bytes = line.read(buf, 0, buf.length);
                    if (bytes <= 0) continue;

                    for (int b = 0; b + 1 < bytes; b += 2) {
                        int lo = buf[b] & 0xFF;
                        int hi = buf[b + 1];          // signed
                        int sample = (hi << 8) | lo;  // signed 16-bit
                        double v = sample / 32768.0;
                        double a = Math.abs(v);
                        if (a > maxAmp) maxAmp = a;
                    }
                }

                System.out.printf("    Max amplitude: %.6f%n", maxAmp);
                if (maxAmp < 0.0005) {
                    System.out.println("    ⚠ likely silent/muted/not a live mic");
                } else {
                    System.out.println("    ✓ live signal detected");
                }
            } catch (Exception e) {
                System.out.println("    ERROR: " + e.getMessage());
            }
        }
    }

}