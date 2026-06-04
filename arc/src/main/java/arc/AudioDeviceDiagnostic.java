package arc;

import javax.sound.sampled.*;

public class AudioDeviceDiagnostic {
    public static void main(String[] args) {
        System.out.println("=== Java Audio Device Diagnostic ===\n");
        
        // List all mixers
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        System.out.println("Found " + mixerInfos.length + " audio mixers:\n");
        
        for (int i = 0; i < mixerInfos.length; i++) {
            Mixer.Info info = mixerInfos[i];
            System.out.println("Mixer " + i + ":");
            System.out.println("  Name: " + info.getName());
            System.out.println("  Description: " + info.getDescription());
            System.out.println("  Vendor: " + info.getVendor());
            
            // Try to get the mixer and check capabilities
            try {
                Mixer mixer = AudioSystem.getMixer(info);
                
                // Check for output (SourceDataLine) support
                Line.Info[] sourceLineInfos = mixer.getSourceLineInfo();
                System.out.println("  Source Lines (OUTPUT): " + sourceLineInfos.length);
                for (Line.Info lineInfo : sourceLineInfos) {
                    if (lineInfo instanceof DataLine.Info) {
                        DataLine.Info dataLineInfo = (DataLine.Info) lineInfo;
                        System.out.println("    - " + dataLineInfo.getLineClass().getSimpleName());
                    }
                }
                
                // Check for input (TargetDataLine) support
                Line.Info[] targetLineInfos = mixer.getTargetLineInfo();
                System.out.println("  Target Lines (INPUT): " + targetLineInfos.length);
                for (Line.Info lineInfo : targetLineInfos) {
                    if (lineInfo instanceof DataLine.Info) {
                        DataLine.Info dataLineInfo = (DataLine.Info) lineInfo;
                        System.out.println("    - " + dataLineInfo.getLineClass().getSimpleName());
                    }
                }
                
            } catch (Exception e) {
                System.out.println("  Error accessing mixer: " + e.getMessage());
            }
            
            System.out.println();
        }
        
        // Try to get default line
        System.out.println("=== Testing Default Audio Output ===\n");
        try {
            AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            
            System.out.println("Default line info: " + info);
            System.out.println("Is supported: " + AudioSystem.isLineSupported(info));
            
            if (AudioSystem.isLineSupported(info)) {
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                System.out.println("Got line: " + line.getClass().getName());
                System.out.println("Line info: " + line.getLineInfo());
                
                // Try to open it
                line.open(format);
                System.out.println("✓ Line opened successfully!");
                
                // Try to play a tone
                line.start();
                System.out.println("✓ Line started!");
                System.out.println("\nPlaying test tone for 2 seconds...");
                
                byte[] buffer = new byte[4096];
                int framesWritten = 0;
                for (int bufNum = 0; bufNum < 100; bufNum++) {
                    for (int i = 0; i < buffer.length / 2; i++) {
                        double time = (bufNum * buffer.length / 2 + i) / 44100.0;
                        double value = Math.sin(2 * Math.PI * 440 * time);
                        short sample = (short)(value * Short.MAX_VALUE * 0.8); // Loud!
                        buffer[2 * i] = (byte)(sample & 0xFF);
                        buffer[2 * i + 1] = (byte)((sample >> 8) & 0xFF);
                    }
                    int written = line.write(buffer, 0, buffer.length);
                    framesWritten += written;
                    
                    if (bufNum % 25 == 0) {
                        System.out.println("  Written " + framesWritten + " bytes...");
                    }
                }
                
                line.drain();
                line.stop();
                line.close();
                
                System.out.println("\n✓ Test complete! Total bytes written: " + framesWritten);
                System.out.println("\nDid you hear a 440Hz tone?");
                System.out.println("If NO: Audio routing issue (Java → wrong device)");
                System.out.println("If YES: Problem is in MusicalAdultSOMAudioOut code");
                
            } else {
                System.out.println("✗ Line format not supported!");
            }
            
        } catch (Exception e) {
            System.err.println("\n✗ Error during test:");
            e.printStackTrace();
        }
        
        System.out.println("\n=== Diagnostic Complete ===");
    }
}