package arc;

import java.util.Random;

final class EvolvingMemory {
    private final AnonymizedQueue<BabySOM> queue;
    private final AdultSOM adult;
    private final Random rng;

    public EvolvingMemory(Random rng) {
        // Queue capacity from config
        this.queue = new AnonymizedQueue<>(ARCConfig.QUEUE_CAPACITY, rng);
        
        // Adult SOM with perceptrons
        // Second param (dim) is now ignored, but we pass something for compatibility
     // Replace your adult construction block in EvolvingMemory with:

        this.adult = new AdultSOM(
            ARCConfig.ADULT_W,
            ARCConfig.ADULT_H,
            ARCConfig.ADULT_DIM,
            rng
        );

        
        this.rng = rng;
    }

    public void process(double[] x) {
        // Create BabySOM using config parameters
        BabySOM baby = new BabySOM(
            ARCConfig.BABY_NODES, 
            ARCConfig.BABY_INPUT_DIM, 
            rng
        );

        // Train using config epochs
        for (int epoch = 0; epoch < ARCConfig.BABY_EPOCHS; epoch++) {
            baby.train(x);
        }

        // Enqueue new baby
        BabySOM evicted = queue.enqueue(baby);
        
        // If a baby was evicted, it teaches the adult
        if (evicted != null) {
            double[] babyFlatMap = evicted.getFullMapFlat();
            adult.learnFromBaby(babyFlatMap);
        }
    }

    public AdultSOM adultMap() { 
        return adult; 
    }
}