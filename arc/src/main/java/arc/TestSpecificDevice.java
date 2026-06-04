package arc;

import javax.sound.sampled.*;

public class TestSpecificDevice {
    public static void main(String[] args) {
        // Test Device 0 specifically (UACDemoV10 USB audio)
        testDevice(0);
    }
    
    private static void testDevice(int deviceIndex) {
        System.out.println("\n=== Testing Device " + deviceIndex + " ===");
        
        try {
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            if (deviceIndex >= mixerInfos.length) {
                System.out.println("Device " + deviceIndex + " does not exist");
                return;
            }
            
            Mixer.Info mixerInfo = mixerInfos[deviceIndex];
            System.out.println("Device: " + mixerInfo.getName());
            System.out.println("Description: " + mixerInfo.getDescription());
            
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            
            SourceDataLine line = (SourceDataLine) mixer.getLine(info);
            line.open(format);
            line.start();
            
            System.out.println("Playing 440Hz tone for 2 seconds...");
            
            byte[] buffer = new byte[4096];
            for (int bufNum = 0; bufNum < 100; bufNum++) {
                for (int i = 0; i < buffer.length / 2; i++) {
                    double time = (bufNum * buffer.length / 2 + i) / 44100.0;
                    double value = Math.sin(2 * Math.PI * 440 * time);
                    short sample = (short)(value * Short.MAX_VALUE * 0.8);
                    buffer[2 * i] = (byte)(sample & 0xFF);
                    buffer[2 * i + 1] = (byte)((sample >> 8) & 0xFF);
                }
                line.write(buffer, 0, buffer.length);
            }
            
            line.drain();
            line.stop();
            line.close();
            
            System.out.println("✓ Test tone complete!");
            System.out.println("\nDid you hear the tone? (Y/N)");
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}