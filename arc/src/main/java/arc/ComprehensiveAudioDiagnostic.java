package arc;

import javax.sound.sampled.*;

public class ComprehensiveAudioDiagnostic {
    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("COMPREHENSIVE AUDIO DIAGNOSTIC");
        System.out.println("=".repeat(70));
        
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        System.out.println("\nFound " + mixerInfos.length + " audio devices\n");
        
        // Phase 1: List all devices
        System.out.println("=== PHASE 1: DEVICE INVENTORY ===\n");
        for (int i = 0; i < mixerInfos.length; i++) {
            Mixer.Info info = mixerInfos[i];
            Mixer mixer = AudioSystem.getMixer(info);
            
            System.out.println("Device " + i + ": " + info.getName());
            System.out.println("  Description: " + info.getDescription());
            System.out.println("  Vendor: " + info.getVendor());
            
            // Check capabilities
            Line.Info[] sourceLines = mixer.getSourceLineInfo();
            Line.Info[] targetLines = mixer.getTargetLineInfo();
            
            boolean hasOutput = false;
            boolean hasInput = false;
            
            for (Line.Info lineInfo : sourceLines) {
                if (lineInfo.getLineClass().equals(SourceDataLine.class)) {
                    hasOutput = true;
                    break;
                }
            }
            
            for (Line.Info lineInfo : targetLines) {
                if (lineInfo.getLineClass().equals(TargetDataLine.class)) {
                    hasInput = true;
                    break;
                }
            }
            
            System.out.println("  Capabilities: " + 
                (hasOutput ? "OUTPUT " : "") + 
                (hasInput ? "INPUT" : ""));
            System.out.println();
        }
        
        // Phase 2: Test OUTPUT devices
        System.out.println("\n" + "=".repeat(70));
        System.out.println("=== PHASE 2: TESTING OUTPUT DEVICES (Listen for 440Hz tone!) ===");
        System.out.println("=".repeat(70) + "\n");
        
        AudioFormat outFormat = new AudioFormat(44100, 16, 1, true, false);
        
        for (int i = 0; i < mixerInfos.length; i++) {
            Mixer.Info info = mixerInfos[i];
            Mixer mixer = AudioSystem.getMixer(info);
            
            DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, outFormat);
            if (!mixer.isLineSupported(lineInfo)) continue;
            
            System.out.println("Testing OUTPUT on Device " + i + ": " + info.getName());
            
            try {
                SourceDataLine line = (SourceDataLine) mixer.getLine(lineInfo);
                line.open(outFormat, 4096);
                line.start();
                
                System.out.println("  âœ Playing 440Hz tone for 2 seconds...");
                
                byte[] buffer = new byte[4096];
                for (int bufNum = 0; bufNum < 90; bufNum++) { // ~2 seconds
                    for (int s = 0; s < buffer.length / 2; s++) {
                        double time = (bufNum * buffer.length / 2 + s) / 44100.0;
                        double value = Math.sin(2 * Math.PI * 440 * time);
                        short sample = (short)(value * Short.MAX_VALUE * 0.7);
                        buffer[2 * s] = (byte)(sample & 0xFF);
                        buffer[2 * s + 1] = (byte)((sample >> 8) & 0xFF);
                    }
                    line.write(buffer, 0, buffer.length);
                }
                
                line.drain();
                line.stop();
                line.close();
                
                System.out.println("  âœ Test complete!");
                System.out.println("  >>> DID YOU HEAR THE TONE? If YES, this is your OUTPUT device! <<<\n");
                
                Thread.sleep(500); // Brief pause between tests
                
            } catch (Exception e) {
                System.out.println("  âœ— Failed: " + e.getMessage() + "\n");
            }
        }
        
        // Phase 3: Test INPUT devices
        System.out.println("\n" + "=".repeat(70));
        System.out.println("=== PHASE 3: TESTING INPUT DEVICES (Make some noise!) ===");
        System.out.println("=".repeat(70) + "\n");
        
        AudioFormat inFormat = new AudioFormat(44100, 16, 1, true, false);
        
        for (int i = 0; i < mixerInfos.length; i++) {
            Mixer.Info info = mixerInfos[i];
            Mixer mixer = AudioSystem.getMixer(info);
            
            DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, inFormat);
            if (!mixer.isLineSupported(lineInfo)) continue;
            
            System.out.println("Testing INPUT on Device " + i + ": " + info.getName());
            System.out.println("  MAKE NOISE NOW (talk, clap, etc.) for 3 seconds...");
            
            try {
                TargetDataLine line = (TargetDataLine) mixer.getLine(lineInfo);
                line.open(inFormat, 4096);
                line.start();
                
                byte[] buffer = new byte[4096];
                long startTime = System.currentTimeMillis();
                double maxAmplitude = 0.0;
                int samplesRead = 0;
                
                while (System.currentTimeMillis() - startTime < 3000) {
                    int bytesRead = line.read(buffer, 0, buffer.length);
                    if (bytesRead <= 0) continue;
                    
                    samplesRead += bytesRead / 2;
                    
                    for (int b = 0; b + 1 < bytesRead; b += 2) {
                        int lo = buffer[b] & 0xFF;
                        int hi = buffer[b + 1];
                        int sample = (hi << 8) | lo;
                        double normalized = sample / 32768.0;
                        maxAmplitude = Math.max(maxAmplitude, Math.abs(normalized));
                    }
                }
                
                line.stop();
                line.close();
                
                System.out.println("  Samples read: " + samplesRead);
                System.out.println("  Max amplitude: " + String.format("%.6f", maxAmplitude));
                
                if (maxAmplitude < 0.001) {
                    System.out.println("  âš  SILENT - Not detecting audio (may be wrong device or muted)");
                } else if (maxAmplitude < 0.01) {
                    System.out.println("  âš ï¸ WEAK - Very low signal (check gain/volume)");
                } else {
                    System.out.println("  âœ ACTIVE - Audio detected!");
                    System.out.println("  >>> THIS IS YOUR MICROPHONE! <<<");
                }
                System.out.println();
                
            } catch (Exception e) {
                System.out.println("  âœ— Failed: " + e.getMessage() + "\n");
            }
        }
        
        // Phase 4: Recommendations
        System.out.println("\n" + "=".repeat(70));
        System.out.println("=== RECOMMENDATIONS ===");
        System.out.println("=".repeat(70));
        System.out.println("\n1. For OUTPUT: Use the device where you HEARD the 440Hz tone");
        System.out.println("2. For INPUT: Use the device with the highest amplitude when you made noise");
        System.out.println("\n3. Update ARCConfig.java:");
        System.out.println("   public static volatile String OUTPUT_MIXER_NAME = \"<device name here>\";");
        System.out.println("   public static volatile String MIC_MIXER_NAME = \"<device name here>\";");
        System.out.println("\n4. Or select by device INDEX in your code:");
        System.out.println("   Mixer.Info[] infos = AudioSystem.getMixerInfo();");
        System.out.println("   Mixer mixer = AudioSystem.getMixer(infos[<index>]);");
        System.out.println("\nDiagnostic complete!");
    }
}