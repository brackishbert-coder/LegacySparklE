package arc;

import javax.sound.sampled.*;

/**
 * The absolute simplest audio test possible.
 * If this doesn't work, Java audio is broken on your system.
 */
public class SimplestAudioTest {
    public static void main(String[] args) {
        System.out.println("=== SIMPLEST AUDIO TEST ===");
        System.out.println("This is the most basic audio test possible.\n");
        
        try {
            // Use defaults for everything
            AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            
            line.open(format);
            line.start();
            
            System.out.println("*** LISTEN NOW - Playing tone for 2 seconds ***\n");
            
            // Generate simple sine wave
            byte[] buffer = new byte[4410 * 4]; // 0.1 second worth
            
            for (int repeat = 0; repeat < 20; repeat++) { // 2 seconds total
                for (int i = 0; i < buffer.length / 4; i++) {
                    double angle = 2.0 * Math.PI * 440.0 * i / 44100.0;
                    short sample = (short)(Math.sin(angle) * Short.MAX_VALUE * 0.8);
                    
                    // Left channel
                    buffer[i * 4] = (byte)(sample & 0xFF);
                    buffer[i * 4 + 1] = (byte)((sample >> 8) & 0xFF);
                    // Right channel
                    buffer[i * 4 + 2] = (byte)(sample & 0xFF);
                    buffer[i * 4 + 3] = (byte)((sample >> 8) & 0xFF);
                }
                
                line.write(buffer, 0, buffer.length);
                System.out.print(".");
            }
            
            System.out.println("\n\nDraining...");
            line.drain();
            line.stop();
            line.close();
            
            System.out.println("\nâœ Test complete!");
            System.out.println("\nDid you hear a tone?");
            System.out.println("  YES = Java audio works, problem is elsewhere");
            System.out.println("  NO = Java audio is broken on your system");
            
        } catch (Exception e) {
            System.out.println("\nâœ— FAILED: " + e.getMessage());
            e.printStackTrace();
            
            System.out.println("\nTROUBLESHOOTING:");
            System.out.println("1. Is your volume turned up?");
            System.out.println("2. Are speakers/headphones connected?");
            System.out.println("3. Close other apps using audio");
            System.out.println("4. Check Java installation");
            System.out.println("5. Try running as administrator/sudo");
        }
    }
}
