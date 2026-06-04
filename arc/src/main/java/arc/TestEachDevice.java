package arc;

import javax.sound.sampled.*;

public class TestEachDevice {
    public static void main(String[] args) {
        System.out.println("=== TESTING EACH AUDIO DEVICE ===");
        System.out.println("This will play a tone on EACH device.");
        System.out.println("Listen for which one makes sound!\n");
        
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        AudioFormat format = new AudioFormat(44100, 16, 2, true, false); // stereo
        
        // Only test "plughw" devices - these are actual outputs
        for (int i = 0; i < mixerInfos.length; i++) {
            String name = mixerInfos[i].getName();
            
            // Skip port mixers - they're not actual outputs
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
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                
                if (!mixer.isLineSupported(info)) {
                    System.out.println("âœ— Cannot output audio on this device\n");
                    continue;
                }
                
                SourceDataLine line = (SourceDataLine) mixer.getLine(info);
                line.open(format, 8192);
                line.start();
                
                System.out.println("*** PLAYING 440Hz TONE FOR 2 SECONDS ***");
                System.out.println("*** LISTEN NOW! ***\n");
                
                byte[] buffer = new byte[8192];
                
                for (int buf = 0; buf < 85; buf++) { // ~2 seconds
                    for (int s = 0; s < buffer.length / 4; s++) {
                        double time = (buf * buffer.length / 4 + s) / 44100.0;
                        double angle = 2.0 * Math.PI * 440.0 * time;
                        short sample = (short)(Math.sin(angle) * Short.MAX_VALUE * 0.8);
                        
                        // Left channel
                        buffer[s * 4] = (byte)(sample & 0xFF);
                        buffer[s * 4 + 1] = (byte)((sample >> 8) & 0xFF);
                        // Right channel
                        buffer[s * 4 + 2] = (byte)(sample & 0xFF);
                        buffer[s * 4 + 3] = (byte)((sample >> 8) & 0xFF);
                    }
                    line.write(buffer, 0, buffer.length);
                }
                
                line.drain();
                line.stop();
                line.close();
                
                System.out.println("âœ Tone finished");
                System.out.println("\n>>> DID YOU HEAR IT? <<<");
                System.out.println("If YES, this is your audio output device!");
                System.out.println("Use index: " + i);
                System.out.println("Or name: \"" + name + "\"");
                
                // Pause between tests
                Thread.sleep(1000);
                
            } catch (Exception e) {
                System.out.println("âœ— Error: " + e.getMessage());
            }
        }
        
        System.out.println("\n\n" + "=".repeat(70));
        System.out.println("TESTING COMPLETE");
        System.out.println("=".repeat(70));
        System.out.println("\nWhich device number made sound?");
        System.out.println("That's the one you need to use in your code!");
    }
}
