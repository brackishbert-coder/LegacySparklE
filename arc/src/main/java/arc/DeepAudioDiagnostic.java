package arc;

import javax.sound.sampled.*;

public class DeepAudioDiagnostic {
    public static void main(String[] args) {
        System.out.println("=== DEEP AUDIO DIAGNOSTIC ===\n");
        
        // Step 1: Check Java Sound System
        System.out.println("STEP 1: Java Sound System Check");
        System.out.println("-".repeat(50));
        try {
            System.out.println("Java version: " + System.getProperty("java.version"));
            System.out.println("OS: " + System.getProperty("os.name"));
            System.out.println("OS version: " + System.getProperty("os.version"));
            System.out.println("Architecture: " + System.getProperty("os.arch"));
        } catch (Exception e) {
            System.out.println("Could not read system properties: " + e.getMessage());
        }
        System.out.println();
        
        // Step 2: List mixers with detailed info
        System.out.println("STEP 2: Available Audio Mixers");
        System.out.println("-".repeat(50));
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        System.out.println("Found " + mixerInfos.length + " mixers\n");
        
        if (mixerInfos.length == 0) {
            System.out.println("âœ— CRITICAL: No audio mixers found!");
            System.out.println("Your Java installation may not have audio support.");
            System.out.println("Try reinstalling Java or checking Java audio libraries.");
            return;
        }
        
        for (int i = 0; i < mixerInfos.length; i++) {
            System.out.println("Mixer " + i + ":");
            System.out.println("  Name: " + mixerInfos[i].getName());
            System.out.println("  Description: " + mixerInfos[i].getDescription());
            System.out.println("  Vendor: " + mixerInfos[i].getVendor());
            System.out.println();
        }
        
        // Step 3: Check default line support
        System.out.println("STEP 3: Default Audio Line Test");
        System.out.println("-".repeat(50));
        AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        
        System.out.println("Testing format: " + format);
        System.out.println("Line supported: " + AudioSystem.isLineSupported(info));
        
        if (!AudioSystem.isLineSupported(info)) {
            System.out.println("\nâœ— DEFAULT format not supported. Trying alternatives...\n");
            
            AudioFormat[] formats = {
                new AudioFormat(22050, 16, 1, true, false),
                new AudioFormat(48000, 16, 1, true, false),
                new AudioFormat(44100, 16, 2, true, false),
                new AudioFormat(44100, 8, 1, true, false),
            };
            
            for (AudioFormat fmt : formats) {
                info = new DataLine.Info(SourceDataLine.class, fmt);
                if (AudioSystem.isLineSupported(info)) {
                    System.out.println("âœ Found supported format: " + fmt);
                    format = fmt;
                    break;
                }
            }
        }
        
        // Step 4: Try to get and open a line
        System.out.println("\nSTEP 4: Opening Audio Line");
        System.out.println("-".repeat(50));
        try {
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            System.out.println("âœ Got line: " + line.getClass().getName());
            System.out.println("Line info: " + line.getLineInfo());
            
            line.open(format, 8192);
            System.out.println("âœ Line opened successfully!");
            System.out.println("Buffer size: " + line.getBufferSize());
            System.out.println("Format: " + line.getFormat());
            
            // Step 5: Start the line
            System.out.println("\nSTEP 5: Starting Audio Line");
            System.out.println("-".repeat(50));
            line.start();
            System.out.println("âœ Line started!");
            System.out.println("Line is active: " + line.isActive());
            System.out.println("Line is running: " + line.isRunning());
            
            // Step 6: Generate and write audio
            System.out.println("\nSTEP 6: Writing Audio Data");
            System.out.println("-".repeat(50));
            System.out.println("*** LISTEN NOW - Playing LOUD 440Hz tone for 3 seconds ***\n");
            
            int sampleRate = (int)format.getSampleRate();
            int channels = format.getChannels();
            byte[] buffer = new byte[8192];
            
            long startTime = System.currentTimeMillis();
            int totalBytesWritten = 0;
            int bufferCount = 0;
            
            while (System.currentTimeMillis() - startTime < 3000) {
                // Generate sine wave at maximum safe volume
                for (int i = 0; i < buffer.length / (2 * channels); i++) {
                    double time = (bufferCount * buffer.length / (2 * channels) + i) / (double)sampleRate;
                    double angle = 2.0 * Math.PI * 440.0 * time;
                    double value = Math.sin(angle);
                    
                    // VERY LOUD - 90% of max amplitude
                    short sample = (short)(value * Short.MAX_VALUE * 0.9);
                    
                    // Write for all channels
                    for (int ch = 0; ch < channels; ch++) {
                        int idx = (i * channels + ch) * 2;
                        buffer[idx] = (byte)(sample & 0xFF);
                        buffer[idx + 1] = (byte)((sample >> 8) & 0xFF);
                    }
                }
                
                int written = line.write(buffer, 0, buffer.length);
                totalBytesWritten += written;
                bufferCount++;
                
                if (bufferCount % 20 == 0) {
                    System.out.println("  Buffers: " + bufferCount + 
                                     " | Bytes written: " + totalBytesWritten +
                                     " | Available: " + line.available());
                }
            }
            
            System.out.println("\nFinishing...");
            line.drain();
            System.out.println("âœ Audio drained");
            
            line.stop();
            System.out.println("âœ Line stopped");
            
            line.close();
            System.out.println("âœ Line closed");
            
            System.out.println("\n" + "=".repeat(50));
            System.out.println("RESULTS:");
            System.out.println("  Total bytes written: " + totalBytesWritten);
            System.out.println("  Buffers written: " + bufferCount);
            System.out.println("  Expected bytes: " + (sampleRate * channels * 2 * 3)); // 3 seconds
            
            System.out.println("\n*** DID YOU HEAR A LOUD TONE? ***");
            System.out.println("\nIf NO:");
            System.out.println("  1. Check system volume is UP");
            System.out.println("  2. Check speakers/headphones are connected");
            System.out.println("  3. Check Java has audio permissions");
            System.out.println("  4. Try a different audio device/output");
            System.out.println("  5. Check OS audio settings (muted apps, etc.)");
            
            System.out.println("\nIf YES:");
            System.out.println("  Great! Java audio is working.");
            System.out.println("  Your MusicalAdultSOMAudioOut may have a different issue.");
            
        } catch (LineUnavailableException e) {
            System.out.println("\nâœ— CRITICAL: Could not get audio line!");
            System.out.println("Error: " + e.getMessage());
            System.out.println("\nPossible causes:");
            System.out.println("  - Audio device in use by another application");
            System.out.println("  - No audio hardware available");
            System.out.println("  - Audio drivers not installed");
            System.out.println("  - Java doesn't have permission to access audio");
            e.printStackTrace();
        } catch (Exception e) {
            System.out.println("\nâœ— ERROR: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Step 7: OS-specific troubleshooting
        System.out.println("\n" + "=".repeat(50));
        System.out.println("OS-SPECIFIC TROUBLESHOOTING:");
        System.out.println("-".repeat(50));
        
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            System.out.println("macOS detected:");
            System.out.println("  - Check System Preferences > Security & Privacy > Microphone");
            System.out.println("  - Check System Preferences > Sound > Output");
            System.out.println("  - Try: sudo killall coreaudiod (restart audio)");
        } else if (os.contains("win")) {
            System.out.println("Windows detected:");
            System.out.println("  - Check Sound settings > App volume and device preferences");
            System.out.println("  - Make sure Java isn't muted in Volume Mixer");
            System.out.println("  - Check default playback device");
        } else if (os.contains("nix") || os.contains("nux")) {
            System.out.println("Linux detected:");
            System.out.println("  - Check: aplay -l (list devices)");
            System.out.println("  - Check: pactl list sinks (PulseAudio)");
            System.out.println("  - Try: pulseaudio --kill && pulseaudio --start");
            System.out.println("  - Check ALSA mixer: alsamixer");
        }
        
        System.out.println("\n=== DIAGNOSTIC COMPLETE ===");
    }
}
