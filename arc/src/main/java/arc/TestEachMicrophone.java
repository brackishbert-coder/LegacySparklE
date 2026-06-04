package arc;

import javax.sound.sampled.*;

public class TestEachMicrophone {
    public static void main(String[] args) {
        System.out.println("=== TESTING EACH MICROPHONE INPUT DEVICE ===");
        System.out.println("This will record from EACH device for 3 seconds.");
        System.out.println("MAKE NOISE (talk, clap, etc.) during each test!\n");
        
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        AudioFormat format = new AudioFormat(44100, 16, 1, true, false); // mono
        
        for (int i = 0; i < mixerInfos.length; i++) {
            String name = mixerInfos[i].getName();
            
            // Skip port mixers
            if (name.startsWith("Port ")) {
                System.out.println("Device " + i + ": " + name + " [SKIPPED - Port mixer]");
                continue;
            }
            
            System.out.println("\n" + "=".repeat(70));
            System.out.println("Device " + i + ": " + name);
            System.out.println(mixerInfos[i].getDescription());
            System.out.println("=".repeat(70));
            
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfos[i]);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                
                if (!mixer.isLineSupported(info)) {
                    System.out.println("âœ— Cannot record audio on this device\n");
                    continue;
                }
                
                TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                line.open(format, 4096);
                line.start();
                
                System.out.println("*** RECORDING FOR 3 SECONDS ***");
                System.out.println("*** MAKE NOISE NOW - TALK, CLAP, SNAP! ***\n");
                
                byte[] buffer = new byte[4096];
                long startTime = System.currentTimeMillis();
                double maxAmplitude = 0.0;
                double totalEnergy = 0.0;
                int samplesRead = 0;
                
                while (System.currentTimeMillis() - startTime < 3000) {
                    int bytesRead = line.read(buffer, 0, buffer.length);
                    if (bytesRead <= 0) continue;
                    
                    for (int b = 0; b + 1 < bytesRead; b += 2) {
                        int lo = buffer[b] & 0xFF;
                        int hi = buffer[b + 1];
                        int sample = (hi << 8) | lo;
                        double normalized = sample / 32768.0;
                        double amp = Math.abs(normalized);
                        
                        maxAmplitude = Math.max(maxAmplitude, amp);
                        totalEnergy += amp;
                        samplesRead++;
                    }
                }
                
                line.stop();
                line.close();
                
                double avgAmplitude = totalEnergy / Math.max(1, samplesRead);
                
                System.out.println("Results:");
                System.out.println("  Max amplitude: " + String.format("%.6f", maxAmplitude));
                System.out.println("  Avg amplitude: " + String.format("%.6f", avgAmplitude));
                System.out.println("  Samples read:  " + samplesRead);
                
                if (maxAmplitude < 0.0001) {
                    System.out.println("\n  âœ— SILENT - No audio detected (wrong device or muted)");
                } else if (maxAmplitude < 0.001) {
                    System.out.println("\n  âš ï¸ VERY WEAK - Possible mic but very low (check gain/volume)");
                } else if (maxAmplitude < 0.01) {
                    System.out.println("\n  âš ï¸ WEAK - Low signal (increase mic volume)");
                } else {
                    System.out.println("\n  âœ ACTIVE - STRONG AUDIO DETECTED!");
                    System.out.println("  >>> THIS IS YOUR MICROPHONE! <<<");
                    System.out.println("  >>> Use index: " + i + " <<<");
                    System.out.println("  >>> Or name: \"" + name + "\" <<<");
                }
                
                // Pause between tests
                Thread.sleep(500);
                
            } catch (Exception e) {
                System.out.println("âœ— Error: " + e.getMessage());
            }
        }
        
        System.out.println("\n\n" + "=".repeat(70));
        System.out.println("TESTING COMPLETE");
        System.out.println("=".repeat(70));
        System.out.println("\nWhich device had the HIGHEST amplitude when you made noise?");
        System.out.println("That's your microphone!");
        System.out.println("\nUpdate your code to use that device index.");
    }
}
