package arc;

import javax.swing.*;

public final class Two1AlignedTmrPlotFrame {
    private Two1AlignedTmrPlotFrame() {}

    public static JFrame show(String title, Two1AlignedTmrPlotPanel panel) {
        JFrame f = new JFrame(title);
        f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        f.setContentPane(panel);
        f.pack();
        f.setLocationByPlatform(true);
        f.setVisible(true);
        return f;
    }
}
