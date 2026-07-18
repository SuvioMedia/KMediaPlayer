import java.awt.Canvas;
import java.awt.Frame;
import java.awt.Toolkit;
import java.util.Arrays;
import javax.swing.SwingUtilities;

public final class JbrWaylandSurfaceSmokeTest {
    private static native long[] capture(Canvas component);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("native test library path is required");
        System.load(args[0]);

        String toolkit = Toolkit.getDefaultToolkit().getClass().getName();
        if (!toolkit.endsWith("WLToolkit")) {
            throw new AssertionError("Expected JBR WLToolkit, got " + toolkit);
        }

        Frame[] frame = new Frame[1];
        Canvas[] canvas = new Canvas[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new Frame("JBR Wayland surface smoke test");
            frame[0].setUndecorated(true);
            canvas[0] = new Canvas();
            canvas[0].setSize(320, 180);
            frame[0].add(canvas[0]);
            frame[0].pack();
            frame[0].setVisible(true);
        });

        try {
            long[] values = capture(canvas[0]);
            if (values == null || values.length != 17) {
                throw new AssertionError("JBR surface capture failed");
            }
            if (values[0] == 0 || values[1] == 0) {
                throw new AssertionError("Missing wl_display/wl_surface: " + Arrays.toString(values));
            }
            if (values[2] <= 0 || values[5] <= 0 || values[6] <= 0) {
                throw new AssertionError("Invalid output or geometry: " + Arrays.toString(values));
            }
            if (values[7] != 1 || (values[8] & 1) == 0 || values[9] != values[2]) {
                throw new AssertionError("Native color protocol query failed: " + Arrays.toString(values));
            }
            if (values[10] != 1) {
                throw new AssertionError("JBR surface refresh failed: " + Arrays.toString(values));
            }
            if (values[11] != 1 || values[12] == 0 || values[13] == 0) {
                throw new AssertionError("JBR subsurface pair is unavailable: " + Arrays.toString(values));
            }
            if (values[14] <= 0 || values[15] <= 0 || values[16] != 1) {
                throw new AssertionError("JBR overlay upload failed: " + Arrays.toString(values));
            }
            System.out.println("JBR_WAYLAND_SMOKE_OK " + Arrays.toString(values));
        } finally {
            SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        }
    }
}
