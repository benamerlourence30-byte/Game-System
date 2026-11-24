import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.AudioSystem;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;


public class MemoryFogGame {
    private static final int MAX_LEVEL = 50;
    private static final int VARIANTS_PER_IMAGE = 9;
    private static final Color BACKGROUND = new Color(22, 28, 48);
    private static final Color PANEL = new Color(35, 44, 70);
    private static final Color ACCENT = new Color(111, 190, 255);
    // UI kit palette (navy ground, yellow / teal / pink accents)
    private static final Color NAVY = new Color(18, 20, 43);
    private static final Color PANEL_DARK = new Color(30, 34, 80);
    private static final Color KIT_YELLOW = new Color(255, 201, 60);
    private static final Color KIT_TEAL = new Color(46, 230, 197);
    private static final Color KIT_PINK = new Color(214, 36, 110);
    private static final Color KIT_PURPLE = new Color(91, 63, 208);
    /** Image files found in the assets folder; drop in another supported image and relaunch to add it. */
    private static final String[] CARD_IMAGES = discoverCardImages();
    private static final String[] THEME_NAMES = {
        "MIDNIGHT", "TIDAL", "VIOLET", "FOREST", "SUNSET",
        "GLACIER", "EMBER", "DREAM", "COSMOS", "AURORA"
    };
    private static final Color[] BOARD_THEMES = {
        new Color(35, 44, 70), new Color(31, 62, 79), new Color(52, 43, 82),
        new Color(37, 68, 61), new Color(83, 53, 58), new Color(42, 68, 86),
        new Color(85, 52, 44), new Color(58, 49, 79), new Color(32, 51, 85),
        new Color(36, 76, 78)
    };
    private static final String[] CARD_BACK_NAMES = {"Level theme", "Amethyst", "Lagoon", "Rose gold"};
    private static final Color[] CARD_BACK_SKINS = {
        new Color(73, 99, 151), new Color(117, 77, 165),
        new Color(43, 139, 151), new Color(173, 91, 111)
    };
    private static final Color[] THEME_CARD_BACKS = {
        new Color(73, 99, 151), new Color(45, 116, 140), new Color(104, 78, 151),
        new Color(58, 119, 91), new Color(157, 87, 78), new Color(68, 122, 161),
        new Color(164, 91, 60), new Color(112, 82, 155), new Color(65, 88, 166),
        new Color(46, 137, 129)
    };
    private static final int[] CARD_BACK_PRICES = {0, 150, 200, 250};
    /** Coins earned for clearing a level. Change this one number to tune the economy. */
    private static final int LEVEL_REWARD = 50;
    private static final File ACCOUNT_DIRECTORY = new File(System.getProperty("user.dir"), "memoryfog-accounts");
    private static final int PASSWORD_ITERATIONS = 120_000;
    private static final int PASSWORD_BITS = 256;
    private static final Map<String, BufferedImage> IMAGE_CACHE = new HashMap<>();

    // ------------------------------------------------------------------
    // Small shared animation helpers
    // ------------------------------------------------------------------

    /** Overshooting ease used for pop-in animations. */
    private static double easeOutBack(double t) {
        double c1 = 1.70158, c3 = c1 + 1.0;
        double x = t - 1.0;
        return 1.0 + c3 * x * x * x + c1 * x * x;
    }

    private static Color mix(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(
            Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
            Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** Gives any button the glossy, animated look: hover glow, press-down, and pop-in support. */
    private static void installFancy(AbstractButton b) {
        b.setUI(new FancyButtonUI());
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setRolloverEnabled(true);
        b.setBorder(new EmptyBorder(8, 14, 8, 14));
        b.putClientProperty("hover", 0f);
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (b.isEnabled()) {
                    animateHover(b, 1f);
                    playHoverTick();
                }
            }
            @Override public void mouseExited(MouseEvent e) { animateHover(b, 0f); }
            @Override public void mousePressed(MouseEvent e) { if (b.isEnabled()) playUiClickSound(); }
        });
    }

    private static void animateHover(AbstractButton b, float target) {
        Object old = b.getClientProperty("hoverTimer");
        if (old instanceof javax.swing.Timer) ((javax.swing.Timer) old).stop();
        javax.swing.Timer t = new javax.swing.Timer(16, null);
        t.addActionListener(e -> {
            Object cur = b.getClientProperty("hover");
            float v = cur instanceof Float ? (Float) cur : 0f;
            v += (target - v) * 0.3f;
            if (Math.abs(target - v) < 0.02f) { v = target; t.stop(); }
            b.putClientProperty("hover", v);
            b.repaint();
        });
        b.putClientProperty("hoverTimer", t);
        t.start();
    }

    /** Colors a button as one of the kit variants: primary, secondary, success, danger, disabled. */
    private static void styleAs(AbstractButton b, String variant) {
        Color bg, fg, depth;
        switch (variant) {
            case "primary": bg = KIT_YELLOW; fg = new Color(26, 16, 51); depth = new Color(184, 134, 26); break;
            case "success": bg = KIT_TEAL; fg = new Color(11, 31, 42); depth = new Color(18, 156, 133); break;
            case "danger": bg = KIT_PINK; fg = Color.WHITE; depth = new Color(143, 22, 72); break;
            case "disabled": bg = new Color(52, 58, 107); fg = new Color(160, 165, 207); depth = new Color(30, 34, 72); break;
            default: bg = new Color(42, 47, 92); fg = Color.WHITE; depth = new Color(22, 26, 58); break;
        }
        b.setBackground(bg);
        b.setForeground(fg);
        b.putClientProperty("depth", depth);
    }

    /** Paints the kit's chunky buttons: flat face, solid bottom edge, press-down. Reads "hover", "pop", "pulse", "depth", "outline". */
    private static class FancyButtonUI extends javax.swing.plaf.basic.BasicButtonUI {
        @Override public void paint(Graphics graphics, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = c.getWidth(), h = c.getHeight();
            Object hv = b.getClientProperty("hover");
            float hover = hv instanceof Float ? (Float) hv : 0f;
            Object pp = b.getClientProperty("pop");
            float pop = pp instanceof Float ? (Float) pp : 1f;
            Object pl = b.getClientProperty("pulse");
            float pulse = pl instanceof Float ? (Float) pl : 0f;
            boolean enabled = b.isEnabled();
            boolean pressed = b.getModel().isPressed() && enabled;

            if (pop < 1f) {
                double sc = Math.max(0.02, easeOutBack(pop));
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, pop * 2.5f)));
                g.translate(w * (1.0 - sc) / 2.0, h * (1.0 - sc) / 2.0);
                g.scale(sc, sc);
            }

            int arc = 16, depth = 4;
            int lift = pressed ? depth : 0;
            int faceH = Math.max(1, h - depth);
            Color base = b.getBackground();
            Object dp = b.getClientProperty("depth");
            Color depthColor = dp instanceof Color ? (Color) dp : mix(base, Color.BLACK, 0.45f);
            if (!pressed) {
                g.setColor(depthColor);
                g.fillRoundRect(0, depth, w, faceH, arc, arc);
            }
            g.setColor(mix(base, Color.WHITE, 0.14f * hover));
            g.fillRoundRect(0, lift, w, faceH, arc, arc);
            Object ol = b.getClientProperty("outline");
            if (ol instanceof Color) {
                Color o = (Color) ol;
                g.setStroke(new BasicStroke(2f));
                g.setColor(new Color(o.getRed(), o.getGreen(), o.getBlue(), Math.min(255, (int) (190 + 65 * hover))));
                g.drawRoundRect(1, lift + 1, w - 3, faceH - 3, arc, arc);
            }
            if (pulse > 0f) {
                g.setStroke(new BasicStroke(2.6f));
                g.setColor(new Color(255, 226, 130, (int) (40 + 190 * pulse)));
                g.drawRoundRect(2, lift + 2, w - 5, faceH - 5, arc - 3, arc - 3);
            }
            g.translate(0, lift - depth / 2.0);
            super.paint(g, c);
            g.dispose();
        }
    }

    /** Draws a picture centered in a box. The whole picture is shown (nothing is cut off) unless it is almost exactly the box's shape. */
    private static void drawImageFit(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
        double contain = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
        double cover = Math.max((double) w / img.getWidth(), (double) h / img.getHeight());
        double scale = cover / contain <= 1.06 ? cover : contain;
        int dw = (int) Math.round(img.getWidth() * scale);
        int dh = (int) Math.round(img.getHeight() * scale);
        g.drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
    }

    /**
     * Lays the cards out in the roomiest grid that keeps every card the same portrait shape,
     * so cards shrink evenly as their number grows instead of stretching into wide rectangles.
     * The whole grid (and a partly filled last row) is centered in the board.
     */
    private static class CardGridLayout implements LayoutManager {
        private static final double ASPECT = 0.75;   // card width / card height
        private static final double MAX_CARD_WIDTH = 170;
        private final int gap;

        CardGridLayout(int gap) { this.gap = gap; }

        @Override public void addLayoutComponent(String name, Component comp) { }
        @Override public void removeLayoutComponent(Component comp) { }

        @Override public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            return new Dimension(in.left + in.right + 200, in.top + in.bottom + 200);
        }

        @Override public Dimension minimumLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            return new Dimension(in.left + in.right + 100, in.top + in.bottom + 100);
        }

        @Override public void layoutContainer(Container parent) {
            int n = parent.getComponentCount();
            if (n == 0) return;
            Insets in = parent.getInsets();
            int availW = parent.getWidth() - in.left - in.right;
            int availH = parent.getHeight() - in.top - in.bottom;
            if (availW <= 0 || availH <= 0) return;

            double bestWidth = -1, bestScore = 0;
            int bestCols = 1;
            for (int cols = 1; cols <= n; cols++) {
                int rows = (n + cols - 1) / cols;
                double byWidth = (availW - (double) gap * (cols - 1)) / cols;
                double byHeight = ((availH - (double) gap * (rows - 1)) / rows) * ASPECT;
                double width = Math.min(Math.min(byWidth, byHeight), MAX_CARD_WIDTH);
                if (width <= 0) continue;
                double gridW = cols * width + gap * (cols - 1);
                double gridH = rows * (width / ASPECT) + gap * (rows - 1);
                double score = (cols * rows - n) * 10 + Math.abs(Math.log((gridW / gridH) / ((double) availW / availH)));
                if (width > bestWidth + 0.5 || (Math.abs(width - bestWidth) <= 0.5 && score < bestScore)) {
                    bestWidth = width;
                    bestScore = score;
                    bestCols = cols;
                }
            }
            if (bestWidth <= 0) bestWidth = 20;
            int cols = bestCols;
            int rows = (n + cols - 1) / cols;
            int cw = (int) Math.floor(bestWidth);
            int ch = (int) Math.round(bestWidth / ASPECT);
            int gridH = rows * ch + gap * (rows - 1);
            int y0 = in.top + (availH - gridH) / 2;
            for (int i = 0; i < n; i++) {
                int r = i / cols, c = i % cols;
                int inRow = (r == rows - 1) ? n - r * cols : cols;
                int rowW = inRow * cw + gap * (inRow - 1);
                int x0 = in.left + (availW - rowW) / 2;
                parent.getComponent(i).setBounds(x0 + c * (cw + gap), y0 + r * (ch + gap), cw, ch);
            }
        }
    }

    /** Draws a face-up card (frame, centered picture, letter badge) with its top-left corner at the origin. */
    private static void paintFaceCard(Graphics2D g0, int w, int h, BufferedImage img, String badge, Color frame) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double u = Math.max(0.55, Math.min(2.2, Math.min(w, h * 0.75) / 90.0));
        int arc = Math.max(10, (int) Math.round(22 * u));
        float frameW = (float) Math.max(2.0, 3.5 * u);
        g.setColor(new Color(5, 9, 24, 120));
        g.fillRoundRect(3, 6, w - 6, h - 8, arc, arc);
        g.setColor(PANEL_DARK);
        g.fillRoundRect(2, 2, w - 6, h - 7, arc, arc);
        int pad = Math.max(4, (int) Math.round(9 * u));
        int ix = pad, iy = pad, iw = Math.max(1, w - 2 * pad - 4), ih = Math.max(1, h - 2 * pad - 5);
        int innerArc = Math.max(6, arc - 8);
        Shape oldClip = g.getClip();
        g.clip(new RoundRectangle2D.Float(ix, iy, iw, ih, innerArc, innerArc));
        g.setColor(KIT_PURPLE);
        g.fillRect(ix, iy, iw, ih);
        if (img != null) drawImageFit(g, img, ix, iy, iw, ih);
        g.setClip(oldClip);
        int bw = Math.max(14, (int) Math.round(20 * u)), bh = Math.max(11, (int) Math.round(16 * u));
        int bx = ix + iw - bw - 3, by = iy + 3;
        g.setColor(KIT_YELLOW);
        g.fillRoundRect(bx, by, bw, bh, bh / 2 + 1, bh / 2 + 1);
        g.setColor(new Color(26, 16, 51));
        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(8, (int) Math.round(10 * u))));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(badge, bx + (bw - fm.stringWidth(badge)) / 2, by + (bh + fm.getAscent() - fm.getDescent()) / 2);
        g.setColor(frame);
        g.setStroke(new BasicStroke(frameW));
        g.drawRoundRect(3, 3, w - 8, h - 9, arc - 2, arc - 2);
        g.dispose();
    }

    /** A laughing emoji-style face centered on the origin; {@code t} (0..1) animates the tears. */
    private static void paintLaughFace(Graphics2D g0, double size, double t) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double r = size / 2;
        java.awt.geom.Ellipse2D.Double head = new java.awt.geom.Ellipse2D.Double(-r, -r, size, size);
        g.setPaint(new GradientPaint(0f, (float) -r, new Color(255, 232, 100), 0f, (float) r, new Color(255, 176, 30)));
        g.fill(head);
        g.setColor(new Color(170, 100, 10));
        g.setStroke(new BasicStroke((float) Math.max(2.0, size * 0.045)));
        g.draw(head);
        // squeezed "> <" eyes
        g.setColor(new Color(90, 50, 10));
        g.setStroke(new BasicStroke((float) Math.max(2.0, size * 0.07), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double ex = size * 0.27, ey = -size * 0.12, es = size * 0.11;
        g.draw(new java.awt.geom.Line2D.Double(-ex - es, ey - es, -ex + es, ey));
        g.draw(new java.awt.geom.Line2D.Double(-ex + es, ey, -ex - es, ey + es));
        g.draw(new java.awt.geom.Line2D.Double(ex + es, ey - es, ex - es, ey));
        g.draw(new java.awt.geom.Line2D.Double(ex - es, ey, ex + es, ey + es));
        // wide-open laughing mouth with teeth and tongue
        double mw = size * 0.62, mh = size * 0.5, mx = -mw / 2, my = size * 0.04;
        java.awt.geom.Arc2D.Double mouth = new java.awt.geom.Arc2D.Double(mx, my - mh, mw, mh * 2, 180, 180, java.awt.geom.Arc2D.CHORD);
        g.setColor(new Color(120, 20, 34));
        g.fill(mouth);
        Shape oldClip = g.getClip();
        g.clip(mouth);
        g.setColor(Color.WHITE);
        g.fill(new java.awt.geom.Rectangle2D.Double(mx, my, mw, mh * 0.28));
        g.setColor(new Color(255, 110, 130));
        g.fill(new java.awt.geom.Ellipse2D.Double(-mw * 0.28, my + mh * 0.55, mw * 0.56, mh * 0.8));
        g.setClip(oldClip);
        g.setColor(new Color(90, 50, 10));
        g.setStroke(new BasicStroke((float) Math.max(1.5, size * 0.03)));
        g.draw(mouth);
        // tears of laughter
        double wiggle = Math.sin(t * 24) * size * 0.03;
        for (int side = -1; side <= 1; side += 2) {
            double tx = side * (r + size * 0.04 + t * size * 0.1) + wiggle;
            double ty = ey + size * 0.12 + t * size * 0.12;
            g.setColor(new Color(110, 200, 255));
            g.fill(new java.awt.geom.Ellipse2D.Double(tx - size * 0.06, ty - size * 0.06, size * 0.12, size * 0.15));
            g.setColor(new Color(255, 255, 255, 170));
            g.fill(new java.awt.geom.Ellipse2D.Double(tx - size * 0.025, ty - size * 0.04, size * 0.03, size * 0.05));
        }
        g.dispose();
    }

    /** A card button that can flip, spin, and fade using Swing's repaint loop. */
    private static class AnimatedCardButton extends JButton {
        private double flipProgress = 1.0;
        private double matchProgress = -1.0;
        private BufferedImage cardImage;
        private String variantLabel = "A";
        private boolean hintVisible;
        private boolean xrayVisible;
        private boolean distractionVisible;
        private boolean chainedLocked;
        private double xrayProgress = 1.0;
        private double hover = 0.0;
        private double dealProgress = 1.0;
        private double shakeProgress = -1.0;
        private javax.swing.Timer hoverTimer;
        private Color frameColor = KIT_YELLOW;
        private double laughProgress = -1.0;

        void setFrameColor(Color color) {
            frameColor = color;
            repaint();
        }

        AnimatedCardButton() {
            super("?");
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { animateHover(1.0); }
                @Override public void mouseExited(MouseEvent e) { animateHover(0.0); }
            });
        }

        void animateHover(double target) {
            if (hoverTimer != null) hoverTimer.stop();
            hoverTimer = new javax.swing.Timer(16, null);
            hoverTimer.addActionListener(e -> {
                hover += (target - hover) * 0.3;
                if (Math.abs(target - hover) < 0.02) {
                    hover = target;
                    ((javax.swing.Timer) e.getSource()).stop();
                }
                repaint();
            });
            hoverTimer.start();
        }

        /** Cards pop onto the board one after another when a level begins. */
        void playDealIn(int delayMs) {
            dealProgress = 0.0;
            repaint();
            javax.swing.Timer starter = new javax.swing.Timer(Math.max(1, delayMs), e ->
                runAnimation(380, p -> {
                    dealProgress = p;
                    repaint();
                }, null));
            starter.setRepeats(false);
            starter.start();
        }

        /** Quick red shake for a wrong guess. */
        void playShake() {
            shakeProgress = 0.0;
            runAnimation(420, p -> {
                shakeProgress = p >= 1.0 ? -1.0 : p;
                repaint();
            }, null);
        }

        void showImage(BufferedImage image, String variant) {
            cardImage = image;
            variantLabel = variant;
            xrayVisible = false;
            distractionVisible = false;
            putClientProperty("imageName", null);
            setText(image == null ? "?" : "");
            repaint();
        }

        void showImage(BufferedImage image, String imageName, String variant) {
            cardImage = image;
            variantLabel = variant;
            xrayVisible = false;
            distractionVisible = false;
            putClientProperty("imageName", imageName);
            setText(image == null ? "?" : "");
            repaint();
        }

        void showXray(BufferedImage image, String imageName, String variant) {
            cardImage = image;
            variantLabel = variant;
            xrayVisible = true;
            putClientProperty("imageName", imageName);
            setText(image == null ? "?" : "");
            xrayProgress = 0.0;
            runAnimation(420, progress -> {
                xrayProgress = progress;
                repaint();
            }, null);
        }

        void showDistraction(BufferedImage image, String imageName) {
            cardImage = image;
            variantLabel = "!";
            distractionVisible = true;
            xrayVisible = false;
            putClientProperty("imageName", imageName);
            setText(image == null ? "?" : "");
            xrayProgress = 0.0;
            runAnimation(380, progress -> {
                xrayProgress = progress;
                repaint();
            }, null);
        }

        void hideDistraction() {
            distractionVisible = false;
            cardImage = null;
            setText("?");
            repaint();
        }

        boolean isDistractionVisible() { return distractionVisible; }

        void setHintVisible(boolean visible) {
            hintVisible = visible;
            repaint();
        }

        void setChainedLocked(boolean locked) {
            chainedLocked = locked;
            repaint();
        }

        boolean isXrayVisible() { return xrayVisible; }

        void playFlip() {
            flipProgress = 0.0;
            runAnimation(220, progress -> {
                flipProgress = progress;
                repaint();
            }, null);
        }

        void playMatch(Runnable finished) {
            matchProgress = 0.0;
            runAnimation(650, progress -> {
                matchProgress = progress;
                repaint();
            }, finished);
        }

        /** A laughing face pops up on the card, wobbles and bounces, then fades. */
        void playLaugh() {
            laughProgress = 0.0;
            runAnimation(1700, p -> {
                laughProgress = p >= 1.0 ? -1.0 : p;
                repaint();
            }, null);
        }

        private void paintLaugh(Graphics2D g0, int w, int h) {
            Graphics2D g = (Graphics2D) g0.create();
            double p = laughProgress;
            double fade = p > 0.8 ? Math.max(0.0, 1.0 - (p - 0.8) / 0.2) : 1.0;
            double size = Math.min(w, h * 0.75) * 0.78 * Math.max(0.02, easeOutBack(Math.min(1.0, p / 0.18)));
            double wobble = Math.sin(p * 40) * 0.22 * (1.0 - p * 0.5);
            double bounce = Math.abs(Math.sin(p * 18)) * size * 0.08;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) fade));
            g.translate(w / 2.0 - 2, h / 2.0 - 3 - bounce);
            g.rotate(wobble);
            paintLaughFace(g, size, p);
            g.dispose();
        }

        private void runAnimation(int duration, java.util.function.DoubleConsumer update, Runnable finished) {
            final long started = System.nanoTime();
            javax.swing.Timer animation = new javax.swing.Timer(16, null);
            animation.addActionListener(event -> {
                double progress = Math.min(1.0, (System.nanoTime() - started) / (duration * 1_000_000.0));
                update.accept(progress);
                if (progress >= 1.0) {
                    animation.stop();
                    if (finished != null) finished.run();
                }
            });
            animation.start();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            if (hover > 0.001 && matchProgress < 0.0 && dealProgress >= 1.0) {
                double hs = 1.0 + 0.035 * hover;
                g.translate(w * (1.0 - hs) / 2.0, h * (1.0 - hs) / 2.0 - 1.5 * hover);
                g.scale(hs, hs);
            }
            if (shakeProgress >= 0.0) {
                g.translate(Math.sin(shakeProgress * Math.PI * 7) * 7 * (1.0 - shakeProgress), 0);
            }
            if (dealProgress < 1.0) {
                double ds = Math.max(0.02, easeOutBack(dealProgress));
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                    (float) Math.min(1.0, dealProgress * 2.5)));
                g.translate(w * (1.0 - ds) / 2.0, h * (1.0 - ds) / 2.0 + (1.0 - dealProgress) * 18);
                g.scale(ds, ds);
            } else if (matchProgress >= 0.0) {
                double p = matchProgress;
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float)(1.0 - p)));
                g.rotate(Math.PI * 2.0 * p, w / 2.0, h / 2.0);
                double scale = 1.0 - 0.85 * p;
                g.translate(w * (1.0 - scale) / 2.0, h * (1.0 - scale) / 2.0);
                g.scale(scale, scale);
            } else if (flipProgress < 1.0) {
                double widthScale = Math.max(0.06, Math.abs(Math.cos(Math.PI * flipProgress)));
                g.translate(w * (1.0 - widthScale) / 2.0, 0);
                g.scale(widthScale, 1.0);
            }

            // Kit style: chunky rounded cards with a bold frame and a solid drop shadow.
            // Every measurement scales with the card size so small cards stay tidy.
            double u = Math.max(0.55, Math.min(1.5, Math.min(w, h * 0.75) / 90.0));
            int arc = Math.max(10, (int) Math.round(22 * u));
            float frameW = (float) Math.max(2.0, 3.5 * u);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(new Color(5, 9, 24, 120));
            g.fillRoundRect(3, 6, w - 6, h - 8, arc, arc);
            Color base = getBackground();
            boolean faceDown = "?".equals(getText()) && !xrayVisible && !hintVisible && !distractionVisible;
            if (faceDown) {
                Shape oldBackClip = g.getClip();
                g.clip(new RoundRectangle2D.Float(2, 2, w - 6, h - 7, arc, arc));
                g.setColor(mix(base, Color.BLACK, 0.30f));
                g.fillRect(0, 0, w, h);
                g.setColor(mix(base, Color.BLACK, 0.52f));
                int stripe = Math.max(8, (int) Math.round(10 * u));
                for (int k = -h; k < w + h; k += stripe * 2) {
                    g.fillPolygon(new int[]{k, k + stripe, k + stripe - h, k - h}, new int[]{0, 0, h, h}, 4);
                }
                g.setClip(oldBackClip);
                g.setColor(KIT_PINK);
                g.setStroke(new BasicStroke(frameW * 0.85f));
                g.drawRoundRect(3, 3, w - 8, h - 9, arc - 2, arc - 2);
                g.setStroke(new BasicStroke(1f));
                // Yellow question mark, sized to the card and centered.
                g.setColor(KIT_YELLOW);
                g.setFont(new Font("SansSerif", Font.BOLD, Math.max(14, (int) Math.round(Math.min(w, h * 0.75) * 0.5))));
                FontMetrics qfm = g.getFontMetrics();
                g.drawString("?", (w - 4 - qfm.stringWidth("?")) / 2,
                    (h - 5 + qfm.getAscent() - qfm.getDescent()) / 2);
                // Occasional shine sweeping across face-down cards.
                double sweepT = (System.nanoTime() / 1e9 + getX() * 0.0025 + getY() * 0.0035) % 5.0;
                if (sweepT < 0.8) {
                    double sp = sweepT / 0.8;
                    int sx = (int) (-24 + sp * (w + 48));
                    Shape sweepClip = g.getClip();
                    g.clip(new RoundRectangle2D.Float(2, 2, w - 6, h - 7, arc, arc));
                    g.setColor(new Color(255, 255, 255, 46));
                    g.fillPolygon(new int[]{sx, sx + 16, sx - 8, sx - 24}, new int[]{0, 0, h, h}, 4);
                    g.setClip(sweepClip);
                }
            } else {
                g.setColor(mix(PANEL_DARK, base, 0.35f));
                g.fillRoundRect(2, 2, w - 6, h - 7, arc, arc);
                if (cardImage != null) {
                    int pad = Math.max(4, (int) Math.round(9 * u));
                    int ix = pad, iy = pad, iw = Math.max(1, w - 2 * pad - 4), ih = Math.max(1, h - 2 * pad - 5);
                    int innerArc = Math.max(6, arc - 8);
                    Shape oldClip = g.getClip();
                    g.clip(new RoundRectangle2D.Float(ix, iy, iw, ih, innerArc, innerArc));
                    g.setColor(KIT_PURPLE);
                    g.fillRect(ix, iy, iw, ih);
                    drawImageFit(g, cardImage, ix, iy, iw, ih);
                    g.setClip(oldClip);
                    int bw = Math.max(14, (int) Math.round(20 * u)), bh = Math.max(11, (int) Math.round(16 * u));
                    int bx = ix + iw - bw - 3, by = iy + 3;
                    g.setColor(KIT_YELLOW);
                    g.fillRoundRect(bx, by, bw, bh, bh / 2 + 1, bh / 2 + 1);
                    g.setColor(new Color(26, 16, 51));
                    g.setFont(new Font("SansSerif", Font.BOLD, Math.max(8, (int) Math.round(10 * u))));
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(variantLabel, bx + (bw - fm.stringWidth(variantLabel)) / 2,
                        by + (bh + fm.getAscent() - fm.getDescent()) / 2);
                } else {
                    g.setColor(new Color(255, 255, 255, 55));
                    g.fillRoundRect(8, 8, w - 20, 5, 5, 5);
                }
                g.setColor(frameColor);
                g.setStroke(new BasicStroke(frameW));
                g.drawRoundRect(3, 3, w - 8, h - 9, arc - 2, arc - 2);
                g.setStroke(new BasicStroke(1f));
            }
            super.paintComponent(g);
            if (!faceDown && hintVisible) {
                g.setColor(new Color(255, 211, 83));
                g.fillOval(7, 7, 25, 25);
                g.setColor(new Color(40, 45, 65));
                g.setFont(new Font("SansSerif", Font.BOLD, 16));
                g.drawString("✦", 12, 25);
            }
            if (!faceDown && xrayVisible) {
                int pulse = (int) (5 * (1.0 - xrayProgress));
                g.setColor(new Color(132, 239, 255, 210));
                g.setStroke(new BasicStroke(2.5f));
                g.drawOval(5 - pulse, 5 - pulse, 24 + pulse * 2, 24 + pulse * 2);
                g.drawLine(24, 24, 34, 34);
                g.setStroke(new BasicStroke(1f));
            }
            if (!faceDown && distractionVisible) {
                int flashAlpha = 130 + (int) (90 * Math.abs(Math.sin(xrayProgress * Math.PI * 3)));
                g.setColor(new Color(255, 75, 105, flashAlpha));
                g.setStroke(new BasicStroke(4f));
                g.drawRoundRect(4, 4, w - 10, h - 11, 20, 20);
                g.setColor(new Color(160, 30, 55, 225));
                g.fillRoundRect(7, 7, 20, 20, 7, 7);
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 15));
                g.drawString("!", 14, 23);
                g.setStroke(new BasicStroke(1f));
            }
            if (hover > 0.02 && matchProgress < 0.0) {
                g.setColor(new Color(255, 255, 255, (int) (120 * hover)));
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(3, 3, w - 8, h - 9, arc - 2, arc - 2);
                g.setStroke(new BasicStroke(1f));
            }
            if (shakeProgress >= 0.0) {
                g.setColor(new Color(255, 70, 90, (int) (120 * (1.0 - shakeProgress))));
                g.fillRoundRect(2, 2, w - 6, h - 7, arc, arc);
            }
            if (laughProgress >= 0.0) paintLaugh(g, w, h);
            if (chainedLocked && matchProgress < 0.0) paintChainLock(g, w, h, u);
            g.dispose();
        }

        private void paintChainLock(Graphics2D g0, int w, int h, double scale) {
            Graphics2D g = (Graphics2D) g0.create();
            int arc = Math.max(10, (int) (18 * scale));
            g.setColor(new Color(8, 12, 29, 125));
            g.fillRoundRect(5, 5, w - 10, h - 12, arc, arc);

            Graphics2D chain = (Graphics2D) g.create();
            chain.setColor(new Color(255, 201, 60, 235));
            chain.setStroke(new BasicStroke(Math.max(3f, (float) (5 * scale)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            chain.drawLine((int) (w * 0.16), (int) (h * 0.25), (int) (w * 0.84), (int) (h * 0.75));
            chain.setStroke(new BasicStroke(Math.max(1.5f, (float) (2 * scale))));
            int linkW = Math.max(10, (int) (17 * scale));
            int linkH = Math.max(6, (int) (9 * scale));
            for (int i = 0; i < 6; i++) {
                double t = (i + 0.5) / 6.0;
                Graphics2D link = (Graphics2D) chain.create();
                link.translate(w * (0.16 + 0.68 * t), h * (0.25 + 0.5 * t));
                link.rotate(Math.atan2(h * 0.5, w * 0.68));
                link.setColor(new Color(18, 20, 43, 235));
                link.fillRoundRect(-linkW / 2, -linkH / 2, linkW, linkH, linkH, linkH);
                link.setColor(new Color(255, 218, 108));
                link.drawRoundRect(-linkW / 2, -linkH / 2, linkW, linkH, linkH, linkH);
                link.dispose();
            }
            chain.dispose();

            int bodyW = Math.max(23, (int) (34 * scale));
            int bodyH = Math.max(20, (int) (26 * scale));
            int cx = w / 2, cy = h / 2;
            g.setColor(new Color(15, 18, 39, 245));
            g.fillRoundRect(cx - bodyW / 2, cy - bodyH / 2 + 5, bodyW, bodyH, 7, 7);
            g.setColor(KIT_YELLOW);
            g.setStroke(new BasicStroke(Math.max(2f, (float) (3 * scale))));
            g.drawRoundRect(cx - bodyW / 2, cy - bodyH / 2 + 5, bodyW, bodyH, 7, 7);
            g.drawArc(cx - bodyW / 3, cy - bodyH / 2 - 9, bodyW * 2 / 3, bodyH, 180, 180);
            g.setStroke(new BasicStroke(1f));
            g.setColor(Color.WHITE);
            g.fillOval(cx - 2, cy + 4, 4, 5);
            g.dispose();
        }
    }

    /** Animated gradient backdrop with drifting bubbles and soft moving glows. */
    private static class AnimatedBackground extends JPanel {
        private final double[][] dots;
        private final long start = System.nanoTime();

        AnimatedBackground(LayoutManager layout, int count) {
            super(layout);
            setOpaque(true);
            Random r = new Random(7);
            dots = new double[count][5];
            for (int i = 0; i < count; i++) {
                dots[i][0] = r.nextDouble();
                dots[i][1] = r.nextDouble();
                dots[i][2] = 0.012 + r.nextDouble() * 0.03;
                dots[i][3] = 3 + r.nextDouble() * 9;
                dots[i][4] = r.nextDouble() * Math.PI * 2;
            }
            javax.swing.Timer timer = new javax.swing.Timer(33, e -> { if (isShowing()) repaint(); });
            timer.start();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            double time = (System.nanoTime() - start) / 1e9;
            g.setColor(NAVY);
            g.fillRect(0, 0, w, h);
            g.setPaint(new RadialGradientPaint(new Point(w / 2, (int) (h * 0.22)),
                Math.max(200f, Math.max(w, h) * 0.62f), new float[]{0f, 1f},
                new Color[]{new Color(59, 42, 122), new Color(59, 42, 122, 0)}));
            g.fillRect(0, 0, w, h);

            for (int k = 0; k < 2; k++) {
                double cx = w * (0.28 + 0.44 * k) + Math.sin(time * 0.25 + k * 2.1) * w * 0.12;
                double cy = h * (0.32 + 0.30 * k) + Math.cos(time * 0.21 + k) * h * 0.10;
                float radius = Math.max(120f, Math.min(w, h) * 0.55f);
                Color glow = k == 0 ? KIT_TEAL : KIT_PINK;
                g.setPaint(new RadialGradientPaint(new Point((int) cx, (int) cy), radius,
                    new float[]{0f, 1f},
                    new Color[]{new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 26),
                                new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 0)}));
                g.fillRect(0, 0, w, h);
            }

            for (double[] d : dots) {
                double y = (1.0 - ((d[1] + time * d[2]) % 1.0)) * h;
                double x = d[0] * w + Math.sin(time * 0.6 + d[4]) * 18;
                int a = (int) (26 + 34 * (Math.sin(time + d[4]) + 1) / 2);
                int size = (int) d[3];
                Color dc = (int) d[3] % 3 == 0 ? KIT_YELLOW : (int) d[3] % 3 == 1 ? KIT_TEAL : new Color(255, 120, 170);
                g.setColor(new Color(dc.getRed(), dc.getGreen(), dc.getBlue(), a));
                g.fillOval((int) x, (int) y, size, size);
                if (size > 8) {
                    g.setColor(new Color(dc.getRed(), dc.getGreen(), dc.getBlue(), a));
                    g.drawOval((int) x - 2, (int) y - 2, size + 4, size + 4);
                }
            }
            g.dispose();
        }
    }

    /** Stacked game logo (yellow SQUAD over white FLIP) that bobs and catches a moving shine. */
    private static class ShimmerLabel extends JComponent {
        private final String top, bottom;

        ShimmerLabel(String top, String bottom) {
            this.top = top;
            this.bottom = bottom;
            setOpaque(false);
            setPreferredSize(new Dimension(480, 140));
            setMaximumSize(new Dimension(600, 140));
        }

        private void line(Graphics2D g, String text, Color c, int baseline, double sweep) {
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(text);
            int x = (getWidth() - tw) / 2;
            g.setColor(new Color(0, 0, 0, 110));
            g.drawString(text, x + 3, baseline + 4);
            float sx = (float) (x - tw * 0.3 + sweep * tw * 1.6);
            g.setPaint(new LinearGradientPaint(sx - 90f, 0f, sx + 90f, 0f, new float[]{0f, 0.5f, 1f},
                new Color[]{c, mix(c, Color.WHITE, 0.8f), c}));
            g.drawString(text, x, baseline);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.BOLD, 58));
            double now = System.nanoTime() / 1e9;
            int bob = (int) (Math.sin(now * 2.0) * 3);
            double sweep = (now % 3.0) / 3.0;
            int mid = getHeight() / 2;
            line(g, top, KIT_YELLOW, mid - 4 + bob, sweep);
            line(g, bottom, Color.WHITE, mid + 54 + bob, (sweep + 0.2) % 1.0);
            g.dispose();
        }
    }

    /** A floating preview card for the main menu that bobs, tilts and flips on its own. */
    private static class FloatingCard extends JComponent {
        private final String imageName;
        private final double phase;
        private final Color frame;
        private final double tilt;

        FloatingCard(String imageName, double phase, Color frame, double tilt) {
            this.imageName = imageName;
            this.phase = phase;
            this.frame = frame;
            this.tilt = tilt;
            setOpaque(false);
            setPreferredSize(new Dimension(104, 136));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double t = System.nanoTime() / 1e9 + phase;
            int w = getWidth(), h = getHeight();
            double bob = Math.sin(t * 2.0) * 5.0;
            double s = t % 4.0;
            boolean face = false;
            double sx = 1.0;
            if (imageName != null) {
                if (s < 0.4) {
                    double p = s / 0.4;
                    sx = Math.max(0.05, Math.abs(Math.cos(Math.PI * p)));
                    face = p >= 0.5;
                } else if (s < 2.2) {
                    face = true;
                } else if (s < 2.6) {
                    double p = (s - 2.2) / 0.4;
                    sx = Math.max(0.05, Math.abs(Math.cos(Math.PI * p)));
                    face = p < 0.5;
                }
            }
            int cw = 78, ch = 104, tx = -cw / 2, ty = -ch / 2;
            g.setColor(new Color(0, 0, 0, (int) (55 - bob * 2)));
            g.fillOval(w / 2 - 28, h - 12, 56, 9);
            g.translate(w / 2.0, h / 2.0 - 6 + bob);
            g.rotate(Math.toRadians(tilt));
            g.scale(sx, 1.0);
            g.setColor(new Color(0, 0, 0, 110));
            g.fill(new RoundRectangle2D.Float(tx + 2, ty + 5, cw, ch, 18, 18));
            RoundRectangle2D.Float shape = new RoundRectangle2D.Float(tx, ty, cw, ch, 18, 18);
            Shape oldClip = g.getClip();
            if (face) {
                g.setColor(PANEL_DARK);
                g.fill(shape);
                BufferedImage img = loadCardImage(imageName);
                g.clip(new RoundRectangle2D.Float(tx + 6, ty + 6, cw - 12, ch - 12, 12, 12));
                g.setColor(KIT_PURPLE);
                g.fillRect(tx + 6, ty + 6, cw - 12, ch - 12);
                if (img != null) drawImageFit(g, img, tx + 6, ty + 6, cw - 12, ch - 12);
                g.setClip(oldClip);
            } else {
                g.clip(shape);
                g.setColor(new Color(59, 42, 122));
                g.fill(shape);
                g.setColor(new Color(46, 32, 96));
                for (int k = 0; k < cw + ch; k += 20) {
                    g.fillPolygon(new int[]{tx + k, tx + k + 10, tx + k + 10 - ch, tx + k - ch},
                        new int[]{ty, ty, ty + ch, ty + ch}, 4);
                }
                g.setClip(oldClip);
                g.setColor(KIT_YELLOW);
                g.setFont(new Font("SansSerif", Font.BOLD, 46));
                FontMetrics fm = g.getFontMetrics();
                g.drawString("?", -fm.stringWidth("?") / 2, fm.getAscent() / 2 - 4);
            }
            g.setColor(face ? frame : KIT_PINK);
            g.setStroke(new BasicStroke(3.5f));
            g.draw(shape);
            g.dispose();
        }
    }

    /** Round avatar badge for the main-menu profile bar. */
    private static class AvatarBadge extends JComponent {
        AvatarBadge() {
            setOpaque(false);
            setPreferredSize(new Dimension(46, 46));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(KIT_PINK);
            g.fillOval(0, 0, w, h);
            g.clip(new java.awt.geom.Ellipse2D.Float(0, 0, w, h));
            g.setColor(new Color(255, 255, 255, 235));
            g.fillOval((int) (w * 0.3), (int) (h * 0.16), (int) (w * 0.4), (int) (h * 0.4));
            g.fillOval((int) (w * 0.1), (int) (h * 0.62), (int) (w * 0.8), (int) (h * 0.8));
            g.dispose();
        }
    }

    /** Thin progress bar under the player name. */
    private static class XpBar extends JComponent {
        private double fraction;

        XpBar() {
            setOpaque(false);
            setPreferredSize(new Dimension(150, 8));
            setMaximumSize(new Dimension(150, 8));
        }

        void setFraction(double value) {
            fraction = Math.max(0.0, Math.min(1.0, value));
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(42, 47, 92));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.setColor(KIT_TEAL);
            g.fillRoundRect(0, 0, Math.max(getHeight(), (int) (getWidth() * fraction)), getHeight(), getHeight(), getHeight());
            g.dispose();
        }
    }

    /** Bottom navigation bar from the kit: icon + label, the active item in yellow. */
    private static class NavBar extends JPanel {
        NavBar(String[] icons, String[] labels, int active, Runnable[] actions) {
            super(new GridLayout(1, icons.length));
            setOpaque(false);
            setPreferredSize(new Dimension(100, 76));
            for (int i = 0; i < icons.length; i++) {
                final int idx = i;
                final Color normal = i == active ? KIT_YELLOW : new Color(184, 188, 224);
                JButton b = new JButton("<html><center><font size='+2'>" + icons[i] + "</font><br>" + labels[i] + "</center></html>");
                b.setForeground(normal);
                b.setFont(new Font("SansSerif", Font.BOLD, 11));
                b.setContentAreaFilled(false);
                b.setBorderPainted(false);
                b.setFocusPainted(false);
                b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                b.addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) {
                        b.setForeground(KIT_YELLOW);
                        playHoverTick();
                    }
                    @Override public void mouseExited(MouseEvent e) { b.setForeground(normal); }
                    @Override public void mousePressed(MouseEvent e) { if (b.isEnabled()) playUiClickSound(); }
                });
                b.addActionListener(e -> actions[idx].run());
                add(b);
            }
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(new Color(12, 14, 34));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(new Color(42, 47, 92));
            g.fillRect(0, 0, getWidth(), 1);
            g.dispose();
        }
    }

    /** Paints the dark rounded box (yellow glow when focused) behind the account text fields. */
    private static void paintFieldChrome(Graphics graphics, JComponent c) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = c.getWidth(), h = c.getHeight();
        boolean focus = c.isFocusOwner();
        g.setColor(new Color(10, 12, 32));
        g.fillRoundRect(0, 0, w, h, 14, 14);
        if (focus) {
            g.setColor(new Color(255, 201, 60, 50));
            g.setStroke(new BasicStroke(5f));
            g.drawRoundRect(1, 1, w - 3, h - 3, 14, 14);
        }
        g.setColor(focus ? KIT_YELLOW : new Color(60, 66, 124));
        g.setStroke(new BasicStroke(focus ? 2.2f : 1.6f));
        g.drawRoundRect(1, 1, w - 3, h - 3, 14, 14);
        g.dispose();
    }

    private static void styleField(javax.swing.text.JTextComponent c) {
        c.setOpaque(false);
        c.setForeground(Color.WHITE);
        c.setCaretColor(KIT_YELLOW);
        c.setSelectionColor(KIT_PURPLE);
        c.setSelectedTextColor(Color.WHITE);
        c.setFont(new Font("SansSerif", Font.PLAIN, 15));
        c.setBorder(new EmptyBorder(10, 14, 10, 14));
        c.setPreferredSize(new Dimension(200, 46));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { c.repaint(); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { c.repaint(); }
        });
    }

    private static class RoundedTextField extends JTextField {
        RoundedTextField(String text) {
            super(text, 18);
            styleField(this);
        }

        @Override protected void paintComponent(Graphics graphics) {
            paintFieldChrome(graphics, this);
            super.paintComponent(graphics);
        }
    }

    private static class RoundedPasswordField extends JPasswordField {
        RoundedPasswordField() {
            super(18);
            styleField(this);
        }

        @Override protected void paintComponent(Graphics graphics) {
            paintFieldChrome(graphics, this);
            super.paintComponent(graphics);
        }
    }

    /** Four-segment password strength meter. */
    private static class StrengthBar extends JComponent {
        private int score;

        StrengthBar() {
            setOpaque(false);
            setPreferredSize(new Dimension(200, 8));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        void setScore(int value) {
            score = Math.max(0, Math.min(4, value));
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int gap = 5, seg = (getWidth() - gap * 3) / 4;
            Color on = score <= 1 ? KIT_PINK : score == 2 ? KIT_YELLOW : KIT_TEAL;
            for (int i = 0; i < 4; i++) {
                g.setColor(i < score ? on : new Color(42, 47, 92));
                g.fillRoundRect(i * (seg + gap), 0, seg, getHeight(), getHeight(), getHeight());
            }
            g.dispose();
        }
    }

    private static int passwordScore(char[] pw) {
        int score = 0;
        if (pw.length >= 6) score++;
        if (pw.length >= 10) score++;
        boolean letter = false, digit = false, upper = false, lower = false, symbol = false;
        for (char ch : pw) {
            if (Character.isLetter(ch)) letter = true;
            if (Character.isDigit(ch)) digit = true;
            if (Character.isUpperCase(ch)) upper = true;
            if (Character.isLowerCase(ch)) lower = true;
            if (!Character.isLetterOrDigit(ch)) symbol = true;
        }
        if (letter && digit) score++;
        if ((upper && lower) || symbol) score++;
        return score;
    }

    private static void onTextChange(javax.swing.text.JTextComponent c, Runnable r) {
        c.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        });
    }

    /** Wiggles a window sideways (used when a sign-in fails). */
    private static void shakeWindow(Window w) {
        final Point origin = w.getLocation();
        final long started = System.nanoTime();
        javax.swing.Timer t = new javax.swing.Timer(16, null);
        t.addActionListener(e -> {
            double p = Math.min(1.0, (System.nanoTime() - started) / 420e6);
            w.setLocation(origin.x + (int) (Math.sin(p * Math.PI * 8) * 12 * (1 - p)), origin.y);
            if (p >= 1.0) {
                w.setLocation(origin);
                t.stop();
            }
        });
        t.start();
    }

    /** Rounded stat badge used in the game header. When urgent it grows, pulses and glows red. */
    private static class PillLabel extends JLabel {
        private Color fill;
        private boolean urgent;
        private Font calmFont;
        private final javax.swing.Timer pulseTimer = new javax.swing.Timer(33, e -> repaint());

        PillLabel(Color fill) {
            this.fill = fill;
            setOpaque(false);
            setBorder(new EmptyBorder(6, 14, 6, 14));
        }

        void setFill(Color color) {
            if (!color.equals(fill)) {
                fill = color;
                repaint();
            }
        }

        /** Switches the big, pulsing red "time is almost up" look on or off. */
        void setUrgent(boolean on) {
            if (urgent == on) return;
            urgent = on;
            if (on) {
                calmFont = getFont();
                setFont(calmFont.deriveFont(Font.BOLD, calmFont.getSize2D() + 10f));
                setBorder(new EmptyBorder(13, 21, 13, 21));
                pulseTimer.start();
            } else {
                if (calmFont != null) setFont(calmFont);
                setBorder(new EmptyBorder(6, 14, 6, 14));
                pulseTimer.stop();
            }
            revalidate();
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (urgent) {
                double pulse = (Math.sin(System.nanoTime() / 1e9 * 7.0) + 1.0) / 2.0;
                int m = 7, pw = getWidth() - 2 * m - 1, ph = getHeight() - 2 * m - 1;
                for (int k = 3; k >= 0; k--) {          // soft red glow around the badge
                    int grow = k * 2;
                    g.setColor(new Color(255, 60, 90, (int) ((35 + 70 * pulse) * (1.0 - k / 4.0))));
                    g.fillRoundRect(m - grow, m - grow, pw + 2 * grow, ph + 2 * grow, ph + 2 * grow, ph + 2 * grow);
                }
                g.setColor(mix(KIT_PINK, new Color(255, 70, 70), (float) pulse));
                g.fillRoundRect(m, m, pw, ph, ph, ph);
                g.setColor(new Color(255, 255, 255, 90));
                g.drawRoundRect(m, m, pw, ph, ph, ph);
            } else {
                g.setColor(fill);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g.setColor(new Color(255, 255, 255, 24));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /**
     * Transparent overlay (the window's glass pane) for sparkle bursts, confetti,
     * floating combo text, screen flashes, the big level banner and the card-merge showcase.
     * A brand-new card gets the "unlock" treatment: the two cards are sucked into a spinning vortex,
     * the screen shakes and whites out, then the card flips into view with rays, rings, a NEW tag and a banner.
     */
    private static class EffectsPane extends JComponent {
        private static class Particle {
            double x, y, vx, vy, gravity, life, maxLife, size, rot, vr;
            Color color;
            int kind; // 0 dot, 1 star, 2 confetti, 3 text
            String text;
        }

        private final java.util.List<Particle> particles = new ArrayList<>();
        private final Random rnd = new Random();
        private final javax.swing.Timer timer;
        private long last = System.nanoTime();
        private String bannerTitle = "", bannerSub = "";
        private Color bannerColor = Color.WHITE;
        private long bannerStart = -1;
        private Color flashColor = Color.WHITE;
        private long flashStart = -1;

        // Match showcase: the two cards arc toward the middle, merge, and pop up for the player to see.
        private static final int MATCH_FLY = 600;        // ms: cards fly and merge
        private static final int NEW_CARD_FLY = 1150;    // ms: the vortex build-up for a brand-new collectible
        private static final int MATCH_POP = 420;        // ms: merged card pops up
        private static final int MATCH_UNLOCK = 1500;    // ms: the board can be played again
        private static final int MATCH_HOLD_END = 2500;  // ms: showcase held on screen until here
        private static final int MATCH_END = 3000;       // ms: showcase fully faded (ends with the sound's tail)
        // The merge sound's loud hit is ~0.1 s into the file, plus a little player start-up time,
        // so it is started this many ms early so that the hit lands exactly on the merge flash.
        private static final int MATCH_SOUND_LEAD = 150;
        private static final int MATCH_SPARKLE = 1800;   // ms: a second sparkle on the sound's later swell
        private long matchStart = -1;
        private BufferedImage matchImage;
        private String matchBadge = "A";
        private Color matchFrame = Color.WHITE;
        private Rectangle matchFromA = new Rectangle(), matchFromB = new Rectangle();
        private Runnable matchDone;
        private Runnable matchFinished;
        private boolean matchSpinRapidly;
        private boolean matchUnlockSoundPlaying;
        private boolean matchBurst, matchDoneFired, matchFinishedFired, matchSoundStarted, matchClipPlayed, matchSparkle, matchWave2;

        EffectsPane() {
            setOpaque(false);
            timer = new javax.swing.Timer(16, e -> step());
        }

        /** Let every mouse click fall through to the game underneath. */
        @Override public boolean contains(int x, int y) { return false; }

        private void wake() {
            if (!timer.isRunning()) {
                last = System.nanoTime();
                timer.start();
            }
        }

        void burst(Point p, Color c, int n) {
            for (int i = 0; i < n; i++) {
                Particle q = new Particle();
                double a = rnd.nextDouble() * Math.PI * 2, s = 70 + rnd.nextDouble() * 230;
                q.x = p.x; q.y = p.y;
                q.vx = Math.cos(a) * s; q.vy = Math.sin(a) * s - 60;
                q.gravity = 420;
                q.maxLife = q.life = 0.55 + rnd.nextDouble() * 0.5;
                q.size = 4 + rnd.nextDouble() * 6;
                q.color = i % 3 == 0 ? Color.WHITE : c;
                q.kind = i % 2;
                q.rot = rnd.nextDouble() * Math.PI;
                q.vr = (rnd.nextDouble() - 0.5) * 8;
                particles.add(q);
            }
            wake();
        }

        void confetti() {
            Color[] palette = {
                new Color(255, 214, 110), new Color(136, 235, 196), new Color(111, 190, 255),
                new Color(255, 120, 160), new Color(190, 150, 255)
            };
            for (int i = 0; i < 45; i++) {
                Particle q = new Particle();
                q.x = rnd.nextDouble() * Math.max(1, getWidth());
                q.y = -20 - rnd.nextDouble() * Math.max(1, getHeight()) * 0.5;
                q.vx = (rnd.nextDouble() - 0.5) * 110;
                q.vy = 60 + rnd.nextDouble() * 150;
                q.gravity = 70;
                q.maxLife = q.life = 1.0 + rnd.nextDouble() * 0.6;
                q.size = 4 + rnd.nextDouble() * 4;
                q.color = palette[rnd.nextInt(palette.length)];
                q.kind = 2;
                q.rot = rnd.nextDouble() * Math.PI;
                q.vr = (rnd.nextDouble() - 0.5) * 12;
                particles.add(q);
            }
            wake();
        }

        void floatText(String text, Point p, Color c) {
            Particle q = new Particle();
            q.x = p.x; q.y = p.y;
            q.vy = -55;
            q.maxLife = q.life = 1.1;
            q.color = c;
            q.kind = 3;
            q.text = text;
            particles.add(q);
            wake();
        }

        void banner(String title, String sub, Color c) {
            bannerTitle = title;
            bannerSub = sub;
            bannerColor = c;
            bannerStart = System.nanoTime();
            wake();
        }

        void flash(Color c) {
            flashColor = c;
            flashStart = System.nanoTime();
            wake();
        }

        /** Starts the fly-merge-pop-up animation. The board unlock and end callbacks run at their own times. */
        void showMatch(BufferedImage image, String badge, Color frame, Rectangle fromA, Rectangle fromB,
                       boolean rapidSpin, Runnable onUnlock, Runnable onFinished) {
            matchImage = image;
            matchBadge = badge;
            matchFrame = frame;
            matchFromA = new Rectangle(fromA);
            matchFromB = new Rectangle(fromB);
            matchDone = onUnlock;
            matchFinished = onFinished;
            matchSpinRapidly = rapidSpin;
            matchUnlockSoundPlaying = rapidSpin && playNewUnlockSound();
            if (rapidSpin && !matchUnlockSoundPlaying) playSound("match");
            matchBurst = false;
            matchDoneFired = false;
            matchFinishedFired = false;
            matchSoundStarted = false;
            matchClipPlayed = false;
            matchSparkle = false;
            matchWave2 = false;
            matchStart = System.nanoTime();
            wake();
        }

        void cancelMatch() {
            if (matchUnlockSoundPlaying) stopNewUnlockSound();
            matchUnlockSoundPlaying = false;
            matchStart = -1;
            matchDone = null;
            matchFinished = null;
            repaint();
        }

        private Point matchCenter() {
            return new Point(getWidth() / 2, (int) (getHeight() * 0.46));
        }

        private int currentMatchFlyDuration() {
            return matchSpinRapidly ? NEW_CARD_FLY : MATCH_FLY;
        }

        // ---------------- normal match: two cards arch together ----------------

        private void drawGhost(Graphics2D g0, Rectangle from, Point to, double e, int side,
                               int showW, int showH, float alpha) {
            double x0 = from.getCenterX(), y0 = from.getCenterY();
            double dist = Math.hypot(to.x - x0, to.y - y0);
            double cx = (x0 + to.x) / 2.0, cy = Math.min(y0, to.y) - (90 + dist * 0.25);   // arch above both cards
            double x = (1 - e) * (1 - e) * x0 + 2 * (1 - e) * e * cx + e * e * to.x;
            double y = (1 - e) * (1 - e) * y0 + 2 * (1 - e) * e * cy + e * e * to.y;
            int w = Math.max(8, (int) Math.round(from.width + (showW * 0.55 - from.width) * e));
            int h = Math.max(8, (int) Math.round(from.height + (showH * 0.55 - from.height) * e));
            double rot = Math.toRadians(28) * side * Math.sin(Math.PI * e);   // tilts mid-flight, upright at the merge
            Graphics2D g = (Graphics2D) g0.create();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g.translate(x, y);
            g.rotate(rot);
            g.translate(-w / 2.0, -h / 2.0);
            paintFaceCard(g, w, h, matchImage, matchBadge, matchFrame);
            g.dispose();
        }

        // ---------------- new card: vortex build-up, white-out, reveal ----------------

        /** A simple striped card back, shown while a spinning card is turned away from the player. */
        private static void paintCardBack(Graphics2D g0, int w, int h) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double u = Math.max(0.55, Math.min(2.2, Math.min(w, h * 0.75) / 90.0));
            int arc = Math.max(10, (int) Math.round(22 * u));
            g.setColor(new Color(5, 9, 24, 120));
            g.fillRoundRect(3, 6, w - 6, h - 8, arc, arc);
            Shape old = g.getClip();
            g.clip(new RoundRectangle2D.Float(2, 2, w - 6, h - 7, arc, arc));
            g.setColor(new Color(59, 42, 122));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(46, 32, 96));
            int stripe = Math.max(8, (int) Math.round(10 * u));
            for (int k = -h; k < w + h; k += stripe * 2) {
                g.fillPolygon(new int[]{k, k + stripe, k + stripe - h, k - h}, new int[]{0, 0, h, h}, 4);
            }
            g.setClip(old);
            g.setColor(KIT_PINK);
            g.setStroke(new BasicStroke((float) Math.max(2.0, 3.5 * u) * 0.85f));
            g.drawRoundRect(3, 3, w - 8, h - 9, arc - 2, arc - 2);
            g.setColor(KIT_YELLOW);
            g.setFont(new Font("SansSerif", Font.BOLD, Math.max(14, (int) Math.round(Math.min(w, h * 0.75) * 0.5))));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("?", (w - 4 - fm.stringWidth("?")) / 2, (h - 5 + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }

        /** Draws the card centered on the origin, turned about its vertical axis by {@code flip} radians. */
        private void drawSpinCard(Graphics2D g0, int w, int h, double flip) {
            double c = Math.cos(flip);
            double sx = Math.max(0.06, Math.abs(c));
            Graphics2D g = (Graphics2D) g0.create();
            g.scale(sx, 1.0);
            g.translate(-w / 2.0, -h / 2.0);
            if (c >= 0) paintFaceCard(g, w, h, matchImage, matchBadge, matchFrame);
            else paintCardBack(g, w, h);
            g.dispose();
        }

        /** Position of one card on its inward spiral around the merge point (t from 0 to 1). */
        private double[] vortexPoint(Rectangle from, Point center, double t) {
            double x0 = from.getCenterX() - center.x, y0 = from.getCenterY() - center.y;
            double r0 = Math.hypot(x0, y0), a0 = Math.atan2(y0, x0);
            double radius = r0 * Math.pow(1.0 - Math.pow(t, 1.7), 1.2);                 // slow start, fast finish
            double angle = a0 + 2 * Math.PI * 3.4 * Math.pow(t, 2.0);                    // spins faster and faster
            return new double[]{center.x + Math.cos(angle) * radius, center.y + Math.sin(angle) * radius * 0.82, angle};
        }

        private void drawVortexCard(Graphics2D g0, Rectangle from, Point center, double t, int which,
                                    float alpha, int showW, int showH, boolean lead) {
            double[] p = vortexPoint(from, center, t);
            double s = Math.pow(t, 1.7);
            int w = Math.max(10, (int) Math.round(from.width + (showW * 0.6 - from.width) * s));
            int h = Math.max(10, (int) Math.round(from.height + (showH * 0.6 - from.height) * s));
            double flip = t * t * 46.0 + which * 1.2;                                    // flips over faster and faster
            Graphics2D g = (Graphics2D) g0.create();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g.translate(p[0], p[1]);
            g.rotate(Math.sin(p[2]) * 0.35);
            if (lead && t > 0.12) {
                int glow = (int) Math.min(210, 50 + 170 * t);
                g.setColor(which == 0 ? new Color(255, 201, 60, glow) : new Color(46, 230, 197, glow));
                g.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawRoundRect(-w / 2 - 6, -h / 2 - 6, w + 12, h + 12, 28, 28);
            }
            drawSpinCard(g, w, h, flip);
            g.dispose();
        }

        private void paintVortex(Graphics2D g0, double ms, int fly, Point c, int showW, int showH) {
            int w = getWidth(), h = getHeight();
            double t = Math.min(1.0, ms / fly);
            double t2 = t * t;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (t > 0.68) {                                   // the screen shakes as the energy peaks
                double k = (t - 0.68) / 0.32;
                double amp = 9.0 * k * k;
                g.translate(Math.sin(ms * 0.095) * amp, Math.cos(ms * 0.123) * amp * 0.6);
            }
            g.setColor(new Color(6, 8, 24, (int) (170 * Math.min(1.0, t * 1.4))));
            g.fillRect(-20, -20, w + 40, h + 40);

            // a glowing energy core that swells at the center
            float core = (float) Math.max(8.0, showH * (0.16 + 0.62 * t2));
            int coreAlpha = (int) Math.min(255, 40 + 190 * t2 * t);
            g.setPaint(new RadialGradientPaint(c, core, new float[]{0f, 0.45f, 1f},
                new Color[]{new Color(255, 250, 220, coreAlpha), new Color(255, 205, 80, coreAlpha / 2),
                            new Color(255, 190, 60, 0)}));
            g.fillOval(c.x - (int) core, c.y - (int) core, (int) core * 2, (int) core * 2);

            // speed lines rushing inward
            double reach = Math.hypot(w, h) * 0.55;
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 30; i++) {
                double a = i * 2 * Math.PI / 30 + t * 1.1 + Math.sin(i * 12.9898) * 0.08;
                double p = (t * 1.7 + (i * 0.6180339) % 1.0) % 1.0;
                double rHead = reach * (1.0 - p) + 24;
                double rTail = rHead + (40 + 150 * t) * (0.4 + 0.6 * ((i * 7) % 5) / 4.0);
                int alpha = (int) (170 * Math.sin(Math.PI * p) * Math.min(1.0, t * 1.6));
                g.setColor(i % 3 == 0 ? new Color(255, 255, 255, alpha)
                    : i % 3 == 1 ? new Color(255, 214, 110, alpha) : new Color(120, 240, 220, alpha));
                g.drawLine((int) (c.x + Math.cos(a) * rHead), (int) (c.y + Math.sin(a) * rHead),
                           (int) (c.x + Math.cos(a) * rTail), (int) (c.y + Math.sin(a) * rTail));
            }

            // charge rings contracting into the core
            for (int k = 0; k < 3; k++) {
                double q = (t * 2.4 + k / 3.0) % 1.0;
                double radius = showH * 0.95 * (1.0 - q) + 12;
                int alpha = (int) (150 * q * Math.min(1.0, t * 2.0));
                g.setColor(k % 2 == 0 ? new Color(255, 214, 110, alpha) : new Color(120, 240, 220, alpha));
                g.setStroke(new BasicStroke((float) (2.0 + 4.0 * q)));
                g.drawOval((int) (c.x - radius), (int) (c.y - radius), (int) (radius * 2), (int) (radius * 2));
            }

            // sparks trailing behind each card, then the cards with their ghost trails
            for (int which = 0; which < 2; which++) {
                Rectangle from = which == 0 ? matchFromA : matchFromB;
                for (int j = 1; j <= 7; j++) {
                    double[] sp = vortexPoint(from, c, Math.max(0.0, t - 0.02 * j));
                    int alpha = (int) (200 * (1.0 - j / 8.0) * Math.min(1.0, t * 3.0));
                    g.setColor(which == 0 ? new Color(255, 214, 110, alpha) : new Color(120, 240, 220, alpha));
                    fillStar(g, sp[0] + Math.sin(j * 2.1) * 9, sp[1] + Math.cos(j * 1.7) * 9, 4 + (j % 3) * 2, j * 0.9 + t * 8);
                }
            }
            for (int k = 6; k >= 0; k--) {
                double tk = Math.max(0.0, t - 0.016 * k);
                float alpha = k == 0 ? 1f : 0.30f * (1f - k / 7f);
                drawVortexCard(g, matchFromA, c, tk, 0, alpha, showW, showH, k == 0);
                drawVortexCard(g, matchFromB, c, tk, 1, alpha, showW, showH, k == 0);
            }

            double pre = Math.max(0.0, (t - 0.86) / 0.14);    // light swallows the screen just before the reveal
            if (pre > 0.0) {
                g.setColor(new Color(255, 255, 255, (int) (235 * pre * pre)));
                g.fillRect(-20, -20, w + 40, h + 40);
            }
            g.dispose();
        }

        private void paintUnlockReveal(Graphics2D g0, double u, double ms, Point c, int showW, int showH) {
            int w = getWidth(), h = getHeight();
            double fade = ms > MATCH_HOLD_END
                ? Math.max(0.0, 1.0 - (ms - MATCH_HOLD_END) / (MATCH_END - MATCH_HOLD_END)) : 1.0;
            double pop = Math.min(1.0, u / 560.0);
            double settle = Math.min(1.0, u / 900.0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (u < 380) {                                    // impact shake
                double k = 1.0 - u / 380.0;
                g.translate(Math.sin(u * 0.12) * 13 * k, Math.cos(u * 0.15) * 8 * k);
            }
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) fade));
            g.setColor(new Color(6, 8, 24, 150));
            g.fillRect(-20, -20, w + 40, h + 40);

            float glowR = (float) (showH * 1.05 * (0.45 + 0.55 * pop));
            g.setPaint(new RadialGradientPaint(c, glowR, new float[]{0f, 1f},
                new Color[]{new Color(255, 226, 120, 175), new Color(255, 214, 110, 0)}));
            g.fillOval(c.x - (int) glowR, c.y - (int) glowR, (int) glowR * 2, (int) glowR * 2);

            // light rays: they whip around at first, then slow to a calm turn
            double spin = 0.7 * (u / 1000.0) + 7.0 * 0.42 * (1.0 - Math.exp(-u / 420.0));
            double innerRay = showH * 0.52, outerRay = showH * (0.95 + 0.45 * pop);
            for (int k = 0; k < 12; k++) {
                double a = -spin * 0.6 + k * 2 * Math.PI / 12;
                g.setColor(new Color(255, 255, 255, 34));
                g.fillPolygon(
                    new int[]{(int) (c.x + innerRay * Math.cos(a - 0.12)), (int) (c.x + innerRay * Math.cos(a + 0.12)), (int) (c.x + outerRay * 1.25 * Math.cos(a))},
                    new int[]{(int) (c.y + innerRay * Math.sin(a - 0.12)), (int) (c.y + innerRay * Math.sin(a + 0.12)), (int) (c.y + outerRay * 1.25 * Math.sin(a))}, 3);
            }
            for (int k = 0; k < 24; k++) {
                double a = spin + k * 2 * Math.PI / 24;
                g.setColor(k % 2 == 0 ? new Color(255, 201, 60, 140) : new Color(255, 255, 255, 90));
                g.fillPolygon(
                    new int[]{(int) (c.x + innerRay * Math.cos(a - 0.055)), (int) (c.x + innerRay * Math.cos(a + 0.055)), (int) (c.x + outerRay * Math.cos(a))},
                    new int[]{(int) (c.y + innerRay * Math.sin(a - 0.055)), (int) (c.y + innerRay * Math.sin(a + 0.055)), (int) (c.y + outerRay * Math.sin(a))}, 3);
            }

            // shockwave rings
            for (int ring = 0; ring < 3; ring++) {
                double progress = Math.max(0.0, Math.min(1.0, u / 620.0 - ring * 0.16));
                if (progress <= 0.0 || progress >= 1.0) continue;
                int radius = (int) (36 + progress * showH * 0.92);
                g.setColor(ring == 1 ? new Color(46, 230, 197, (int) ((1.0 - progress) * 190))
                                     : new Color(255, 214, 110, (int) ((1.0 - progress) * 190)));
                g.setStroke(new BasicStroke((float) (6.0 * (1.0 - progress) + 1.2)));
                g.drawOval(c.x - radius, c.y - radius, radius * 2, radius * 2);
            }

            // the card flips over several times, slowing down until it faces the player
            double scale = 0.22 + 0.78 * easeOutBack(pop);
            double flip = Math.pow(1.0 - settle, 2.0) * Math.PI * 7.0;
            double pulse = (Math.sin(ms * 0.008) + 1.0) / 2.0;
            Graphics2D cardG = (Graphics2D) g.create();
            cardG.translate(c.x, c.y);
            cardG.rotate(Math.sin(u * 0.014) * 0.07 * (1.0 - settle));
            cardG.scale(scale, scale);
            int halo = (int) (14 + 10 * pulse);
            cardG.setColor(new Color(255, 201, 60, (int) (40 + 50 * pulse)));
            cardG.fillRoundRect(-showW / 2 - halo, -showH / 2 - halo, showW + halo * 2, showH + halo * 2, 46, 46);
            drawSpinCard(cardG, showW, showH, flip);
            cardG.dispose();

            // twinkling stars around the card
            for (int i = 0; i < 12; i++) {
                double a = i * 2.399 + ms * 0.0007;
                double rx = showW * 0.62 * scale, ry = showH * 0.58 * scale;
                double px = c.x + Math.cos(a) * rx * (0.92 + 0.12 * Math.sin(i * 3.1));
                double py = c.y + Math.sin(a) * ry * (0.92 + 0.12 * Math.cos(i * 2.3));
                int alpha = (int) (230 * Math.abs(Math.sin(ms * 0.006 + i * 1.7)) * Math.min(1.0, u / 300.0));
                g.setColor(i % 2 == 0 ? new Color(255, 236, 160, alpha) : new Color(255, 255, 255, alpha));
                fillStar(g, px, py, 5 + (i % 3) * 3, ms * 0.002 + i);
            }

            // the NEW tag pops in at the card's corner
            if (u > 260) {
                double bt = easeOutBack(Math.min(1.0, (u - 260) / 300.0));
                double bx = c.x + showW * 0.5 * scale - 6, by = c.y - showH * 0.5 * scale + 14;
                Graphics2D tag = (Graphics2D) g.create();
                tag.translate(bx, by);
                tag.rotate(Math.toRadians(-14));
                tag.scale(Math.max(0.02, bt), Math.max(0.02, bt));
                int points = 14;
                int[] xs = new int[points * 2], ys = new int[points * 2];
                for (int k = 0; k < points * 2; k++) {
                    double rr = k % 2 == 0 ? 34 : 27;
                    double aa = k * Math.PI / points;
                    xs[k] = (int) (Math.cos(aa) * rr);
                    ys[k] = (int) (Math.sin(aa) * rr);
                }
                tag.setColor(new Color(150, 20, 70));
                tag.fillPolygon(xs, ys, points * 2);
                tag.translate(-2, -2);
                tag.setColor(KIT_YELLOW);
                tag.fillPolygon(xs, ys, points * 2);
                tag.setColor(new Color(26, 16, 51));
                tag.setFont(new Font("SansSerif", Font.BOLD, 17));
                FontMetrics tfm = tag.getFontMetrics();
                tag.drawString("NEW", -tfm.stringWidth("NEW") / 2, 6);
                tag.dispose();
            }

            // ribbon banner with a shining title
            double bandT = Math.max(0.0, Math.min(1.0, (u - 220) / 360.0));
            if (bandT > 0.0) {
                double eb = easeOutBack(bandT);
                int ty = c.y + (int) (showH / 2.0 * scale) + 62;
                int bandW = (int) (560 * Math.min(1.0, eb));
                g.setColor(new Color(10, 16, 36, 215));
                g.fillRect(c.x - bandW / 2, ty - 40, bandW, 60);
                g.setColor(KIT_YELLOW);
                g.fillRect(c.x - bandW / 2, ty - 40, bandW, 3);
                g.fillRect(c.x - bandW / 2, ty + 17, bandW, 3);
                Graphics2D text = (Graphics2D) g.create();
                text.translate(c.x, ty);
                text.scale(Math.max(0.02, eb), Math.max(0.02, eb));
                text.setFont(new Font("SansSerif", Font.BOLD, 40));
                FontMetrics fm = text.getFontMetrics();
                String label = "NEW CARD!";
                int tw = fm.stringWidth(label);
                text.setColor(new Color(0, 0, 0, 170));
                text.drawString(label, -tw / 2 + 3, 4);
                double sweep = ((ms / 1000.0) % 1.6) / 1.6;
                float sx = (float) (-tw / 2.0 - tw * 0.3 + sweep * tw * 1.6);
                text.setPaint(new LinearGradientPaint(sx - 80f, 0f, sx + 80f, 0f, new float[]{0f, 0.5f, 1f},
                    new Color[]{KIT_YELLOW, Color.WHITE, KIT_YELLOW}));
                text.drawString(label, -tw / 2, 1);
                text.dispose();
            }

            // the white-out that the vortex ends with fades away over the first moments of the reveal
            g.setComposite(AlphaComposite.SrcOver);
            double white = 1.0 - Math.min(1.0, u / 260.0);
            if (white > 0.0) {
                g.setColor(new Color(255, 255, 255, (int) (235 * white * white)));
                g.fillRect(-20, -20, w + 40, h + 40);
            }
            g.dispose();
        }

        private void paintMatch(Graphics2D g0, double ms) {
            Point c = matchCenter();
            int showH = (int) Math.min(getHeight() * 0.66, 560);
            int showW = (int) (showH * 0.75);
            int flyDuration = currentMatchFlyDuration();
            if (matchSpinRapidly) {
                if (ms < flyDuration) paintVortex(g0, ms, flyDuration, c, showW, showH);
                else paintUnlockReveal(g0, ms - flyDuration, ms, c, showW, showH);
                return;
            }
            if (ms < flyDuration) {
                double t = ms / flyDuration;
                double e = t * t * (3 - 2 * t);
                for (int k = 1; k >= 0; k--) {          // light motion trail behind each card
                    double ek = Math.max(0.0, e - 0.045 * k);
                    float alpha = k == 0 ? 1f : 0.16f / k;
                    drawGhost(g0, matchFromA, c, ek, -1, showW, showH, alpha);
                    drawGhost(g0, matchFromB, c, ek, +1, showW, showH, alpha);
                }
                return;
            }
            double pop = Math.min(1.0, (ms - flyDuration) / MATCH_POP);
            double scale = 0.55 + 0.45 * easeOutBack(pop);
            double fade = 1.0;
            if (ms > MATCH_HOLD_END) {
                fade = Math.max(0.0, 1.0 - (ms - MATCH_HOLD_END) / (MATCH_END - MATCH_HOLD_END));
                scale *= 1.0 + 0.12 * (1.0 - fade);
            }
            Graphics2D g = (Graphics2D) g0.create();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) fade));
            float glowR = (float) (showH * 0.95 * (0.5 + 0.5 * pop));
            g.setPaint(new RadialGradientPaint(c, glowR, new float[]{0f, 1f},
                new Color[]{new Color(255, 214, 110, 150), new Color(255, 214, 110, 0)}));
            g.fillOval(c.x - (int) glowR, c.y - (int) glowR, (int) glowR * 2, (int) glowR * 2);
            double spin = ms / 1000.0 * 0.6;
            int rays = 14;
            double inner = showH * 0.55 * scale;
            double outer = showH * (0.80 + 0.30 * pop) * scale;
            for (int k = 0; k < rays; k++) {
                double a = spin + k * 2 * Math.PI / rays;
                g.setColor(k % 2 == 0 ? new Color(255, 201, 60, 130) : new Color(255, 255, 255, 80));
                g.fillPolygon(
                    new int[]{(int) (c.x + inner * Math.cos(a - 0.07)), (int) (c.x + inner * Math.cos(a + 0.07)), (int) (c.x + outer * Math.cos(a))},
                    new int[]{(int) (c.y + inner * Math.sin(a - 0.07)), (int) (c.y + inner * Math.sin(a + 0.07)), (int) (c.y + outer * Math.sin(a))}, 3);
            }
            Graphics2D cardG = (Graphics2D) g.create();
            cardG.translate(c.x, c.y);
            cardG.scale(scale, scale);
            cardG.translate(-showW / 2.0, -showH / 2.0);
            paintFaceCard(cardG, showW, showH, matchImage, matchBadge, matchFrame);
            cardG.dispose();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.BOLD, 34));
            FontMetrics fm = g.getFontMetrics();
            int ty = c.y + (int) (showH / 2.0 * scale) + 44;
            g.setColor(new Color(0, 0, 0, 150));
            g.drawString("MATCH!", c.x - fm.stringWidth("MATCH!") / 2 + 2, ty + 3);
            g.setColor(KIT_YELLOW);
            g.drawString("MATCH!", c.x - fm.stringWidth("MATCH!") / 2, ty);
            g.dispose();
        }

        private void step() {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - last) / 1e9);
            last = now;
            Iterator<Particle> it = particles.iterator();
            while (it.hasNext()) {
                Particle q = it.next();
                q.vy += q.gravity * dt;
                q.x += q.vx * dt;
                q.y += q.vy * dt;
                q.rot += q.vr * dt;
                q.life -= dt;
                if (q.life <= 0) it.remove();
            }
            if (bannerStart >= 0 && (now - bannerStart) / 1e6 >= 1500) bannerStart = -1;
            if (flashStart >= 0 && (now - flashStart) / 1e6 >= 320) flashStart = -1;
            if (matchStart >= 0) {
                double ms = (now - matchStart) / 1e6;
                int flyDuration = currentMatchFlyDuration();
                if (!matchSoundStarted && ms >= flyDuration - MATCH_SOUND_LEAD) {
                    matchSoundStarted = true;
                    if (!matchSpinRapidly) matchClipPlayed = playMergeClip();
                }
                if (!matchBurst && ms >= flyDuration) {
                    matchBurst = true;
                    if (matchUnlockSoundPlaying) {
                        stopNewUnlockSound();
                        matchUnlockSoundPlaying = false;
                    }
                    if (matchSpinRapidly) matchClipPlayed = playMergeClip();
                    burst(matchCenter(), KIT_YELLOW, matchSpinRapidly ? 34 : 10);
                    burst(matchCenter(), matchSpinRapidly ? KIT_TEAL : Color.WHITE, matchSpinRapidly ? 18 : 4);
                    if (matchSpinRapidly) burst(matchCenter(), Color.WHITE, 14);
                    if (!matchSpinRapidly) flash(new Color(255, 255, 255, 90));
                    if (!matchClipPlayed) playSound("pop");
                }
                if (matchSpinRapidly && !matchWave2 && ms >= flyDuration + 260) {
                    matchWave2 = true;
                    burst(matchCenter(), KIT_YELLOW, 22);
                    burst(matchCenter(), KIT_PINK, 12);
                    confetti();
                }
                if (!matchSparkle && ms >= MATCH_SPARKLE) {
                    matchSparkle = true;
                    burst(matchCenter(), KIT_YELLOW, 8);
                    burst(matchCenter(), Color.WHITE, 6);
                    flash(new Color(255, 255, 255, 45));
                }
                if (!matchDoneFired && ms >= MATCH_UNLOCK) {
                    matchDoneFired = true;
                    Runnable done = matchDone;
                    matchDone = null;
                    if (done != null) done.run();
                }
                if (!matchFinishedFired && ms >= MATCH_END) {
                    matchFinishedFired = true;
                    matchStart = -1;
                    Runnable finished = matchFinished;
                    matchFinished = null;
                    if (finished != null) finished.run();
                }
            }
            if (particles.isEmpty() && bannerStart < 0 && flashStart < 0 && matchStart < 0) timer.stop();
            repaint();
        }

        private static void fillStar(Graphics2D g, double cx, double cy, double r, double rot) {
            int[] xs = new int[8], ys = new int[8];
            for (int k = 0; k < 8; k++) {
                double radius = k % 2 == 0 ? r : r * 0.35;
                double a = rot + k * Math.PI / 4;
                xs[k] = (int) (cx + Math.cos(a) * radius);
                ys[k] = (int) (cy + Math.sin(a) * radius);
            }
            g.fillPolygon(xs, ys, 8);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            long now = System.nanoTime();
            int w = getWidth(), h = getHeight();

            if (flashStart >= 0) {
                double p = (now - flashStart) / 320e6;
                int alpha = (int) Math.max(0, flashColor.getAlpha() * (1.0 - p));
                g.setColor(new Color(flashColor.getRed(), flashColor.getGreen(), flashColor.getBlue(), alpha));
                g.fillRect(0, 0, w, h);
            }

            if (matchStart >= 0) paintMatch(g, (now - matchStart) / 1e6);

            for (Particle q : particles) {
                double f = Math.max(0.0, Math.min(1.0, q.life / (q.maxLife * 0.4)));
                Color c = new Color(q.color.getRed(), q.color.getGreen(), q.color.getBlue(), (int) (255 * f));
                g.setColor(c);
                if (q.kind == 0) {
                    g.fillOval((int) (q.x - q.size / 2), (int) (q.y - q.size / 2), (int) q.size, (int) q.size);
                } else if (q.kind == 1) {
                    fillStar(g, q.x, q.y, q.size * 1.2, q.rot);
                } else if (q.kind == 2) {
                    Graphics2D r = (Graphics2D) g.create();
                    r.translate(q.x, q.y);
                    r.rotate(q.rot);
                    r.setColor(c);
                    r.fillRect((int) (-q.size / 2), (int) (-q.size * 0.8), (int) q.size, (int) (q.size * 1.6));
                    r.dispose();
                } else {
                    g.setFont(new Font("SansSerif", Font.BOLD, 26));
                    FontMetrics fm = g.getFontMetrics();
                    int tx = (int) q.x - fm.stringWidth(q.text) / 2;
                    g.setColor(new Color(0, 0, 0, (int) (140 * f)));
                    g.drawString(q.text, tx + 2, (int) q.y + 2);
                    g.setColor(c);
                    g.drawString(q.text, tx, (int) q.y);
                }
            }

            if (bannerStart >= 0) {
                double p = (now - bannerStart) / 1.5e9;
                double sc = p < 0.18 ? easeOutBack(p / 0.18) : 1.0;
                float alpha = (float) Math.max(0.0, Math.min(1.0, p > 0.78 ? 1.0 - (p - 0.78) / 0.22 : 1.0));
                int cy = (int) (h * 0.40);
                Graphics2D b = (Graphics2D) g.create();
                b.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                b.translate(w / 2.0, cy);
                b.scale(1.0, Math.max(0.02, sc));
                b.setColor(new Color(10, 16, 36, 205));
                b.fillRect(-w / 2, -62, w, 124);
                b.setColor(bannerColor);
                b.fillRect(-w / 2, -62, w, 3);
                b.fillRect(-w / 2, 59, w, 3);
                b.dispose();
                Graphics2D t = (Graphics2D) g.create();
                t.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                t.translate(w / 2.0, cy);
                t.scale(Math.max(0.02, sc), Math.max(0.02, sc));
                t.setFont(new Font("SansSerif", Font.BOLD, 56));
                FontMetrics fm = t.getFontMetrics();
                t.setColor(new Color(0, 0, 0, 150));
                t.drawString(bannerTitle, -fm.stringWidth(bannerTitle) / 2 + 3, 5);
                t.setColor(Color.WHITE);
                t.drawString(bannerTitle, -fm.stringWidth(bannerTitle) / 2, 2);
                t.setFont(new Font("SansSerif", Font.BOLD, 18));
                fm = t.getFontMetrics();
                t.setColor(bannerColor);
                t.drawString(bannerSub, -fm.stringWidth(bannerSub) / 2, 40);
                t.dispose();
            }
            g.dispose();
        }
    }

    private final JFrame frame = new JFrame("Squad Flip: Who's Who?");
    private final JPanel root = new JPanel(new CardLayout());
    private final JPanel albumGrid = new JPanel(new GridLayout(0, 4, 12, 12));
    private final JLabel albumCountLabel = new JLabel("0 / 0 CARDS COLLECTED");
    private final JPanel board = new JPanel() {
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color c = getBackground();
            g.setColor(new Color(0, 0, 0, 70));
            g.fillRoundRect(3, 7, w - 6, h - 7, 34, 34);
            g.setPaint(new GradientPaint(0, 0, mix(c, Color.WHITE, 0.08f), 0, h, mix(c, Color.BLACK, 0.18f)));
            g.fillRoundRect(0, 0, w - 4, h - 7, 34, 34);
            g.setColor(new Color(255, 255, 255, 45));
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(1, 1, w - 6, h - 9, 32, 32);
            g.dispose();
        }
    };
    private final EffectsPane effects = new EffectsPane();
    private final PillLabel levelLabel = new PillLabel(PANEL_DARK);
    private final PillLabel moneyLabel = new PillLabel(PANEL_DARK);
    private final PillLabel timerLabel = new PillLabel(PANEL_DARK);
    private final JLabel statusLabel = new JLabel("Find every matching pair!");
    private final JLabel inventoryLabel = new JLabel();
    private final PillLabel levelSelectCoinsLabel = new PillLabel(PANEL_DARK);
    private final PillLabel mainMenuCoinsLabel = new PillLabel(PANEL_DARK);
    private final JLabel mainMenuNameLabel = new JLabel("Ace · Lv 1");
    private final XpBar xpBar = new XpBar();
    private final JButton homeButton = new JButton("← LEVELS");
    private final JButton pauseButton = new JButton("Ⅱ PAUSE");
    private final JButton shopButton = new JButton("🛒 Shop");
    private final JButton magnifyButton = new JButton("🔍 Magnify");
    private final JButton hintButton = new JButton("💡 Hint");
    private final JButton shieldButton = new JButton("🛡 Shield");
    private final JButton timerButton = new JButton("⏱ +1 second");
    private final java.util.List<Card> cards = new ArrayList<>();
    private final Random random = new Random();
    private final javax.swing.Timer clock;
    private AudioClip startClip, winClip, starClip;
    private MusicPlayer music;
    private boolean musicEnabled = true;
    private String musicScene = "";
    private final java.util.List<JButton> musicButtons = new ArrayList<>();
    private static AudioClip uiClickClip;
    private static AudioClip matchClip;
    private static AudioClip unlockClip;
    private static AudioClip unlockClipLoudLayer;
    private final JButton[] levelButtons = new JButton[MAX_LEVEL + 1];

    private int level = 1;
    private boolean gameCompleted = false;
    private int highestUnlockedLevel = 1;
    private int selectedCardBack = 0;
    private final boolean[] unlockedCardBacks = {true, false, false, false};
    private final int[] starsByLevel = new int[MAX_LEVEL + 1];
    private int coins = 0;
    private int secondsLeft = 300;
    private int levelTimeLimit = 300;
    /** Consecutive wrong pair attempts since the last match or shuffle. */
    private int wrongGuesses = 0;
    /** All unshielded wrong pair attempts in this level; used for star scoring. */
    private int totalWrongGuesses = 0;
    private int matchedPairs = 0;
    private int comboStreak = 0;
    private int levelGeneration = 0;
    private int firstCard = -1;
    private int secondCard = -1;
    private int lockCount = 0;
    private int distractionsRemaining = 0;
    private boolean distractionActive = false;
    private javax.swing.Timer distractionTimer;
    private javax.swing.Timer levelPulseTimer;
    private int magnifiers = 0, hints = 0, shields = 0, timerBoosts = 0;
    private boolean shieldArmed = false;
    private boolean magnifierActive = false;
    private int magnifierSeconds = 0;
    private javax.swing.Timer magnifierTimer;
    private int boardRows;
    private int boardColumns;
    private Card activeLure;
    private String currentUsername;
    private File accountSaveFile;
    private final Set<String> discoveredImages = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private boolean openAlbumAfterAccount;
    private boolean chainLockIntroSeen;
    private int chainedLocksRemaining;
    private int shopPauseDepth;
    private boolean shopClockWasRunning;

    private static class Card {
        String face;
        String imageName;
        String variant;
        final AnimatedCardButton button = new AnimatedCardButton();
        boolean matched = false;
        boolean revealed = false;
        boolean lure = false;        // flipped itself face-up to tempt the player
        boolean lureFake = false;    // true: shows the wrong picture (a trick); false: it really is the match
        boolean chainedLocked = false;
        Card(String face, String imageName, String variant) {
            setFace(face);
        }
        void setFace(String face) {
            this.face = face;
            String[] parts = face.split("\\|");
            imageName = parts[0];
            variant = parts[1];
        }
    }

    public MemoryFogGame() {
        clock = new javax.swing.Timer(1000, e -> tick());
        loadSettings();
        preloadAudio();
        buildWindow();
    }

    private void buildWindow() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 680));
        frame.setSize(1000, 800);
        frame.setLocationRelativeTo(null);
        JPanel gameScreen = new AnimatedBackground(new BorderLayout(12, 12), 18);
        gameScreen.setBorder(new EmptyBorder(16, 18, 16, 18));

        JPanel header = new JPanel(new BorderLayout(10, 7)) {
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                d.height = Math.max(d.height, 62);   // fixed height, so the board doesn't jump when the timer grows
                return d;
            }
        };
        header.setOpaque(false);
        JLabel title = new JLabel("<html><font color='#FFC93C'>SQUAD FLIP</font>: WHO'S WHO?</html>");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 26));
        JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        stats.setOpaque(false);
        for (JLabel label : new JLabel[]{levelLabel, moneyLabel, timerLabel}) {
            label.setForeground(Color.WHITE);
            label.setFont(new Font("SansSerif", Font.BOLD, 15));
            stats.add(label);
        }
        moneyLabel.setForeground(KIT_YELLOW);
        JPanel headerTop = new JPanel(new BorderLayout(10, 0));
        headerTop.setOpaque(false);
        JPanel leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftHeader.setOpaque(false);
        styleButton(homeButton);
        homeButton.setFont(new Font("SansSerif", Font.BOLD, 11));
        styleButton(pauseButton, "primary");
        pauseButton.setFont(new Font("SansSerif", Font.BOLD, 11));
        leftHeader.add(homeButton);
        leftHeader.add(pauseButton);
        leftHeader.add(createMusicButton());
        leftHeader.add(title);
        headerTop.add(leftHeader, BorderLayout.WEST);
        headerTop.add(stats, BorderLayout.EAST);
        header.add(headerTop, BorderLayout.CENTER);

        board.setBackground(PANEL);
        board.setOpaque(false);
        board.setBorder(new EmptyBorder(16, 16, 22, 20));
        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(board, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        footer.add(statusLabel);
        footer.add(Box.createVerticalStrut(9));
        JPanel powerups = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        powerups.setOpaque(false);
        for (JButton b : new JButton[]{magnifyButton, hintButton, shieldButton, timerButton, shopButton}) {
            styleButton(b, b == shopButton ? "primary" : "secondary");
            powerups.add(b);
        }
        inventoryLabel.setForeground(new Color(207, 219, 244));
        inventoryLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        inventoryLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        footer.add(powerups);
        footer.add(Box.createVerticalStrut(7));
        footer.add(inventoryLabel);

        gameScreen.add(header, BorderLayout.NORTH);
        gameScreen.add(center, BorderLayout.CENTER);
        gameScreen.add(footer, BorderLayout.SOUTH);

        JPanel mainMenuScreen = createMainMenuScreen();
        JPanel levelSelectScreen = createLevelSelectScreen();
        JPanel albumLibraryScreen = createAlbumLibraryScreen();
        root.add(mainMenuScreen, "main");
        root.add(levelSelectScreen, "levels");
        root.add(albumLibraryScreen, "album");
        root.add(gameScreen, "game");
        frame.setContentPane(root);
        frame.setGlassPane(effects);
        effects.setVisible(true);

        gameScreen.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "pause-game");
        gameScreen.getActionMap().put("pause-game", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { showPauseDialog(); }
        });

        shopButton.addActionListener(e -> showShop());
        magnifyButton.addActionListener(e -> useMagnifier());
        hintButton.addActionListener(e -> useHint());
        shieldButton.addActionListener(e -> useShield());
        timerButton.addActionListener(e -> useTimerBoost());
        homeButton.addActionListener(e -> returnToLevelSelect());
        pauseButton.addActionListener(e -> showPauseDialog());
        startLevelPulse();
        frame.setVisible(true);
        setMusicScene("menu");   // the menu music starts by itself when the game opens
    }

    private void showHowToPlay() {
        JOptionPane.showMessageDialog(frame,
            "Choose any unlocked level from the table.\n"
            + "Flip two cards to find a matching pair. Every third wrong guess shuffles face-down cards.\n"
            + "From Level 6, a card may flip by itself to trick you. Sometimes it really is your match!\n"
            + "Clear a level to unlock the next one and earn " + LEVEL_REWARD + " coins. Stars show your best rating.",
            "How to play", JOptionPane.INFORMATION_MESSAGE);
    }

    private void openLevelSelect() {
        refreshLevelSelect();
        animateLevelTiles();
        showScreen("levels");
    }

    private JPanel createMainMenuScreen() {
        JPanel menu = new AnimatedBackground(new BorderLayout(), 34);
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(22, 28, 12, 28));

        // Profile bar: avatar, name + progress, coins, help
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        JPanel profile = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        profile.setOpaque(false);
        JPanel nameBox = new JPanel();
        nameBox.setOpaque(false);
        nameBox.setLayout(new BoxLayout(nameBox, BoxLayout.Y_AXIS));
        mainMenuNameLabel.setForeground(Color.WHITE);
        mainMenuNameLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        mainMenuNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        xpBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameBox.add(mainMenuNameLabel);
        nameBox.add(Box.createVerticalStrut(5));
        nameBox.add(xpBar);
        profile.add(new AvatarBadge());
        profile.add(nameBox);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tools.setOpaque(false);
        mainMenuCoinsLabel.setForeground(KIT_YELLOW);
        mainMenuCoinsLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        JButton info = new JButton("?");
        styleButton(info);
        info.setPreferredSize(new Dimension(44, 44));
        info.setToolTipText("How to play");
        info.addActionListener(event -> showHowToPlay());
        tools.add(mainMenuCoinsLabel);
        tools.add(createMusicButton());
        tools.add(info);
        top.add(profile, BorderLayout.WEST);
        top.add(tools, BorderLayout.EAST);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        ShimmerLabel logo = new ShimmerLabel("SQUAD", "FLIP");
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel tagline = new JLabel("Who’s who? Flip to find out.");
        tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
        tagline.setForeground(new Color(201, 204, 238));
        tagline.setFont(new Font("SansSerif", Font.PLAIN, 15));

        JPanel cardPreview = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        cardPreview.setOpaque(false);
        cardPreview.setMaximumSize(new Dimension(620, 146));
        cardPreview.add(new FloatingCard(null, 0.0, KIT_PINK, -12));
        cardPreview.add(new FloatingCard("blue-face.png", 1.3, KIT_TEAL, 0));
        cardPreview.add(new FloatingCard("tandang-nick.jpg", 2.6, KIT_YELLOW, 12));

        JButton play = new JButton("▶   PLAY GAME");
        JButton shop = new JButton("🛒  SHOP");
        JButton howTo = new JButton("?  HOW TO PLAY");
        JButton backs = new JButton("🎨  CARD BACKS");
        styleButton(play, "primary");
        styleButton(shop);
        styleButton(howTo);
        styleButton(backs, "success");
        play.setFont(new Font("SansSerif", Font.BOLD, 24));
        play.setAlignmentX(Component.CENTER_ALIGNMENT);
        play.setPreferredSize(new Dimension(340, 64));
        play.setMaximumSize(new Dimension(340, 64));
        backs.setAlignmentX(Component.CENTER_ALIGNMENT);
        backs.setPreferredSize(new Dimension(340, 48));
        backs.setMaximumSize(new Dimension(340, 48));
        backs.setFont(new Font("SansSerif", Font.BOLD, 15));
        shop.setFont(new Font("SansSerif", Font.BOLD, 15));
        howTo.setFont(new Font("SansSerif", Font.BOLD, 15));
        JPanel row = new JPanel(new GridLayout(1, 2, 12, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.CENTER_ALIGNMENT);
        row.setPreferredSize(new Dimension(340, 48));
        row.setMaximumSize(new Dimension(340, 48));
        row.add(shop);
        row.add(howTo);
        play.addActionListener(event -> showAccountDialog());
        shop.addActionListener(event -> showShop());
        howTo.addActionListener(event -> showHowToPlay());
        backs.addActionListener(event -> showCardBackShop());

        center.add(Box.createVerticalGlue());
        center.add(logo);
        center.add(tagline);
        center.add(Box.createVerticalStrut(10));
        center.add(cardPreview);
        center.add(Box.createVerticalStrut(14));
        center.add(play);
        center.add(Box.createVerticalStrut(14));
        center.add(row);
        center.add(Box.createVerticalStrut(14));
        center.add(backs);
        center.add(Box.createVerticalGlue());

        content.add(top, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        NavBar nav = new NavBar(new String[]{"🏠", "🎯", "🖼", "🛒", "🚪"},
            new String[]{"HOME", "LEVELS", "ALBUM", "SHOP", "QUIT"}, 0,
            new Runnable[]{() -> { }, this::openLevelSelect, this::openAlbumLibrary,
                this::showShop, () -> frame.dispose()});
        menu.add(content, BorderLayout.CENTER);
        menu.add(nav, BorderLayout.SOUTH);
        return menu;
    }

    private JPanel createLevelSelectScreen() {
        JPanel screen = new AnimatedBackground(new BorderLayout(), 26);
        JPanel content = new JPanel(new BorderLayout(16, 14));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(18, 24, 12, 24));

        JPanel heading = new JPanel(new BorderLayout(10, 6));
        heading.setOpaque(false);
        JPanel headingText = new JPanel();
        headingText.setOpaque(false);
        headingText.setLayout(new BoxLayout(headingText, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("<html><font color='#FFC93C'>SELECT</font> A LEVEL</html>");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 30));
        JLabel subtitle = new JLabel("CLEARED LEVELS SHOW YOUR STARS");
        subtitle.setForeground(KIT_YELLOW);
        subtitle.setFont(new Font("SansSerif", Font.BOLD, 12));
        headingText.add(title);
        headingText.add(Box.createVerticalStrut(4));
        headingText.add(subtitle);
        levelSelectCoinsLabel.setForeground(KIT_YELLOW);
        levelSelectCoinsLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        JButton info = new JButton("?");
        styleButton(info);
        info.setPreferredSize(new Dimension(44, 44));
        info.setToolTipText("How to play");
        info.addActionListener(event -> showHowToPlay());
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tools.setOpaque(false);
        tools.add(levelSelectCoinsLabel);
        tools.add(createMusicButton());
        tools.add(info);
        heading.add(headingText, BorderLayout.WEST);
        heading.add(tools, BorderLayout.EAST);

        JPanel grid = new JPanel(new GridLayout(10, 5, 9, 8));
        grid.setOpaque(false);
        for (int number = 1; number <= MAX_LEVEL; number++) {
            final int selectedLevel = number;
            JButton tile = new JButton();
            tile.setFont(new Font("SansSerif", Font.BOLD, 13));
            tile.setPreferredSize(new Dimension(150, 48));
            installFancy(tile);
            tile.setBorder(new EmptyBorder(2, 6, 4, 6));
            tile.addActionListener(event -> {
                if (selectedLevel > highestUnlockedLevel) return;
                if (selectedLevel == 15 && !chainLockIntroSeen) {
                    showChainLockIntro();
                    chainLockIntroSeen = true;
                    saveProgress();
                }
                level = selectedLevel;
                gameCompleted = false;
                requestLevelStartAudio();
                showScreen("game");
                startLevel();
            });
            levelButtons[number] = tile;
            grid.add(tile);
        }

        JScrollPane scroller = new JScrollPane(grid);
        scroller.setBorder(null);
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);
        scroller.getVerticalScrollBar().setUnitIncrement(18);

        content.add(heading, BorderLayout.NORTH);
        content.add(scroller, BorderLayout.CENTER);
        NavBar nav = new NavBar(new String[]{"🏠", "🎯", "🖼", "🛒", "🚪"},
            new String[]{"HOME", "LEVELS", "ALBUM", "SHOP", "QUIT"}, 1,
            new Runnable[]{() -> showScreen("main"), () -> { }, this::openAlbumLibrary,
                this::showShop, () -> frame.dispose()});
        screen.add(content, BorderLayout.CENTER);
        screen.add(nav, BorderLayout.SOUTH);
        refreshLevelSelect();
        return screen;
    }

    private JPanel createAlbumLibraryScreen() {
        JPanel screen = new AnimatedBackground(new BorderLayout(), 26);
        JPanel content = new JPanel(new BorderLayout(14, 14));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(22, 26, 14, 26));

        JPanel heading = new JPanel(new BorderLayout(8, 8));
        heading.setOpaque(false);
        JLabel title = new JLabel("MY CARD ALBUM");
        title.setForeground(KIT_YELLOW);
        title.setFont(new Font("SansSerif", Font.BOLD, 25));
        albumCountLabel.setForeground(KIT_TEAL);
        albumCountLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        JLabel subtitle = new JLabel("Flip a card in a level to add it to your collection.");
        subtitle.setForeground(new Color(193, 202, 231));
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 13));
        JPanel titleStack = new JPanel();
        titleStack.setOpaque(false);
        titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));
        titleStack.add(title);
        titleStack.add(Box.createVerticalStrut(5));
        titleStack.add(subtitle);
        heading.add(titleStack, BorderLayout.WEST);
        heading.add(albumCountLabel, BorderLayout.EAST);

        albumGrid.setOpaque(false);
        albumGrid.setBorder(new EmptyBorder(4, 4, 12, 4));
        JScrollPane scroller = new JScrollPane(albumGrid);
        scroller.setBorder(BorderFactory.createLineBorder(new Color(60, 66, 124), 1));
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);
        scroller.getVerticalScrollBar().setUnitIncrement(20);

        content.add(heading, BorderLayout.NORTH);
        content.add(scroller, BorderLayout.CENTER);
        NavBar nav = new NavBar(new String[]{"🏠", "🎯", "🖼", "🛒", "🚪"},
            new String[]{"HOME", "LEVELS", "ALBUM", "SHOP", "QUIT"}, 2,
            new Runnable[]{() -> showScreen("main"), this::openLevelSelect, () -> { },
                this::showShop, () -> frame.dispose()});
        screen.add(content, BorderLayout.CENTER);
        screen.add(nav, BorderLayout.SOUTH);
        refreshAlbumLibrary();
        return screen;
    }

    private void openAlbumLibrary() {
        if (currentUsername == null || accountSaveFile == null) {
            JOptionPane.showMessageDialog(frame,
                "Sign in or create a player account first to save your card collection.",
                "Player account needed", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        refreshAlbumLibrary();
        showScreen("album");
    }

    private void refreshAlbumLibrary() {
        if (albumGrid == null || albumCountLabel == null) return;
        albumGrid.removeAll();
        int collected = 0;
        for (String imageName : CARD_IMAGES) {
            boolean found = discoveredImages.contains(imageName);
            if (found) collected++;
            albumGrid.add(createAlbumTile(imageName, found));
        }
        albumCountLabel.setText(collected + " / " + CARD_IMAGES.length + " CARDS COLLECTED");
        albumGrid.revalidate();
        albumGrid.repaint();
    }

    private JPanel createAlbumTile(String imageName, boolean collected) {
        JPanel tile = new JPanel(new BorderLayout(0, 7));
        tile.setOpaque(true);
        tile.setBackground(collected ? new Color(35, 47, 83) : new Color(30, 33, 53));
        Color frameColor = collected ? KIT_TEAL : new Color(76, 80, 106);
        tile.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(frameColor, collected ? 2 : 1),
            new EmptyBorder(8, 8, 9, 8)));
        tile.setPreferredSize(new Dimension(165, 174));

        JPanel art = new JPanel(new BorderLayout());
        art.setPreferredSize(new Dimension(145, 112));
        art.setBackground(collected ? PANEL_DARK : new Color(48, 50, 65));
        if (collected) {
            BufferedImage image = loadCardImage(imageName);
            JLabel picture = new JLabel(image == null ? "IMAGE MISSING" : "", SwingConstants.CENTER);
            picture.setForeground(new Color(180, 188, 215));
            if (image != null) picture.setIcon(fitImage(image, 142, 108));
            art.add(picture, BorderLayout.CENTER);
        } else {
            JLabel lock = new JLabel("🔒", SwingConstants.CENTER);
            lock.setForeground(new Color(139, 143, 164));
            lock.setFont(new Font("SansSerif", Font.BOLD, 34));
            JLabel unknown = new JLabel("UNDISCOVERED", SwingConstants.CENTER);
            unknown.setForeground(new Color(155, 158, 177));
            unknown.setFont(new Font("SansSerif", Font.BOLD, 10));
            art.add(lock, BorderLayout.CENTER);
            art.add(unknown, BorderLayout.SOUTH);
        }

        JLabel name = new JLabel(collected ? albumImageTitle(imageName) : "???", SwingConstants.CENTER);
        name.setForeground(collected ? Color.WHITE : new Color(158, 162, 183));
        name.setFont(new Font("SansSerif", Font.BOLD, 13));
        tile.add(art, BorderLayout.CENTER);
        tile.add(name, BorderLayout.SOUTH);
        tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter openCard = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (collected) showAlbumCard(imageName);
                else JOptionPane.showMessageDialog(frame,
                    "Flip this image card in a level to unlock it in your album.",
                    "Card not discovered", JOptionPane.INFORMATION_MESSAGE);
            }
        };
        tile.addMouseListener(openCard);
        art.addMouseListener(openCard);
        name.addMouseListener(openCard);
        return tile;
    }

    private String albumImageTitle(String imageName) {
        String title = imageName.replaceFirst("(?i)\\.[^.]+$", "")
            .replace('_', ' ').replace('-', ' ').trim();
        if (title.isEmpty()) return imageName;
        return title.substring(0, 1).toUpperCase(Locale.ROOT) + title.substring(1);
    }

    private static ImageIcon fitImage(BufferedImage image, int maxWidth, int maxHeight) {
        double scale = Math.min((double) maxWidth / image.getWidth(), (double) maxHeight / image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        return new ImageIcon(image.getScaledInstance(width, height, Image.SCALE_SMOOTH));
    }

    private void showAlbumCard(String imageName) {
        BufferedImage image = loadCardImage(imageName);
        if (image == null) return;
        JDialog dialog = new JDialog(frame, albumImageTitle(imageName), true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(520, 550);
        dialog.setResizable(false);
        JPanel panel = new JPanel(new BorderLayout(10, 14));
        panel.setBackground(NAVY);
        panel.setBorder(new EmptyBorder(18, 20, 18, 20));
        JLabel title = new JLabel(albumImageTitle(imageName), SwingConstants.CENTER);
        title.setForeground(KIT_YELLOW);
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        JLabel picture = new JLabel(fitImage(image, 460, 420), SwingConstants.CENTER);
        picture.setOpaque(true);
        picture.setBackground(PANEL_DARK);
        picture.setBorder(BorderFactory.createLineBorder(KIT_TEAL, 2));
        JButton close = new JButton("CLOSE");
        styleButton(close);
        close.addActionListener(event -> dialog.dispose());
        panel.add(title, BorderLayout.NORTH);
        panel.add(picture, BorderLayout.CENTER);
        panel.add(close, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void showChainLockIntro() {
        JDialog dialog = new JDialog(frame, "NEW OBSTACLE: CHAIN LOCKS", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(700, 540);
        dialog.setResizable(false);

        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBackground(NAVY);
        panel.setBorder(new EmptyBorder(18, 22, 18, 22));
        JLabel title = new JLabel("CHAIN LOCKS AHEAD!", SwingConstants.CENTER);
        title.setForeground(KIT_YELLOW);
        title.setFont(new Font("SansSerif", Font.BOLD, 25));
        panel.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setOpaque(false);
        JLabel illustration = new JLabel("", SwingConstants.CENTER);
        File illustrationFile = findAssetFile("lock card.png");
        try {
            if (illustrationFile != null) {
                BufferedImage image = ImageIO.read(illustrationFile);
                if (image != null) {
                    illustration.setIcon(new ImageIcon(image.getScaledInstance(620, 350, Image.SCALE_SMOOTH)));
                }
            }
        } catch (Exception ignored) { }
        center.add(illustration, BorderLayout.CENTER);
        JLabel instructions = new JLabel(
            "Match a free pair to break one chain. Locked cards cannot be flipped, and their locks move when cards shuffle.",
            SwingConstants.CENTER);
        instructions.setForeground(new Color(216, 225, 247));
        instructions.setFont(new Font("SansSerif", Font.BOLD, 14));
        center.add(instructions, BorderLayout.SOUTH);
        panel.add(center, BorderLayout.CENTER);

        JButton continueButton = new JButton("GOT IT · START LEVEL 15");
        styleButton(continueButton, "primary");
        continueButton.addActionListener(event -> dialog.dispose());
        panel.add(continueButton, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.getRootPane().setDefaultButton(continueButton);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void refreshLevelSelect() {
        levelSelectCoinsLabel.setText("🪙 " + coins);
        mainMenuCoinsLabel.setText("🪙 " + coins);
        int totalStars = 0;
        for (int earned : starsByLevel) totalStars += earned;
        String displayName = currentUsername == null ? "Player" : currentUsername;
        if (displayName.length() > 12) displayName = displayName.substring(0, 12);
        mainMenuNameLabel.setText(displayName + " · Lv " + highestUnlockedLevel);
        xpBar.setFraction(totalStars / (MAX_LEVEL * 3.0));
        for (int number = 1; number <= MAX_LEVEL; number++) {
            JButton tile = levelButtons[number];
            if (tile == null) continue;
            boolean unlocked = number <= highestUnlockedLevel;
            int stars = starsByLevel[number];
            String starText = "★ ".repeat(stars) + "☆ ".repeat(3 - stars);
            tile.putClientProperty("pulse", 0f);
            if (unlocked) {
                tile.setText("<html><center>LEVEL " + number + "<br><font color='#FFD76E'>"
                    + starText + "</font></center></html>");
                tile.setEnabled(true);
                styleAs(tile, "secondary");
                tile.setBackground(new Color(62, 74, 140));
                tile.putClientProperty("depth", new Color(30, 36, 80));
                if (number == level) {
                    tile.setBackground(new Color(30, 110, 100));
                    tile.putClientProperty("depth", new Color(16, 66, 62));
                    tile.putClientProperty("outline", KIT_TEAL);
                } else {
                    tile.putClientProperty("outline", null);
                }
                tile.setToolTipText("Play Level " + number + (stars > 0 ? " — best: " + stars + " stars" : ""));
            } else {
                tile.setText("<html><center>🔒<br>LEVEL " + number + "</center></html>");
                tile.setEnabled(false);
                styleAs(tile, "disabled");
                tile.setBackground(new Color(33, 37, 74));
                tile.setForeground(new Color(125, 131, 172));
                tile.putClientProperty("depth", new Color(20, 23, 50));
                tile.putClientProperty("outline", null);
                tile.setToolTipText("Clear Level " + (number - 1) + " to unlock");
            }
            tile.repaint();
        }
    }

    /** Level tiles pop in one after another whenever the level table is opened. */
    private void animateLevelTiles() {
        for (int n = 1; n <= MAX_LEVEL; n++) {
            JButton tile = levelButtons[n];
            if (tile == null) continue;
            tile.putClientProperty("pop", 0f);
            final int delay = n * 14;
            javax.swing.Timer starter = new javax.swing.Timer(delay, e -> {
                final long started = System.nanoTime();
                javax.swing.Timer anim = new javax.swing.Timer(16, null);
                anim.addActionListener(ev -> {
                    double p = Math.min(1.0, (System.nanoTime() - started) / 320e6);
                    tile.putClientProperty("pop", (float) p);
                    tile.repaint();
                    if (p >= 1.0) anim.stop();
                });
                anim.start();
            });
            starter.setRepeats(false);
            starter.start();
        }
    }

    /** The next level to play glows with a gentle golden pulse. */
    private void startLevelPulse() {
        levelPulseTimer = new javax.swing.Timer(40, e -> {
            if (levelButtons[1] == null || !levelButtons[1].isShowing()) return;
            JButton target = levelButtons[Math.min(highestUnlockedLevel, MAX_LEVEL)];
            if (target != null) {
                target.putClientProperty("pulse", (float) ((Math.sin(System.nanoTime() / 1e9 * 3.5) + 1) / 2));
                target.repaint();
            }
        });
        levelPulseTimer.start();
    }

    private void returnToLevelSelect() {
        if (lockCount > 0) {
            JOptionPane.showMessageDialog(frame,
                "Wait for the card animation to finish, then return to the level table.",
                "Cards are moving", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        clock.stop();
        deactivateMagnifier();
        clearDistraction();
        refreshLevelSelect();
        animateLevelTiles();
        showScreen("levels");
    }

    private void styleButton(JButton b) {
        styleButton(b, "secondary");
    }

    private void styleButton(JButton b, String variant) {
        b.setFont(new Font("SansSerif", Font.BOLD, 13));
        installFancy(b);
        styleAs(b, variant);
    }

    private void showPauseDialog() {
        if (!clock.isRunning()) return;
        if (lockCount > 0) {
            statusLabel.setText("Pause is available after the card animation finishes.");
            return;
        }

        boolean obstacleWasRunning = distractionTimer != null && distractionTimer.isRunning();
        boolean magnifierWasRunning = magnifierTimer != null && magnifierTimer.isRunning();
        clock.stop();
        if (obstacleWasRunning) distractionTimer.stop();
        if (magnifierWasRunning) magnifierTimer.stop();

        JDialog dialog = new JDialog(frame, "GAME PAUSED", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(440, 330);
        dialog.setResizable(false);
        JPanel panel = new JPanel(new BorderLayout(12, 16));
        panel.setBackground(NAVY);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(KIT_YELLOW, 2), new EmptyBorder(24, 28, 22, 28)));
        JLabel title = new JLabel("GAME PAUSED", SwingConstants.CENTER);
        title.setForeground(KIT_YELLOW);
        title.setFont(new Font("SansSerif", Font.BOLD, 28));
        JLabel subtitle = new JLabel("Your timer is paused. Take a breath.", SwingConstants.CENTER);
        subtitle.setForeground(new Color(196, 210, 238));
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 15));

        JButton resume = new JButton("▶  RESUME");
        JButton restart = new JButton("↻  RESTART LEVEL");
        JButton levels = new JButton("LEVEL SELECT");
        styleButton(resume, "primary");
        styleButton(restart, "secondary");
        styleButton(levels, "secondary");
        levels.setEnabled(lockCount == 0);
        JPanel actions = new JPanel(new GridLayout(3, 1, 0, 9));
        actions.setOpaque(false);
        actions.add(resume);
        actions.add(restart);
        actions.add(levels);

        final int[] choice = {0}; // 0 resumes, 1 restarts, 2 returns to the level table.
        resume.addActionListener(event -> dialog.dispose());
        restart.addActionListener(event -> { choice[0] = 1; dialog.dispose(); });
        levels.addActionListener(event -> { choice[0] = 2; dialog.dispose(); });
        dialog.getRootPane().setDefaultButton(resume);
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "resume-game");
        dialog.getRootPane().getActionMap().put("resume-game", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { dialog.dispose(); }
        });
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 4));
        heading.setOpaque(false);
        heading.add(title);
        heading.add(subtitle);
        panel.add(heading, BorderLayout.NORTH);
        panel.add(actions, BorderLayout.CENTER);
        dialog.setContentPane(panel);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);

        if (choice[0] == 1) {
            startLevel();
        } else if (choice[0] == 2) {
            returnToLevelSelect();
        } else {
            clock.start();
            if (obstacleWasRunning && distractionTimer != null) distractionTimer.start();
            if (magnifierWasRunning && magnifierTimer != null) magnifierTimer.start();
            updateLabels();
        }
    }

    private void startLevel() {
        levelGeneration++;
        effects.cancelMatch();
        clock.stop();
        deactivateMagnifier();
        clearDistraction();
        secondsLeft = 300;
        wrongGuesses = 0;
        totalWrongGuesses = 0;
        matchedPairs = 0;
        comboStreak = 0;
        firstCard = secondCard = -1;
        // Keep card input locked until the opening memory preview has finished.
        lockCount = 1;
        setLevelControlsEnabled(false);
        distractionsRemaining = level >= 6
            ? (isChallengeLevel() ? 5 + random.nextInt(3) : 3 + random.nextInt(3))
            : 0;
        chainedLocksRemaining = 0;
        cards.clear();

        int pairs = Math.min(level + 1, CARD_IMAGES.length * VARIANTS_PER_IMAGE);
        levelTimeLimit = 50 + pairs * 7;
        secondsLeft = levelTimeLimit;
        boolean challenge = isChallengeLevel();
        int theme = currentThemeIndex();
        board.setBackground(BOARD_THEMES[theme]);
        java.util.List<String> faces = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            String imageName = CARD_IMAGES[i % CARD_IMAGES.length];
            String variant = String.valueOf((char) ('A' + i / CARD_IMAGES.length));
            String faceId = imageName + "|" + variant;
            faces.add(faceId);
            faces.add(faceId);
        }
        Collections.shuffle(faces, random);
        if (level == 15) {
            chainedLocksRemaining = 1;
        } else if (level > 15) {
            int appearanceChance = Math.min(70, 35 + (level - 15) * 2);
            if (random.nextInt(100) < appearanceChance) {
                int maxLocks = 1 + Math.min(3, (level - 15) / 10);
                chainedLocksRemaining = Math.min(pairs - 1, 1 + random.nextInt(maxLocks));
            }
        }
        Set<Integer> chainedPositions = new HashSet<>();
        while (chainedPositions.size() < chainedLocksRemaining) {
            chainedPositions.add(random.nextInt(faces.size()));
        }
        board.removeAll();
        boardColumns = Math.min(10, Math.max(4, (int)Math.ceil(Math.sqrt(faces.size() * 1.4))));
        boardRows = (int)Math.ceil((double)faces.size() / boardColumns);
        board.setLayout(new CardGridLayout(8));
        for (int i = 0; i < faces.size(); i++) {
            String[] faceParts = faces.get(i).split("\\|");
            Card card = new Card(faces.get(i), faceParts[0], faceParts[1]);
            card.button.setFont(new Font("SansSerif", Font.BOLD, 34));
            card.button.setFocusPainted(false);
            card.button.setForeground(new Color(0, 0, 0, 0));
            card.button.setBackground(getCardBackColor());
            card.button.setContentAreaFilled(false);
            card.button.setOpaque(false);
            card.button.setRolloverEnabled(true);
            card.button.setBorder(new EmptyBorder(8, 8, 8, 8));
            card.button.setHorizontalAlignment(SwingConstants.CENTER);
            card.button.setVerticalAlignment(SwingConstants.CENTER);
            card.chainedLocked = chainedPositions.contains(i);
            card.button.setChainedLocked(card.chainedLocked);
            card.button.addActionListener(e -> flip(cards.indexOf(card)));
            card.button.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) {
                    if (magnifierActive && !card.matched && !card.revealed && !card.lure) {
                        card.button.showXray(loadCardImage(card.imageName), card.imageName, card.variant);
                        statusLabel.setText("X-ray view: card " + card.variant + " revealed while you hover.");
                    }
                }

                @Override public void mouseExited(MouseEvent event) {
                    if (card.button.isXrayVisible()) {
                        card.button.showImage(null, card.variant);
                        if (magnifierActive) {
                            statusLabel.setText("X-ray active — hover a card (" + magnifierSeconds + "s left).");
                        }
                    }
                }
            });
            cards.add(card);
            card.button.playDealIn((int) (i * 900.0 / faces.size()));
            board.add(card.button);
        }
        statusLabel.setText("Get ready! Memorize the cards when they appear.");
        updateLabels();
        board.revalidate();
        board.repaint();
        final int generation = levelGeneration;
        final int previewDelay = (int) ((Math.max(0, faces.size() - 1) * 900.0) / faces.size()) + 420;
        javax.swing.Timer previewStart = new javax.swing.Timer(previewDelay, event -> {
            if (generation != levelGeneration) return;
            statusLabel.setText("Memorize the cards!");
            for (int i = 0; i < cards.size(); i++) reveal(i);

            int previewDurationMs = 500 + (level - 1) * 100;
            javax.swing.Timer previewEnd = new javax.swing.Timer(previewDurationMs, previewEvent -> {
                if (generation != levelGeneration) return;
                for (int i = 0; i < cards.size(); i++) hide(i);
                statusLabel.setText("Get ready...");

                javax.swing.Timer beginPlay = new javax.swing.Timer(240, beginEvent -> {
                    if (generation != levelGeneration) return;
                    lockCount = 0;
                    setLevelControlsEnabled(true);
                    statusLabel.setText(challenge
                        ? "CHALLENGE LEVEL! Expect more tricky cards. " + pairs + " pairs — " + THEME_NAMES[theme] + " theme."
                        : "Find " + pairs + " pairs — " + THEME_NAMES[theme] + " theme.");
                    effects.banner("LEVEL " + level,
                        challenge ? "CHALLENGE LEVEL  •  " + pairs + " PAIRS"
                                  : pairs + " PAIRS  •  " + THEME_NAMES[theme] + " THEME",
                        challenge ? new Color(255, 120, 120) : new Color(136, 235, 196));
                    clock.start();
                });
                beginPlay.setRepeats(false);
                beginPlay.start();
            });
            previewEnd.setRepeats(false);
            previewEnd.start();
        });
        previewStart.setRepeats(false);
        previewStart.start();
    }

    private void setLevelControlsEnabled(boolean enabled) {
        for (JButton button : new JButton[]{homeButton, pauseButton, shopButton,
                magnifyButton, hintButton, shieldButton, timerButton}) {
            button.setEnabled(enabled);
        }
    }

    private void flip(int index) {
        if (lockCount > 0 || index < 0 || index >= cards.size()) return;
        Card card = cards.get(index);
        if (card.matched || card.revealed) return;
        if (card.chainedLocked) {
            playSound("wrong");
            statusLabel.setText("🔒 Locked by a chain! Match a free pair to break one lock.");
            return;
        }
        boolean mocked = false;
        if (activeLure != null) {
            mocked = activeLure == card && card.lureFake;   // the player fell for the trick
            releaseLure(card);
        }
        playSound("flip");
        reveal(index);
        if (firstCard < 0) {
            firstCard = index;
            maybeTriggerTrick(index);
            return;
        }
        secondCard = index;
        Card first = cards.get(firstCard);
        if (first.face.equals(card.face)) {
            final boolean newAlbumCard = discoverAlbumImage(first);
            final boolean resumeClockForDiscovery = newAlbumCard && clock.isRunning();
            final boolean resumeDistractionForDiscovery = newAlbumCard
                && distractionTimer != null && distractionTimer.isRunning();
            final boolean resumeMagnifierForDiscovery = newAlbumCard
                && magnifierTimer != null && magnifierTimer.isRunning();
            if (newAlbumCard) {
                clock.stop();
                if (resumeDistractionForDiscovery) distractionTimer.stop();
                if (resumeMagnifierForDiscovery) magnifierTimer.stop();
            }
            playSound("match");
            // A successful pair clears the consecutive-mistake counter for the shuffle rule.
            wrongGuesses = 0;
            first.matched = card.matched = true;
            first.button.setBackground(new Color(52, 126, 104));
            card.button.setBackground(new Color(52, 126, 104));
            first.button.setFrameColor(KIT_TEAL);
            card.button.setFrameColor(KIT_TEAL);
            matchedPairs++;
            boolean brokeChain = unlockOneChainedCard();
            firstCard = secondCard = -1;
            lockCount++;
            statusLabel.setText(brokeChain ? "Match! One chain lock broke!" : "Match! Nice one!");
            comboStreak++;
            Point pa = centerOf(first), pb = centerOf(card);
            effects.burst(pa, new Color(120, 235, 200), 6);
            effects.burst(pb, new Color(255, 214, 110), 6);
            Point mid = new Point((pa.x + pb.x) / 2, (pa.y + pb.y) / 2 - 28);
            if (comboStreak >= 2) {
                effects.floatText("COMBO x" + comboStreak + "!", mid, new Color(255, 214, 110));
            }
            // After the second card finishes flipping, both cards fly together, merge, and pop up on screen.
            final int generation = levelGeneration;
            javax.swing.Timer lift = new javax.swing.Timer(300, ev -> {
                if (generation != levelGeneration) return;
                Rectangle ra = boundsInEffects(first), rb = boundsInEffects(card);
                BufferedImage art = loadCardImage(first.imageName);
                first.button.setVisible(false);
                card.button.setVisible(false);
                effects.showMatch(art, first.variant, KIT_TEAL, ra, rb, newAlbumCard, () -> {
                    if (generation != levelGeneration || newAlbumCard) return;
                    lockCount--;
                    if (matchedPairs == cards.size() / 2) levelWon();
                }, () -> {
                    if (generation != levelGeneration || !newAlbumCard) return;
                    resumeNewDiscoveryTimers(resumeClockForDiscovery,
                        resumeDistractionForDiscovery, resumeMagnifierForDiscovery);
                    if (generation != levelGeneration) return;
                    lockCount--;
                    if (matchedPairs == cards.size() / 2) levelWon();
                });
            });
            lift.setRepeats(false);
            lift.start();
        } else {
            playSound("wrong");
            comboStreak = 0;
            cards.get(firstCard).button.playShake();
            card.button.playShake();
            final boolean shielded = shieldArmed;
            final boolean shuffleOnThisMiss;
            if (shielded) {
                shieldArmed = false;
                saveProgress();
                updateLabels();
                shuffleOnThisMiss = false;
            } else {
                wrongGuesses++;
                totalWrongGuesses++;
                shuffleOnThisMiss = wrongGuesses >= 3;
                if (shuffleOnThisMiss) wrongGuesses = 0;
            }
            int a = firstCard, b = secondCard;
            lockCount++;
            statusLabel.setText(shielded
                ? "🛡 Shield absorbed the mistake — no shuffle!"
                : shuffleOnThisMiss
                ? "⚠ Three wrong guesses! Unflipped cards are being shuffled!"
                : "Not a match — try again.");
            final int generation = levelGeneration;
            javax.swing.Timer pause = new javax.swing.Timer(850, e -> {
                if (generation != levelGeneration) return;
                hide(a);
                hide(b);
                firstCard = secondCard = -1;
                lockCount--;
                if (shuffleOnThisMiss) shuffleUnflippedCards();
                if (statusLabel.getText().startsWith("Not a match")) statusLabel.setText("Keep going!");
            });
            pause.setRepeats(false);
            pause.start();
            if (mocked) {
                card.button.playLaugh();
                effects.floatText("HA HA HA!", centerOf(card), new Color(255, 120, 170));
                playSound("laugh");
                statusLabel.setText("😂 Tricked! That card was a decoy.");
            }
        }
    }

    private Rectangle boundsInEffects(Card c) {
        return SwingUtilities.convertRectangle(c.button.getParent(), c.button.getBounds(), effects);
    }

    private Point centerOf(Card c) {
        return SwingUtilities.convertPoint(c.button, c.button.getWidth() / 2, c.button.getHeight() / 2, effects);
    }

    private void reveal(int index) {
        Card c = cards.get(index);
        c.revealed = true;
        c.button.showImage(loadCardImage(c.imageName), c.imageName, c.variant);
        c.button.setBackground(new Color(100, 126, 180));
        c.button.playFlip();
    }

    private void hide(int index) {
        Card c = cards.get(index);
        if (!c.matched) {
            c.revealed = false;
            c.button.showImage(null, c.variant);
            c.button.setBackground(getCardBackColor());
            c.button.playFlip();
        }
    }

    private boolean discoverAlbumImage(Card card) {
        if (accountSaveFile == null || card == null || card.imageName == null) return false;
        if (!discoveredImages.add(card.imageName)) return false;
        saveProgress();
        refreshAlbumLibrary();
        return true;
    }

    private void resumeNewDiscoveryTimers(boolean resumeClock,
                                          boolean resumeDistraction, boolean resumeMagnifier) {
        if (resumeClock && secondsLeft > 0) clock.start();
        if (resumeDistraction && distractionTimer != null) distractionTimer.start();
        if (resumeMagnifier && magnifierTimer != null) magnifierTimer.start();
    }

    /** A correct pair releases one remaining lock; the locked Card object stays with its moving button. */
    private boolean unlockOneChainedCard() {
        ArrayList<Card> locked = new ArrayList<>();
        for (Card card : cards) {
            if (card.chainedLocked && !card.matched) locked.add(card);
        }
        if (locked.isEmpty()) return false;
        Card released = locked.get(random.nextInt(locked.size()));
        released.chainedLocked = false;
        released.button.setChainedLocked(false);
        if (chainedLocksRemaining > 0) chainedLocksRemaining--;
        Point center = centerOf(released);
        effects.burst(center, KIT_YELLOW, 8);
        effects.floatText("CHAIN BROKEN!", center, KIT_YELLOW);
        return true;
    }

    private void shuffleUnflippedCards() {
        java.util.List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            if (!card.matched && !card.revealed) positions.add(i);
        }

        if (positions.size() < 2) return;
        playSound("shuffle");
        effects.flash(new Color(110, 170, 255, 60));
        statusLabel.setText("⚠ Cards are sliding to new spots!");
        lockCount++;

        java.util.List<Card> shuffledOrder = new ArrayList<>(cards);
        java.util.List<Card> movingCards = new ArrayList<>();
        for (int position : positions) movingCards.add(shuffledOrder.get(position));
        java.util.List<Card> originalMovingOrder = new ArrayList<>(movingCards);
        Collections.shuffle(movingCards, random);
        if (movingCards.equals(originalMovingOrder)) {
            Collections.rotate(movingCards, 1);
        }
        for (int i = 0; i < positions.size(); i++) {
            shuffledOrder.set(positions.get(i), movingCards.get(i));
        }

        java.util.List<Rectangle> destinations = new ArrayList<>();
        java.util.List<Rectangle> starts = new ArrayList<>();
        for (int position : positions) {
            destinations.add(cards.get(position).button.getBounds());
            starts.add(shuffledOrder.get(position).button.getBounds());
        }

        LayoutManager originalLayout = board.getLayout();
        final int generation = levelGeneration;
        board.setLayout(null);
        long started = System.nanoTime();
        int durationMs = 620;
        javax.swing.Timer animation = new javax.swing.Timer(16, null);
        animation.addActionListener(event -> {
            if (generation != levelGeneration) {
                animation.stop();
                return;
            }
            double progress = Math.min(1.0,
                (System.nanoTime() - started) / (durationMs * 1_000_000.0));
            double eased = progress * progress * (3.0 - 2.0 * progress);
            for (int i = 0; i < positions.size(); i++) {
                Rectangle from = starts.get(i);
                Rectangle to = destinations.get(i);
                int x = (int) (from.x + (to.x - from.x) * eased);
                int y = (int) (from.y + (to.y - from.y) * eased
                    - Math.sin(progress * Math.PI) * 12);
                int width = (int) (from.width + (to.width - from.width) * eased);
                int height = (int) (from.height + (to.height - from.height) * eased);
                shuffledOrder.get(positions.get(i)).button.setBounds(x, y, width, height);
            }
            board.repaint();
            if (progress >= 1.0) {
                animation.stop();
                cards.clear();
                cards.addAll(shuffledOrder);
                board.removeAll();
                board.setLayout(originalLayout);
                for (Card card : cards) board.add(card.button);
                board.revalidate();
                board.repaint();
                lockCount--;
                statusLabel.setText("⚠ Cards shuffled! Find their new positions.");
            }
        });
        animation.start();
    }

    /**
     * After the player flips a card, a random face-down card may flip itself showing the same picture.
     * Usually it is a trick (a different card wearing the matching picture); sometimes it really is the match.
     */
    private void maybeTriggerTrick(int firstIndex) {
        if (level < 6 || distractionsRemaining <= 0 || activeLure != null) return;
        int chance = isChallengeLevel() ? 55 : 35;
        if (random.nextInt(100) >= chance) return;
        Card first = cards.get(firstIndex);
        boolean real = random.nextInt(100) < 30;
        Card target = null;
        if (real) {
            for (Card c : cards) {
                if (c != first && !c.matched && !c.revealed && c.face.equals(first.face)) { target = c; break; }
            }
        } else {
            java.util.List<Card> pool = new ArrayList<>();
            for (Card c : cards) {
                if (c != first && !c.matched && !c.revealed && !c.lure && !c.face.equals(first.face)) pool.add(c);
            }
            if (!pool.isEmpty()) target = pool.get(random.nextInt(pool.size()));
        }
        if (target == null) return;
        distractionsRemaining--;
        distractionActive = true;
        activeLure = target;
        target.lure = true;
        target.lureFake = !real;
        target.button.showImage(loadCardImage(first.imageName), first.imageName, first.variant);
        target.button.setBackground(new Color(100, 126, 180));
        target.button.playFlip();
        playSound("pop");
        statusLabel.setText("👀 A card flipped by itself… is it your match, or a trick?");
        final int generation = levelGeneration;
        if (distractionTimer != null) distractionTimer.stop();
        distractionTimer = new javax.swing.Timer(2600, event -> {
            if (generation != levelGeneration) return;
            Card lure = activeLure;
            activeLure = null;
            distractionActive = false;
            distractionTimer = null;
            if (lure != null) hideLure(lure);
            statusLabel.setText("The tricky card flipped back. Keep going!");
        });
        distractionTimer.setRepeats(false);
        distractionTimer.start();
    }

    private void hideLure(Card c) {
        c.lure = false;
        c.lureFake = false;
        if (!c.revealed && !c.matched) {
            c.button.showImage(null, c.variant);
            c.button.setBackground(getCardBackColor());
            c.button.playFlip();
        }
    }

    /** The player clicked something: the tempting card is either the one clicked or flips back. */
    private void releaseLure(Card clicked) {
        if (distractionTimer != null) {
            distractionTimer.stop();
            distractionTimer = null;
        }
        distractionActive = false;
        Card lure = activeLure;
        activeLure = null;
        if (lure == null) return;
        if (lure == clicked) {
            lure.lure = false;
            lure.lureFake = false;
        } else {
            hideLure(lure);
        }
    }

    private boolean isChallengeLevel() {
        return level % 10 == 0;
    }

    private int currentThemeIndex() {
        return Math.max(0, ((level - 1) / 5) % THEME_NAMES.length);
    }

    private Color getCardBackColor() {
        return selectedCardBack == 0 ? THEME_CARD_BACKS[currentThemeIndex()] : CARD_BACK_SKINS[selectedCardBack];
    }

    private void clearDistraction() {
        if (distractionTimer != null) distractionTimer.stop();
        distractionTimer = null;
        distractionActive = false;
        Card lure = activeLure;
        activeLure = null;
        if (lure != null) hideLure(lure);
    }

    private void levelWon() {
        clock.stop();
        clearDistraction();
        coins += LEVEL_REWARD;
        int earnedStars = calculateStarRating();
        starsByLevel[level] = Math.max(starsByLevel[level], earnedStars);
        updateLabels();
        boolean finalLevel = level >= MAX_LEVEL;
        if (finalLevel) {
            gameCompleted = true;
        } else {
            highestUnlockedLevel = Math.max(highestUnlockedLevel, level + 1);
        }
        refreshLevelSelect();
        saveProgress();
        // Celebrate first, then show the summary dialog.
        effects.confetti();
        playWinSound();
        Point boardCenter = SwingUtilities.convertPoint(board, board.getWidth() / 2, board.getHeight() / 2, effects);
        effects.floatText("+" + LEVEL_REWARD + " COINS", boardCenter, new Color(255, 218, 118));
        lockCount++;
        final boolean lastLevel = finalLevel;
        final int starsEarned = earnedStars;
        javax.swing.Timer winDelay = new javax.swing.Timer(1100, evt -> {
            lockCount--;
            finishLevelWon(lastLevel, starsEarned);
        });
        winDelay.setRepeats(false);
        winDelay.start();
    }

    private void finishLevelWon(boolean finalLevel, int earnedStars) {
        if (showLevelCompleteDialog(finalLevel, earnedStars)) {
            if (finalLevel) {
                refreshLevelSelect();
                animateLevelTiles();
                showScreen("levels");
            } else {
                level++;
                saveProgress();
                showScreen("game");
                startLevel();
            }
        } else {
            statusLabel.setText(finalLevel
                ? "All 50 levels cleared! Return to the menu to start again."
                : "Level cleared! Visit the shop when you are ready for the next level.");
        }
    }

    private int calculateStarRating() {
        int pairs = cards.size() / 2;
        double timeLeftRatio = (double) secondsLeft / Math.max(1, levelTimeLimit);
        if (totalWrongGuesses <= Math.max(1, pairs / 3) && timeLeftRatio >= 0.35) return 3;
        if (totalWrongGuesses <= pairs && secondsLeft > 0) return 2;
        return 1;
    }

    private boolean showLevelCompleteDialog(boolean finalLevel, int earnedStars) {
        final boolean[] continueGame = {false};
        JDialog dialog = new JDialog(frame, finalLevel ? "GAME COMPLETE" : "LEVEL CLEARED", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(520, 430);
        dialog.setResizable(false);

        JPanel panel = new JPanel(new BorderLayout(14, 14));
        panel.setBackground(NAVY);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(KIT_YELLOW, 3),
            new EmptyBorder(22, 26, 22, 26)));

        JPanel headline = new JPanel();
        headline.setOpaque(false);
        headline.setLayout(new BoxLayout(headline, BoxLayout.Y_AXIS));
        JLabel badge = new JLabel(finalLevel ? "✦  GAME COMPLETE  ✦" : "✦  LEVEL CLEARED  ✦");
        badge.setAlignmentX(Component.CENTER_ALIGNMENT);
        badge.setForeground(KIT_TEAL);
        badge.setFont(new Font("SansSerif", Font.BOLD, 15));
        JLabel title = new JLabel(finalLevel ? "You did it!" : "Great memory!");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 32));
        JLabel subtitle = new JLabel(finalLevel
            ? "You finished all 50 levels!" : "You found every matching card.");
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setForeground(new Color(196, 210, 238));
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 15));
        headline.add(badge);
        headline.add(Box.createVerticalStrut(8));
        headline.add(title);
        headline.add(Box.createVerticalStrut(5));
        JLabel starDisplay = new JLabel("☆ ☆ ☆");
        starDisplay.setAlignmentX(Component.CENTER_ALIGNMENT);
        starDisplay.setForeground(new Color(255, 213, 103));
        starDisplay.setFont(new Font("SansSerif", Font.BOLD, 25));
        // Stars light up one at a time.
        final int[] shownStars = {0};
        javax.swing.Timer starTimer = new javax.swing.Timer(380, e -> {
            shownStars[0]++;
            int lit = Math.min(shownStars[0], earnedStars);
            starDisplay.setText("★ ".repeat(lit) + "☆ ".repeat(3 - lit));
            playStarSound();
            if (shownStars[0] >= earnedStars) ((javax.swing.Timer) e.getSource()).stop();
        });
        starTimer.setInitialDelay(450);
        starTimer.start();
        headline.add(starDisplay);
        headline.add(Box.createVerticalStrut(3));
        headline.add(subtitle);

        JPanel stats = new JPanel(new GridLayout(1, 2, 12, 0));
        stats.setOpaque(false);
        stats.add(makeLevelStat("REWARD", "+" + LEVEL_REWARD, "COINS", new Color(255, 216, 118)));
        stats.add(finalLevel
            ? makeLevelStat("CAMPAIGN", "50", "LEVELS DONE", new Color(142, 199, 255))
            : makeLevelStat("UP NEXT", "LEVEL " + (level + 1), "READY", new Color(142, 199, 255)));

        JButton stay = new JButton("STAY HERE");
        JButton next = new JButton(finalLevel ? "RETURN TO MENU" : "CONTINUE  →");
        styleButton(stay);
        styleButton(next);
        stay.setPreferredSize(new Dimension(150, 44));
        next.setPreferredSize(new Dimension(180, 44));
        styleAs(next, "primary");
        stay.addActionListener(event -> dialog.dispose());
        next.addActionListener(event -> {
            continueGame[0] = true;
            dialog.dispose();
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        actions.setOpaque(false);
        actions.add(stay);
        actions.add(next);

        JPanel center = new JPanel(new BorderLayout(0, 16));
        center.setOpaque(false);
        JLabel balance = new JLabel("Your coin balance: " + coins, SwingConstants.CENTER);
        balance.setForeground(new Color(205, 218, 241));
        center.add(stats, BorderLayout.CENTER);
        center.add(balance, BorderLayout.SOUTH);

        panel.add(headline, BorderLayout.NORTH);
        panel.add(center, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.getRootPane().setDefaultButton(next);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        starTimer.stop();
        return continueGame[0];
    }

    private JPanel makeLevelStat(String heading, String value, String footnote, Color accent) {
        JPanel card = new JPanel();
        card.setBackground(PANEL_DARK);
        card.setBorder(BorderFactory.createLineBorder(new Color(42, 47, 92), 2));
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        JLabel top = new JLabel(heading);
        JLabel amount = new JLabel(value);
        JLabel bottom = new JLabel(footnote);
        for (JLabel label : new JLabel[]{top, amount, bottom}) {
            label.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        top.setForeground(new Color(182, 197, 224));
        top.setFont(new Font("SansSerif", Font.BOLD, 12));
        amount.setForeground(accent);
        amount.setFont(new Font("SansSerif", Font.BOLD, 25));
        bottom.setForeground(new Color(182, 197, 224));
        bottom.setFont(new Font("SansSerif", Font.BOLD, 11));
        card.add(Box.createVerticalGlue());
        card.add(top);
        card.add(Box.createVerticalStrut(7));
        card.add(amount);
        card.add(Box.createVerticalStrut(4));
        card.add(bottom);
        card.add(Box.createVerticalGlue());
        return card;
    }

    private void tick() {
        secondsLeft--;
        updateLabels();
        if (secondsLeft > 0 && secondsLeft <= 10) playSound("tick");
        if (secondsLeft <= 0) {
            clock.stop();
            levelGeneration++;
            effects.cancelMatch();
            lockCount = 0;
            firstCard = secondCard = -1;
            clearDistraction();
            deactivateMagnifier();
            statusLabel.setText("Time ran out. Retry the level or return to the level table.");
            showTimeUpDialog();
        }
    }

    private void showTimeUpDialog() {
        JDialog dialog = new JDialog(frame, "TIME'S UP", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(460, 270);
        dialog.setResizable(false);
        JPanel panel = new JPanel(new BorderLayout(12, 18));
        panel.setBackground(NAVY);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(KIT_PINK, 2), new EmptyBorder(24, 28, 22, 28)));
        JLabel title = new JLabel("TIME'S UP!", SwingConstants.CENTER);
        title.setForeground(KIT_PINK);
        title.setFont(new Font("SansSerif", Font.BOLD, 30));
        JLabel message = new JLabel("The clock ran out before you found every pair.", SwingConstants.CENTER);
        message.setForeground(new Color(196, 210, 238));
        message.setFont(new Font("SansSerif", Font.PLAIN, 15));
        JButton retry = new JButton("↻  TRY AGAIN");
        JButton levels = new JButton("LEVEL SELECT");
        styleButton(retry, "primary");
        styleButton(levels, "secondary");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        actions.setOpaque(false);
        actions.add(retry);
        actions.add(levels);
        final boolean[] retryLevel = {true};
        retry.addActionListener(event -> dialog.dispose());
        levels.addActionListener(event -> { retryLevel[0] = false; dialog.dispose(); });
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 8));
        heading.setOpaque(false);
        heading.add(title);
        heading.add(message);
        panel.add(heading, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.getRootPane().setDefaultButton(retry);
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "retry-level");
        dialog.getRootPane().getActionMap().put("retry-level", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { dialog.dispose(); }
        });
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        if (retryLevel[0]) {
            startLevel();
        } else {
            returnToLevelSelect();
        }
    }

    private void showAccountDialog() {
        JDialog dialog = new JDialog(frame, "PLAYER ACCOUNT", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(480, 720);
        dialog.setResizable(false);
        dialog.setContentPane(buildAccountPanel(dialog::dispose,
            button -> dialog.getRootPane().setDefaultButton(button), () -> shakeWindow(dialog)));
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-account");
        dialog.getRootPane().getActionMap().put("close-account", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { dialog.dispose(); }
        });
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private JLabel accountFieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(KIT_YELLOW);
        label.setFont(new Font("SansSerif", Font.BOLD, 11));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(0, 2, 5, 0));
        return label;
    }

    private JPanel accountPasswordRow(RoundedPasswordField field) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JButton toggle = new JButton("SHOW");
        styleButton(toggle);
        toggle.setFont(new Font("SansSerif", Font.BOLD, 11));
        toggle.setFocusable(false);
        toggle.setPreferredSize(new Dimension(72, 46));
        final char echo = field.getEchoChar();
        toggle.addActionListener(e -> {
            boolean hidden = field.getEchoChar() != 0;
            field.setEchoChar(hidden ? (char) 0 : echo);
            toggle.setText(hidden ? "HIDE" : "SHOW");
        });
        row.add(field, BorderLayout.CENTER);
        row.add(toggle, BorderLayout.EAST);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        return row;
    }

    private JPanel accountFormPanel() {
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(18, 20, 18, 20));
        return form;
    }

    /** The sign-in / create-account screen, styled like the rest of the game. */
    private JPanel buildAccountPanel(Runnable closeAction, java.util.function.Consumer<JButton> setDefaultButton,
                                     Runnable shakeAction) {
        JPanel backdrop = new AnimatedBackground(new BorderLayout(), 24);
        backdrop.setBorder(new EmptyBorder(20, 26, 18, 26));

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        AvatarBadge avatar = new AvatarBadge();
        avatar.setPreferredSize(new Dimension(62, 62));
        avatar.setMaximumSize(new Dimension(62, 62));
        avatar.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel title = new JLabel("<html><font color='#FFC93C'>SQUAD</font> FLIP</html>", SwingConstants.CENTER);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 34));
        JLabel subtitle = new JLabel("Sign in to save your levels, stars and coins", SwingConstants.CENTER);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        subtitle.setForeground(new Color(196, 202, 235));
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 13));
        head.add(avatar);
        head.add(Box.createVerticalStrut(8));
        head.add(title);
        head.add(Box.createVerticalStrut(2));
        head.add(subtitle);
        head.add(Box.createVerticalStrut(14));

        JLabel message = new JLabel(" ", SwingConstants.CENTER);
        message.setFont(new Font("SansSerif", Font.BOLD, 13));
        message.setForeground(new Color(196, 202, 235));
        message.setPreferredSize(new Dimension(200, 24));
        java.util.function.BiConsumer<String, Boolean> say = (text, bad) -> {
            message.setForeground(bad ? new Color(255, 120, 160) : new Color(196, 202, 235));
            message.setText(text);
        };

        // ---- sign in ----
        String[] saved = listAccountNames();
        RoundedTextField loginName = new RoundedTextField(saved.length > 0 ? saved[0] : "");
        RoundedPasswordField loginPassword = new RoundedPasswordField();
        JButton signIn = new JButton("SIGN IN");
        styleButton(signIn, "primary");
        signIn.setFont(new Font("SansSerif", Font.BOLD, 16));
        signIn.setPreferredSize(new Dimension(200, 50));
        signIn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        signIn.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel login = accountFormPanel();
        login.add(accountFieldLabel("USERNAME"));
        login.add(loginName);
        if (saved.length > 0) {
            login.add(Box.createVerticalStrut(8));
            login.add(accountFieldLabel("SAVED PLAYERS"));
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            chips.setOpaque(false);
            chips.setAlignmentX(Component.LEFT_ALIGNMENT);
            chips.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            for (int i = 0; i < Math.min(saved.length, 6); i++) {
                final String name = saved[i];
                JButton chip = new JButton(name.length() > 10 ? name.substring(0, 10) : name);
                styleButton(chip);
                chip.setFont(new Font("SansSerif", Font.BOLD, 12));
                chip.setBorder(new EmptyBorder(4, 12, 7, 12));
                chip.setFocusable(false);
                chip.addActionListener(e -> {
                    loginName.setText(name);
                    loginPassword.requestFocusInWindow();
                });
                chips.add(chip);
            }
            login.add(chips);
        }
        login.add(Box.createVerticalStrut(12));
        login.add(accountFieldLabel("PASSWORD"));
        login.add(accountPasswordRow(loginPassword));
        login.add(Box.createVerticalGlue());
        login.add(signIn);

        // ---- create account ----
        RoundedTextField newName = new RoundedTextField("");
        RoundedPasswordField newPassword = new RoundedPasswordField();
        RoundedPasswordField confirmPassword = new RoundedPasswordField();
        StrengthBar strength = new StrengthBar();
        JLabel strengthText = new JLabel("Use 6+ characters. Mix letters, numbers and symbols for a stronger one.");
        strengthText.setForeground(new Color(160, 168, 205));
        strengthText.setFont(new Font("SansSerif", Font.PLAIN, 11));
        strengthText.setAlignmentX(Component.LEFT_ALIGNMENT);
        onTextChange(newPassword, () -> {
            char[] pw = newPassword.getPassword();
            int score = passwordScore(pw);
            strength.setScore(pw.length == 0 ? 0 : Math.max(1, score));
            strengthText.setText(pw.length == 0 ? "Use 6+ characters. Mix letters, numbers and symbols for a stronger one."
                : score <= 1 ? "Weak password" : score == 2 ? "Okay password" : score == 3 ? "Good password" : "Strong password!");
            Arrays.fill(pw, '\0');
        });
        JButton createAccount = new JButton("CREATE ACCOUNT");
        styleButton(createAccount, "success");
        createAccount.setFont(new Font("SansSerif", Font.BOLD, 16));
        createAccount.setPreferredSize(new Dimension(200, 50));
        createAccount.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        createAccount.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel create = accountFormPanel();
        create.add(accountFieldLabel("CHOOSE A USERNAME"));
        create.add(newName);
        create.add(Box.createVerticalStrut(10));
        create.add(accountFieldLabel("PASSWORD"));
        create.add(accountPasswordRow(newPassword));
        create.add(Box.createVerticalStrut(6));
        create.add(strength);
        create.add(Box.createVerticalStrut(4));
        create.add(strengthText);
        create.add(Box.createVerticalStrut(10));
        create.add(accountFieldLabel("CONFIRM PASSWORD"));
        create.add(confirmPassword);
        create.add(Box.createVerticalGlue());
        create.add(createAccount);

        // ---- card with the two forms + tab buttons ----
        JPanel card = new JPanel(new CardLayout()) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0, 0, 0, 80));
                g.fillRoundRect(0, 5, getWidth(), getHeight() - 5, 22, 22);
                g.setColor(PANEL_DARK);
                g.fillRoundRect(0, 0, getWidth(), getHeight() - 5, 22, 22);
                g.dispose();
            }
        };
        card.setOpaque(false);
        card.add(login, "login");
        card.add(create, "create");

        JButton signInTab = new JButton("SIGN IN");
        JButton createTab = new JButton("NEW PLAYER");
        styleButton(signInTab, "primary");
        styleButton(createTab, "secondary");
        signInTab.setPreferredSize(new Dimension(150, 44));
        createTab.setPreferredSize(new Dimension(150, 44));
        JPanel tabs = new JPanel(new GridLayout(1, 2, 10, 0));
        tabs.setOpaque(false);
        tabs.add(signInTab);
        tabs.add(createTab);
        signInTab.addActionListener(e -> {
            ((CardLayout) card.getLayout()).show(card, "login");
            styleAs(signInTab, "primary");
            styleAs(createTab, "secondary");
            setDefaultButton.accept(signIn);
            say.accept(" ", false);
        });
        createTab.addActionListener(e -> {
            ((CardLayout) card.getLayout()).show(card, "create");
            styleAs(signInTab, "secondary");
            styleAs(createTab, "primary");
            setDefaultButton.accept(createAccount);
            say.accept(" ", false);
        });

        // ---- actions ----
        signIn.addActionListener(event -> {
            String username = loginName.getText().trim();
            char[] password = loginPassword.getPassword();
            try {
                if (!authenticateAccount(username, password)) {
                    say.accept("⚠ Account or password is incorrect.", true);
                    playSound("wrong");
                    shakeAction.run();
                    return;
                }
                activateAccount(username);
                closeAction.run();
                openLevelSelect();
            } catch (Exception ex) {
                say.accept("⚠ Could not open account: " + ex.getMessage(), true);
            } finally {
                Arrays.fill(password, '\0');
            }
        });
        createAccount.addActionListener(event -> {
            String username = newName.getText().trim();
            char[] password = newPassword.getPassword();
            char[] confirmation = confirmPassword.getPassword();
            try {
                if (!username.matches("[A-Za-z0-9_-]{3,20}")) {
                    say.accept("⚠ Username: 3–20 letters, numbers, _ or -.", true);
                    shakeAction.run();
                    return;
                }
                if (password.length < 6) {
                    say.accept("⚠ Password must be at least 6 characters.", true);
                    shakeAction.run();
                    return;
                }
                if (!Arrays.equals(password, confirmation)) {
                    say.accept("⚠ The passwords do not match.", true);
                    shakeAction.run();
                    return;
                }
                File file = accountFile(username);
                if (file.exists()) {
                    say.accept("⚠ That username already exists. Sign in instead.", true);
                    shakeAction.run();
                    return;
                }
                Properties account = new Properties();
                byte[] salt = new byte[16];
                new SecureRandom().nextBytes(salt);
                account.setProperty("username", username);
                account.setProperty("passwordSalt", Base64.getEncoder().encodeToString(salt));
                account.setProperty("passwordHash", Base64.getEncoder().encodeToString(hashPassword(password, salt)));
                writeProperties(file, account, "Squad Flip local player account");
                activateAccount(username);
                closeAction.run();
                openLevelSelect();
            } catch (Exception ex) {
                say.accept("⚠ Could not create account: " + ex.getMessage(), true);
            } finally {
                Arrays.fill(password, '\0');
                Arrays.fill(confirmation, '\0');
            }
        });
        setDefaultButton.accept(signIn);
        if (saved.length == 0) createTab.doClick();

        JButton back = new JButton("← BACK TO MENU");
        styleButton(back);
        back.setFont(new Font("SansSerif", Font.BOLD, 13));
        back.setPreferredSize(new Dimension(200, 44));
        back.addActionListener(e -> closeAction.run());

        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        center.add(tabs, BorderLayout.NORTH);
        center.add(card, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(message, BorderLayout.CENTER);
        south.add(back, BorderLayout.SOUTH);
        south.setBorder(new EmptyBorder(8, 0, 0, 0));
        backdrop.add(head, BorderLayout.NORTH);
        backdrop.add(center, BorderLayout.CENTER);
        backdrop.add(south, BorderLayout.SOUTH);
        return backdrop;
    }

    private String[] listAccountNames() {
        if (!ACCOUNT_DIRECTORY.isDirectory()) return new String[0];
        File[] files = ACCOUNT_DIRECTORY.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files == null) return new String[0];
        ArrayList<String> names = new ArrayList<>();
        for (File file : files) {
            Properties account = new Properties();
            try (FileInputStream input = new FileInputStream(file)) {
                account.load(input);
                String name = account.getProperty("username");
                if (name != null && !name.isEmpty()) names.add(name);
            } catch (Exception ignored) { }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names.toArray(new String[0]);
    }

    private File accountFile(String username) {
        return new File(ACCOUNT_DIRECTORY, username.toLowerCase(Locale.ROOT) + ".properties");
    }

    private boolean authenticateAccount(String username, char[] password) throws Exception {
        if (!username.matches("[A-Za-z0-9_-]{3,20}")) return false;
        File file = accountFile(username);
        if (!file.isFile()) return false;
        Properties account = new Properties();
        try (FileInputStream input = new FileInputStream(file)) { account.load(input); }
        byte[] salt = Base64.getDecoder().decode(account.getProperty("passwordSalt", ""));
        byte[] expected = Base64.getDecoder().decode(account.getProperty("passwordHash", ""));
        byte[] actual = hashPassword(password, salt);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] hashPassword(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PASSWORD_ITERATIONS, PASSWORD_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally { spec.clearPassword(); }
    }

    private void activateAccount(String username) {
        currentUsername = username;
        accountSaveFile = accountFile(username);
        resetProgress();
        loadProgress();
        refreshLevelSelect();
    }

    private void resetProgress() {
        coins = 0;
        discoveredImages.clear();
        highestUnlockedLevel = 1;
        level = 1;
        gameCompleted = false;
        magnifiers = hints = shields = timerBoosts = 0;
        shieldArmed = false;
        chainLockIntroSeen = false;
        selectedCardBack = 0;
        Arrays.fill(starsByLevel, 0);
        Arrays.fill(unlockedCardBacks, false);
        unlockedCardBacks[0] = true;
    }

    private void beginShopPause() {
        if (shopPauseDepth++ == 0) {
            shopClockWasRunning = clock.isRunning();
            if (shopClockWasRunning) clock.stop();
        }
    }

    private void endShopPause() {
        if (shopPauseDepth > 0 && --shopPauseDepth == 0) {
            if (shopClockWasRunning && secondsLeft > 0) clock.start();
            shopClockWasRunning = false;
        }
    }

    private void showShop() {
        beginShopPause();
        JDialog dialog = new JDialog(frame, "POWER-UP SHOP", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(850, 570);
        dialog.setResizable(false);

        JPanel content = new JPanel(new BorderLayout(16, 16));
        content.setBackground(NAVY);
        content.setBorder(new EmptyBorder(22, 24, 20, 24));

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JLabel title = new JLabel("POWER-UP SHOP");
        title.setForeground(KIT_YELLOW);
        title.setFont(new Font("SansSerif", Font.BOLD, 26));
        JLabel balance = new JLabel("🪙 " + coins + " COINS");
        balance.setForeground(new Color(255, 218, 118));
        balance.setFont(new Font("SansSerif", Font.BOLD, 16));
        heading.add(title, BorderLayout.WEST);
        heading.add(balance, BorderLayout.EAST);

        JPanel tiles = new JPanel(new GridLayout(0, 3, 13, 13));
        tiles.setOpaque(false);
        String[] names = {"MAGNIFIER", "PAIR HINT", "MISTAKE SHIELD", "EXTRA TIME"};
        String[] icons = {"🔍", "💡", "🛡", "⏱"};
        String[] details = {
            "Hover over cards to X-ray them for 3 seconds.",
            "Briefly reveal one matching pair.",
            "Cancel the next wrong guess and avoid its shuffle.",
            "Add 60 seconds to the level timer."
        };
        int[] costs = {50, 80, 60, 40};
        Color[] arts = {KIT_PURPLE, new Color(18, 156, 133), KIT_PINK, new Color(184, 134, 26)};
        for (int i = 0; i < names.length; i++) {
            final int item = i;
            JButton buy = new JButton("🪙 " + costs[i]);
            styleButton(buy, "primary");
            buy.setPreferredSize(new Dimension(120, 44));
            buy.addActionListener(event -> {
                if (coins < costs[item]) {
                    playSound("wrong");
                    showToast(dialog, "Not enough coins! Need " + (costs[item] - coins) + " more.");
                    return;
                }
                coins -= costs[item];
                switch (item) {
                    case 0: magnifiers++; break;
                    case 1: hints++; break;
                    case 2: shields++; break;
                    case 3: timerBoosts++; break;
                    default: break;
                }
                updateLabels();
                saveProgress();
                refreshLevelSelect();
                balance.setText("🪙 " + coins + " COINS");
                statusLabel.setText(names[item] + " purchased!");
                playSound("match");
            });
            tiles.add(makeShopCard(icons[i], names[i], details[i], arts[i], buy));
        }

        JButton openBacks = new JButton("OPEN →");
        styleButton(openBacks, "success");
        openBacks.setPreferredSize(new Dimension(120, 44));
        openBacks.addActionListener(event -> {
            dialog.dispose();
            showCardBackShop();
        });
        tiles.add(makeShopCard("🎨", "CARD BACKS",
            "Unlock new designs and equip your favorite card style.", new Color(120, 70, 190), openBacks));

        JLabel caption = new JLabel("Choose a square to buy a power-up", SwingConstants.CENTER);
        caption.setForeground(new Color(191, 205, 232));
        JButton close = new JButton("CLOSE");
        styleButton(close);
        close.addActionListener(event -> dialog.dispose());

        content.add(heading, BorderLayout.NORTH);
        content.add(tiles, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 4));
        footer.setOpaque(false);
        footer.add(caption, BorderLayout.CENTER);
        footer.add(close, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setLocationRelativeTo(frame);
        try { dialog.setVisible(true); }
        finally { endShopPause(); }
    }

    /** Red notification banner that slides down inside a dialog. */
    private static class ToastLabel extends JLabel {
        ToastLabel(String text) {
            super(text, SwingConstants.LEFT);
            setForeground(Color.WHITE);
            setFont(new Font("SansSerif", Font.BOLD, 14));
            setBorder(new EmptyBorder(0, 56, 4, 16));
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(143, 22, 72));
            g.fillRoundRect(0, 4, w, h - 4, 16, 16);
            g.setColor(KIT_PINK);
            g.fillRoundRect(0, 0, w, h - 4, 16, 16);
            int iy = (h - 4 - 28) / 2;
            g.setColor(Color.WHITE);
            g.fillOval(14, iy, 28, 28);
            g.setColor(KIT_PINK);
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("!", 14 + (28 - fm.stringWidth("!")) / 2, iy + 21);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** Slides a notification in from the top of the dialog, holds it, then slides it away. */
    private static void showToast(JDialog dialog, String message) {
        JLayeredPane layers = dialog.getLayeredPane();
        Object old = dialog.getRootPane().getClientProperty("toast");
        if (old instanceof JComponent) {
            JComponent oldToast = (JComponent) old;
            Object oldTimer = oldToast.getClientProperty("timer");
            if (oldTimer instanceof javax.swing.Timer) ((javax.swing.Timer) oldTimer).stop();
            layers.remove(oldToast);
            layers.repaint();
        }
        ToastLabel toast = new ToastLabel(message);
        final int w = Math.min(Math.max(240, layers.getWidth() - 60), 440);
        final int h = 52;
        final int x = (layers.getWidth() - w) / 2;
        toast.setBounds(x, -h, w, h);
        layers.add(toast, JLayeredPane.POPUP_LAYER);
        dialog.getRootPane().putClientProperty("toast", toast);
        final long started = System.nanoTime();
        javax.swing.Timer t = new javax.swing.Timer(16, null);
        t.addActionListener(e -> {
            double ms = (System.nanoTime() - started) / 1e6;
            double y;
            if (ms < 260) y = -h + (h + 14) * easeOutBack(ms / 260.0);
            else if (ms < 2100) y = 14;
            else if (ms < 2400) y = 14 - (h + 14) * ((ms - 2100) / 300.0);
            else {
                t.stop();
                layers.remove(toast);
                layers.repaint();
                return;
            }
            toast.setLocation(x, (int) y);
        });
        toast.putClientProperty("timer", t);
        t.start();
    }

    /** One kit-style shop item: art box, name, description and a price button. */
    private JPanel makeShopCard(String icon, String name, String detail, Color art, JButton action) {
        JPanel card = new JPanel(new BorderLayout(0, 8)) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0, 0, 0, 80));
                g.fillRoundRect(0, 4, getWidth(), getHeight() - 4, 20, 20);
                g.setColor(PANEL_DARK);
                g.fillRoundRect(0, 0, getWidth(), getHeight() - 4, 20, 20);
                g.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(10, 10, 16, 10));
        JLabel artBox = new JLabel(icon, SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0, 0, art, getWidth(), getHeight(), mix(art, Color.BLACK, 0.45f)));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        artBox.setFont(new Font("SansSerif", Font.PLAIN, 38));
        artBox.setForeground(Color.WHITE);
        artBox.setPreferredSize(new Dimension(100, 78));
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel nameLabel = new JLabel(name);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        JLabel detailLabel = new JLabel("<html><div style='width:190px'>" + detail + "</div></html>");
        detailLabel.setForeground(new Color(184, 188, 224));
        detailLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        text.add(nameLabel);
        text.add(Box.createVerticalStrut(4));
        text.add(detailLabel);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(action, BorderLayout.CENTER);
        card.add(artBox, BorderLayout.NORTH);
        card.add(text, BorderLayout.CENTER);
        card.add(south, BorderLayout.SOUTH);
        return card;
    }

    private void showCardBackShop() {
        beginShopPause();
        JDialog dialog = new JDialog(frame, "CARD BACK DESIGNS", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(620, 500);
        dialog.setResizable(false);

        JPanel content = new JPanel(new BorderLayout(14, 14));
        content.setBackground(NAVY);
        content.setBorder(new EmptyBorder(20, 22, 18, 22));
        JLabel title = new JLabel("CARD BACK DESIGNS");
        title.setForeground(KIT_YELLOW);
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        JLabel balance = new JLabel("🪙 " + coins + " COINS");
        balance.setForeground(new Color(255, 218, 118));
        balance.setFont(new Font("SansSerif", Font.BOLD, 15));
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(title, BorderLayout.WEST);
        header.add(balance, BorderLayout.EAST);

        JPanel tiles = new JPanel(new GridLayout(2, 2, 12, 12));
        tiles.setOpaque(false);
        String[] colorCodes = {"#D4DEF2", "#F1D7FF", "#C8F4F5", "#FFE0E6"};
        for (int i = 0; i < CARD_BACK_NAMES.length; i++) {
            final int skin = i;
            String state = !unlockedCardBacks[i]
                ? CARD_BACK_PRICES[i] + " COINS"
                : (selectedCardBack == i ? "EQUIPPED" : "OWNED · CLICK TO EQUIP");
            JButton tile = new JButton("<html><div style='text-align:center;width:190px'>"
                + "<font size='+4'>✦</font><br><br><b>" + CARD_BACK_NAMES[i]
                + "</b><br><br><font color='" + colorCodes[i] + "'>" + state + "</font></div></html>");
            tile.setFont(new Font("SansSerif", Font.BOLD, 14));
            tile.setForeground(Color.WHITE);
            tile.setBackground(i == 0 ? getCardBackColor() : CARD_BACK_SKINS[i]);
            installFancy(tile);
            tile.setBorder(new EmptyBorder(2, 6, 4, 6));
            tile.putClientProperty("outline", new Color(210, 221, 243));
            tile.addActionListener(event -> {
                if (!unlockedCardBacks[skin]) {
                    if (coins < CARD_BACK_PRICES[skin]) {
                        playSound("wrong");
                        showToast(dialog, "Not enough coins! Need " + (CARD_BACK_PRICES[skin] - coins) + " more.");
                        return;
                    }
                    coins -= CARD_BACK_PRICES[skin];
                    unlockedCardBacks[skin] = true;
                }
                selectedCardBack = skin;
                applyCardBack();
                updateLabels();
                saveProgress();
                refreshLevelSelect();
                balance.setText("🪙 " + coins + " COINS");
                tile.setText("<html><div style='text-align:center;width:190px'>"
                    + "<font size='+4'>✦</font><br><br><b>" + CARD_BACK_NAMES[skin]
                    + "</b><br><br><font color='" + colorCodes[skin] + "'>EQUIPPED</font></div></html>");
            });
            tiles.add(tile);
        }

        JButton close = new JButton("DONE");
        styleButton(close);
        close.addActionListener(event -> dialog.dispose());
        content.add(header, BorderLayout.NORTH);
        content.add(tiles, BorderLayout.CENTER);
        content.add(close, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setLocationRelativeTo(frame);
        try { dialog.setVisible(true); }
        finally { endShopPause(); }
    }

    private void applyCardBack() {
        for (Card card : cards) {
            if (!card.matched && !card.revealed) card.button.setBackground(getCardBackColor());
        }
        board.repaint();
    }

    private void useMagnifier() {
        if (magnifierActive) {
            statusLabel.setText("Magnifier is already active — hover over a card!");
            return;
        }
        if (magnifiers <= 0) { noPowerUp("Magnifying glasses"); return; }
        magnifiers--;
        magnifierActive = true;
        magnifierSeconds = 3;
        statusLabel.setText("🔍 X-ray active — hover over a card to inspect it (3 seconds).");
        pulsePowerButton(magnifyButton);
        saveProgress();
        magnifierTimer = new javax.swing.Timer(1000, e -> {
            magnifierSeconds--;
            if (magnifierSeconds <= 0) {
                deactivateMagnifier();
                statusLabel.setText("Magnifier expired. Buy another in the shop.");
            } else {
                updateLabels();
            }
        });
        magnifierTimer.start();
        updateLabels();
    }

    private void deactivateMagnifier() {
        if (magnifierTimer != null) {
            magnifierTimer.stop();
            magnifierTimer = null;
        }
        magnifierActive = false;
        magnifierSeconds = 0;
        for (Card card : cards) {
            if (card.button.isXrayVisible()) card.button.showImage(null, card.variant);
        }
        updateLabels();
    }

    private void pulsePowerButton(JButton button) {
        Color normal = button.getBackground();
        Color glow = new Color(91, 190, 210);
        final int[] ticks = {0};
        javax.swing.Timer pulse = new javax.swing.Timer(90, null);
        pulse.addActionListener(e -> {
            button.setBackground(ticks[0]++ % 2 == 0 ? glow : normal);
            if (ticks[0] >= 8) {
                pulse.stop();
                button.setBackground(normal);
            }
        });
        pulse.start();
    }

    private void useHint() {
        if (hints <= 0) { noPowerUp("Hints"); return; }
        Map<String, java.util.List<Integer>> found = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            if (!c.matched && !c.revealed && !c.lure) found.computeIfAbsent(c.face, k -> new ArrayList<>()).add(i);
        }
        java.util.List<Integer> pair = null;
        for (java.util.List<Integer> indexes : found.values()) if (indexes.size() >= 2) { pair = indexes; break; }
        if (pair == null) return;
        hints--;
        saveProgress();
        int a = pair.get(0), b = pair.get(1);
        for (int i : new int[]{a, b}) {
            Card card = cards.get(i);
            card.button.showImage(loadCardImage(card.imageName), card.imageName, card.variant);
            card.button.setHintVisible(true);
            cards.get(i).button.setBackground(new Color(191, 153, 67));
            effects.burst(centerOf(card), new Color(255, 211, 83), 10);
        }
        statusLabel.setText("Hint: these two cards match!");
        javax.swing.Timer t = new javax.swing.Timer(1500, e -> {
            for (int i : new int[]{a, b}) if (!cards.get(i).matched && !cards.get(i).revealed) {
                cards.get(i).button.setHintVisible(false);
                cards.get(i).button.showImage(null, cards.get(i).variant);
                cards.get(i).button.setBackground(getCardBackColor());
            }
        });
        t.setRepeats(false); t.start();
        updateLabels();
    }

    private void useShield() {
        if (shieldArmed) {
            statusLabel.setText("Shield is armed for your next wrong guess.");
            return;
        }
        if (shields <= 0) { noPowerUp("Mistake shields"); return; }
        shields--;
        shieldArmed = true;
        saveProgress();
        pulsePowerButton(shieldButton);
        statusLabel.setText("🛡 Shield armed — it will block your next wrong guess.");
        updateLabels();
    }

    private void useTimerBoost() {
        if (timerBoosts <= 0) { noPowerUp("Extra-time boosts"); return; }
        timerBoosts--;
        saveProgress();
        secondsLeft += 60;
        statusLabel.setText("60 seconds added!");
        updateLabels();
    }

    private void noPowerUp(String name) {
        statusLabel.setText("No " + name + " left. Buy more in the shop.");
    }

    private static BufferedImage loadCardImage(String imageName) {
        if (IMAGE_CACHE.containsKey(imageName)) return IMAGE_CACHE.get(imageName);
        File file = findAssetFile(imageName);
        if (file != null) {
            try {
                BufferedImage image = ImageIO.read(file);
                if (image != null) {
                    IMAGE_CACHE.put(imageName, image);
                    return image;
                }
            } catch (Exception ex) {
                System.err.println("Could not load card image " + file + ": " + ex.getMessage());
            }
        }
        System.err.println("Card image not found: " + imageName + " (keep the assets folder beside MemoryFogGame.java)");
        return null;
    }

    private static String[] discoverCardImages() {
        Set<String> images = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (File directory : findAssetDirectories()) {
            File[] files = directory.listFiles(file -> isSupportedCardImage(file));
            if (files != null) {
                for (File file : files) images.add(file.getName());
            }
        }
        if (images.isEmpty()) {
            return new String[]{"blue-face.png", "yellow-face.png", "dress-collage.png",
                "monkey.png", "jun.jpg", "tandang-nick.jpg"};
        }
        return images.toArray(new String[0]);
    }

    private static boolean isSupportedCardImage(File file) {
        if (!file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.equals("lock card.png")) return false; // UI illustration, not a collectible face.
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
            || name.endsWith(".gif") || name.endsWith(".bmp");
    }

    private static java.util.List<File> findAssetDirectories() {
        java.util.List<File> directories = new ArrayList<>();
        File working = new File(System.getProperty("user.dir"));
        addAssetDirectory(directories, new File(working, "assets"));
        addAssetDirectory(directories, new File(working, "outputs/assets"));
        addAssetDirectory(directories, new File(working, "outputs/MemoryFogGame3/assets"));
        try {
            File classes = new File(MemoryFogGame.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            if (classes.isFile()) classes = classes.getParentFile();
            addAssetDirectory(directories, new File(classes, "assets"));
            addAssetDirectory(directories, new File(classes, "outputs/assets"));
            File parent = classes.getParentFile();
            if (parent != null) {
                addAssetDirectory(directories, new File(parent, "assets"));
                addAssetDirectory(directories, new File(parent, "outputs/assets"));
                addAssetDirectory(directories, new File(parent, "MemoryFogGame3/assets"));
            }
        } catch (Exception ignored) {
            // The working-directory locations still work if class metadata is unavailable.
        }
        return directories;
    }

    private static void addAssetDirectory(java.util.List<File> directories, File directory) {
        if (!directory.isDirectory()) return;
        for (File existing : directories) {
            if (existing.equals(directory)) return;
        }
        directories.add(directory);
    }

    private static File findAssetFile(String assetName) {
        for (File directory : findAssetDirectories()) {
            File file = new File(directory, assetName);
            if (file.isFile()) return file;
        }
        return null;
    }

    /**
     * Finds a music file: first by its exact name, then any audio file in the assets folder whose name
     * contains every keyword (so "Main_menu_background.mp3" or "Menu Music.mp3" also work).
     */
    private static File findMusicTrack(String exactMp3, String exactMp4, String... keywords) {
        File exact = findAssetFile(exactMp3);
        if (exact == null && exactMp4 != null) exact = findAssetFile(exactMp4);
        if (exact != null) return exact;
        for (File directory : findAssetDirectories()) {
            File[] files = directory.listFiles();
            if (files == null) continue;
            Arrays.sort(files);
            for (File file : files) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                boolean audio = name.endsWith(".mp3") || name.endsWith(".mp4") || name.endsWith(".m4a")
                    || name.endsWith(".wav") || name.endsWith(".wma") || name.endsWith(".aac");
                if (!file.isFile() || !audio) continue;
                boolean matches = true;
                for (String keyword : keywords) if (!name.contains(keyword)) matches = false;
                if (matches) return file;
            }
        }
        return null;
    }

    private void flashBoard(Color color) {
        Color original = board.getBackground();
        board.setBackground(color);
        javax.swing.Timer t = new javax.swing.Timer(350, e -> board.setBackground(original));
        t.setRepeats(false); t.start();
    }

    /** Speaker icon for the music button; a pink cross replaces the sound waves when music is off. */
    private static class SpeakerIcon implements Icon {
        private final boolean on;
        private final Color color;

        SpeakerIcon(boolean on, Color color) {
            this.on = on;
            this.color = color;
        }

        @Override public int getIconWidth() { return 26; }
        @Override public int getIconHeight() { return 22; }

        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(x, y);
            g.setColor(color);
            g.fillRoundRect(2, 7, 5, 8, 2, 2);
            g.fillPolygon(new int[]{6, 13, 13, 6}, new int[]{7, 2, 20, 15}, 4);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (on) {
                g.drawArc(10, 6, 7, 10, -55, 110);
                g.drawArc(12, 2, 12, 18, -55, 110);
            } else {
                g.setColor(KIT_PINK);
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(16, 7, 24, 15);
                g.drawLine(24, 7, 16, 15);
            }
            g.dispose();
        }
    }

    /**
     * Looping background music through one hidden Windows media player holding two tracks
     * (menu and gameplay). Commands: "menu", "game" or "stop".
     * Everything it does (files found, errors, commands) is written to memoryfog-music.log.
     */
    private static class MusicPlayer {
        private static final File LOG = new File(System.getProperty("user.dir"), "memoryfog-music.log");
        private Process process;
        private File commandFile;
        private int commandId;

        private static synchronized void log(String text) {
            try (java.io.FileWriter writer = new java.io.FileWriter(LOG, true)) {
                writer.write(new Date() + "  " + text + System.lineSeparator());
            } catch (Exception ignored) { }
        }

        MusicPlayer(File menuTrack, File gameTrack) {
            LOG.delete();
            log("Working folder: " + System.getProperty("user.dir"));
            log("Menu track: " + (menuTrack == null ? "NOT FOUND" : menuTrack.getAbsolutePath()));
            log("Gameplay track: " + (gameTrack == null ? "NOT FOUND" : gameTrack.getAbsolutePath()));
            if (menuTrack == null || gameTrack == null) {
                StringBuilder folders = new StringBuilder();
                for (File directory : findAssetDirectories()) folders.append(directory.getAbsolutePath()).append("   ");
                log("Asset folders searched: " + (folders.length() == 0 ? "none found" : folders));
            }
            if (menuTrack == null && gameTrack == null) return;
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
                log("Background music is only supported on Windows.");
                return;
            }
            try {
                commandFile = File.createTempFile("squad-flip-music-", ".txt");
                commandFile.deleteOnExit();
                java.nio.file.Files.write(commandFile.toPath(), "0:stop".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String commandPath = commandFile.getAbsolutePath().replace("'", "''");
                String logPath = LOG.getAbsolutePath().replace("'", "''");
                String script = "$ErrorActionPreference='SilentlyContinue'; Add-Type -AssemblyName PresentationCore,WindowsBase; "
                    + "function L($t){ Add-Content -Path '" + logPath + "' -Value ('  [player] ' + $t) }; "
                    + "$script:last='0:stop'; $script:want='stop'; $script:errLogged=$false; "
                    + "$script:parent=" + ProcessHandle.current().pid() + "; "
                    + playerSetup("menu", menuTrack) + playerSetup("game", gameTrack)
                    + "L 'player started'; "
                    + "$frame=New-Object System.Windows.Threading.DispatcherFrame; "
                    + "$timer=New-Object System.Windows.Threading.DispatcherTimer; "
                    + "$timer.Interval=[TimeSpan]::FromMilliseconds(40); $timer.add_Tick({ "
                    + "try{ $v=[IO.File]::ReadAllText('" + commandPath + "').Trim(); "
                    + "if($v -ne $script:last){ $script:last=$v; $script:want=$v.Split(':')[1]; L ('command ' + $v); "
                    + "if($menu){$menu.Pause()}; if($game){$game.Pause()}; "
                    + "if($script:want -eq 'menu' -and $menu){$menu.Position=[TimeSpan]::Zero;$menu.Play()} "
                    + "elseif($script:want -eq 'game' -and $game){$game.Position=[TimeSpan]::Zero;$game.Play()} } "
                    + "}catch{ if(-not $script:errLogged){ $script:errLogged=$true; L ('error: ' + $_.Exception.Message) } }; "
                    + "try{[void][Diagnostics.Process]::GetProcessById($script:parent)}catch{$frame.Continue=$false;$timer.Stop()} }); "
                    + "$timer.Start(); [System.Windows.Threading.Dispatcher]::PushFrame($frame); "
                    + "if($menu){$menu.Close()}; if($game){$game.Close()}";
                process = new ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-WindowStyle", "Hidden", "-Command", script)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(LOG))
                    .start();
                final Process started = process;
                Runtime.getRuntime().addShutdownHook(new Thread(started::destroy));
                log("Music player process started.");
            } catch (Exception ex) {
                process = null;
                log("Could not start the music player: " + ex);
            }
        }

        private static String playerSetup(String name, File file) {
            if (file == null) return "$" + name + "=$null; ";
            String uri = file.toURI().toString().replace("'", "''");
            return "$" + name + "=New-Object System.Windows.Media.MediaPlayer; $" + name + ".Volume=0.4; "
                + "$" + name + ".add_MediaEnded({param($s,$e) $s.Position=[TimeSpan]::Zero; $s.Play()}); "
                + "$" + name + ".add_MediaFailed({param($s,$e) L ('" + name + " FAILED to play: ' + $e.ErrorException.Message)}); "
                + "$" + name + ".add_MediaOpened({param($s,$e) L '" + name + " opened'; "
                + "if($script:want -eq '" + name + "'){$s.Position=[TimeSpan]::Zero; $s.Play()}}); "
                + "$" + name + ".Open([Uri]::new('" + uri + "')); ";
        }

        void send(String command) {
            if (process == null || !process.isAlive() || commandFile == null) {
                log("Cannot send \"" + command + "\": the music player is not running.");
                return;
            }
            try {
                java.nio.file.Files.write(commandFile.toPath(),
                    ((++commandId) + ":" + command).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ex) {
                log("Could not send \"" + command + "\": " + ex);
            }
        }
    }

    /** A bundled MP3 loaded once into a hidden Windows media player so it can start instantly. */
    private static class AudioClip {
        private Process process;
        private File requestFile;
        private int requestId;

        AudioClip(String assetName) {
            File audio = findAssetFile(assetName);
            if (audio == null || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) return;
            try {
                requestFile = File.createTempFile("squad-flip-audio-", ".txt");
                requestFile.deleteOnExit();
                java.nio.file.Files.write(requestFile.toPath(), "0:STOP".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String uri = audio.toURI().toString().replace("'", "''");
                String requestPath = requestFile.getAbsolutePath().replace("'", "''");
                String parentPid = Long.toString(ProcessHandle.current().pid());
                String script = "$ErrorActionPreference='SilentlyContinue'; Add-Type -AssemblyName PresentationCore,WindowsBase; "
                    + "$script:ready=$false; $script:pending=$false; $script:last='0:STOP'; $script:parent=" + parentPid + "; "
                    + "$player=New-Object System.Windows.Media.MediaPlayer; $player.Volume=1.0; "
                    + "$player.add_MediaOpened({$script:ready=$true;if($script:pending){$player.Position=[TimeSpan]::Zero;$player.Play();$script:pending=$false}}); "
                    + "$player.add_MediaFailed({$script:ready=$false}); $player.Open([Uri]::new('" + uri + "')); "
                    + "$frame=New-Object System.Windows.Threading.DispatcherFrame; $timer=New-Object System.Windows.Threading.DispatcherTimer; "
                    + "$timer.Interval=[TimeSpan]::FromMilliseconds(25); $timer.add_Tick({ "
                    + "try{$v=[IO.File]::ReadAllText('" + requestPath + "').Trim();if($v -ne $script:last){$script:last=$v;if($v.EndsWith(':STOP')){$script:pending=$false;$player.Stop()}else{$script:pending=$true;if($script:ready){$player.Stop();$player.Position=[TimeSpan]::Zero;$player.Play();$script:pending=$false}}}}catch{}; "
                    + "try{[void][Diagnostics.Process]::GetProcessById($script:parent)}catch{$frame.Continue=$false;$timer.Stop()} }); "
                    + "$timer.Start();[System.Windows.Threading.Dispatcher]::PushFrame($frame);$player.Close()";
                process = new ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-WindowStyle", "Hidden", "-Command", script)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            } catch (Exception ignored) {
                process = null;
            }
        }

        /** Returns false when the clip can't play, so the caller can use a built-in fallback sound. */
        boolean play() {
            if (process == null || !process.isAlive() || requestFile == null) return false;
            try {
                String request = (++requestId) + ":PLAY";
                java.nio.file.Files.write(requestFile.toPath(), request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        void stop() {
            if (process == null || !process.isAlive() || requestFile == null) return;
            try {
                String request = (++requestId) + ":STOP";
                java.nio.file.Files.write(requestFile.toPath(), request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
    }

    // ---------------- background music ----------------

    private void loadSettings() {
        File file = new File(System.getProperty("user.dir"), "memoryfog-settings.properties");
        if (!file.isFile()) return;
        Properties settings = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            settings.load(input);
            musicEnabled = !"false".equals(settings.getProperty("musicEnabled", "true"));
        } catch (Exception ignored) { }
    }

    private void saveSettings() {
        Properties settings = new Properties();
        settings.setProperty("musicEnabled", String.valueOf(musicEnabled));
        File file = new File(System.getProperty("user.dir"), "memoryfog-settings.properties");
        try (FileOutputStream output = new FileOutputStream(file)) {
            settings.store(output, "Squad Flip settings");
        } catch (Exception ignored) { }
    }

    /** Shows one of the screens ("main", "levels" or "game") and switches the music to match. */
    private void showScreen(String name) {
        ((CardLayout) root.getLayout()).show(root, name);
        setMusicScene("game".equals(name) ? "game" : "menu");
    }

    /** Menu music plays on the menu and level table; gameplay music plays while a level is open. */
    private void setMusicScene(String scene) {
        if (scene.equals(musicScene)) return;
        musicScene = scene;
        applyMusic();
    }

    private void applyMusic() {
        if (music != null) music.send(musicEnabled ? musicScene : "stop");
    }

    private void toggleMusic() {
        musicEnabled = !musicEnabled;
        saveSettings();
        for (JButton b : musicButtons) refreshMusicButton(b);
        applyMusic();
    }

    /** A speaker button (yellow when music is on, dim with a cross when off). */
    private JButton createMusicButton() {
        JButton b = new JButton();
        styleButton(b);
        b.setBorder(new EmptyBorder(8, 8, 8, 8));
        b.setPreferredSize(new Dimension(44, 44));
        b.addActionListener(e -> toggleMusic());
        musicButtons.add(b);
        refreshMusicButton(b);
        return b;
    }

    private void refreshMusicButton(JButton b) {
        if (musicEnabled) {
            styleAs(b, "primary");
            b.setIcon(new SpeakerIcon(true, new Color(26, 16, 51)));
            b.setToolTipText("Music is ON — click to turn it off");
        } else {
            styleAs(b, "secondary");
            b.setIcon(new SpeakerIcon(false, new Color(190, 196, 225)));
            b.setToolTipText("Music is OFF — click to turn it on");
        }
        b.repaint();
    }

    /** Loads the bundled MP3 files once during startup so they play with no delay. */
    private void preloadAudio() {
        startClip = new AudioClip("game-start.mp3");
        winClip = new AudioClip("universfield-game-bonus-144751.mp3");
        uiClickClip = new AudioClip("universfield-bubble-pop-05-323639.mp3");
        matchClip = new AudioClip("subway-surfers-revive.mp3");
        unlockClip = new AudioClip("Brawler_Skin Unlock Sound Effect.mp3");
        unlockClipLoudLayer = new AudioClip("Brawler_Skin Unlock Sound Effect.mp3");
        starClip = new AudioClip("nintendo-game-boy-startup.mp3");
        File menuTrack = findMusicTrack("Main_menu_backround.mp3", null, "menu");
        File gameTrack = findMusicTrack("main_gameplay_music.mp3", "main_gameplay_music.mp4", "gameplay");
        music = new MusicPlayer(menuTrack, gameTrack);
    }

    /** Plays the level-start clip as soon as a level is selected. */
    private void requestLevelStartAudio() {
        if (startClip == null || !startClip.play()) playSound("start");
    }

    /** Plays the bonus jingle when a level is won, just before the next screen appears. */
    private void playWinSound() {
        if (winClip == null || !winClip.play()) playSound("win");
    }

    /** Plays the merge sound (false if it is missing, so the built-in pop is used instead). */
    private static boolean playMergeClip() {
        return matchClip != null && matchClip.play();
    }

    private static boolean playNewUnlockSound() {
        boolean main = unlockClip != null && unlockClip.play();
        boolean layer = unlockClipLoudLayer != null && unlockClipLoudLayer.play();
        return main || layer;
    }

    private static void stopNewUnlockSound() {
        if (unlockClip != null) unlockClip.stop();
        if (unlockClipLoudLayer != null) unlockClipLoudLayer.stop();
    }

    private void playStarSound() {
        if (starClip == null || !starClip.play()) playSound("flip");
    }

    /** Plays the supplied bubble-pop MP3 for every button press, with a short built-in fallback. */
    private static void playUiClickSound() {
        if (uiClickClip == null || !uiClickClip.play()) playSound("click");
    }

    /** Generates short, distinct sound cues with Java's built-in audio support. */
    private static long lastHoverSound = 0;

    /** A very quiet tick when the pointer touches a button (throttled so it never spams). */
    private static void playHoverTick() {
        long now = System.nanoTime();
        if (now - lastHoverSound < 90_000_000L) return;
        lastHoverSound = now;
        playSound("hover");
    }

    private static void playSound(String kind) {
        Thread soundThread = new Thread(() -> {
            final float sampleRate = 22050f;
            AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
            double[][] notes;
            switch (kind) {
                case "wrong": notes = new double[][]{{290, 100}, {205, 155}}; break;
                case "match": notes = new double[][]{{523, 85}, {659, 85}, {784, 170}}; break;
                case "shuffle": notes = new double[][]{{260, 60}, {360, 65}, {480, 75}, {330, 95}}; break;
                case "glitch": notes = new double[][]{{930, 45}, {280, 70}, {780, 55}}; break;
                case "start": notes = new double[][]{{523, 90}, {659, 90}, {784, 160}}; break;
                case "tick": notes = new double[][]{{880, 45}}; break;
                case "laugh": notes = new double[][]{{523, 60}, {440, 60}, {523, 60}, {440, 60}, {523, 60}, {392, 130}}; break;
                case "pop": notes = new double[][]{{784, 60}, {1175, 120}}; break;
                case "click": notes = new double[][]{{880, 28}, {1320, 34}}; break;
                case "hover": notes = new double[][]{{1500, 14}}; break;
                case "win": notes = new double[][]{{523, 90}, {659, 90}, {784, 90}, {1047, 260}}; break;
                default: notes = new double[][]{{740, 55}}; break;
            }

            final double volume = kind.equals("hover") ? 0.07 : kind.equals("click") ? 0.20 : 0.22;
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                line.start();
                for (double[] note : notes) {
                    double frequency = note[0];
                    int frames = (int) (sampleRate * note[1] / 1000.0);
                    byte[] pcm = new byte[frames * 2];
                    for (int i = 0; i < frames; i++) {
                        double progress = (double) i / frames;
                        double envelope = Math.min(1.0, Math.min(progress * 14.0, (1.0 - progress) * 9.0));
                        double sample = Math.sin(2.0 * Math.PI * frequency * i / sampleRate)
                            * envelope * volume;
                        short value = (short) (sample * Short.MAX_VALUE);
                        pcm[i * 2] = (byte) (value & 0xff);
                        pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
                    }
                    line.write(pcm, 0, pcm.length);
                }
                line.drain();
            } catch (Exception ignored) {
                // Audio may be unavailable on some computers; the game remains playable.
            }
        }, "MemoryFog-Sound-" + kind);
        soundThread.setDaemon(true);
        soundThread.start();
    }

    private void loadProgress() {
        if (accountSaveFile == null || !accountSaveFile.isFile()) return;
        Properties saved = new Properties();
        try (FileInputStream input = new FileInputStream(accountSaveFile)) {
            saved.load(input);
            coins = Math.max(0, Integer.parseInt(saved.getProperty("coins", "0")));
            highestUnlockedLevel = Math.max(1, Math.min(MAX_LEVEL,
                Integer.parseInt(saved.getProperty("highestUnlockedLevel", "1"))));
            level = Math.max(1, Math.min(MAX_LEVEL, Integer.parseInt(saved.getProperty("level", "1"))));
            gameCompleted = Boolean.parseBoolean(saved.getProperty("gameCompleted", "false"));
            chainLockIntroSeen = Boolean.parseBoolean(saved.getProperty("chainLockIntroSeen", "false"));
            magnifiers = Math.max(0, Integer.parseInt(saved.getProperty("magnifiers", "0")));
            hints = Math.max(0, Integer.parseInt(saved.getProperty("hints", "0")));
            shields = Math.max(0, Integer.parseInt(saved.getProperty("shields",
                saved.getProperty("blowguns", "0"))));
            shieldArmed = Boolean.parseBoolean(saved.getProperty("shieldArmed", "false"));
            timerBoosts = Math.max(0, Integer.parseInt(saved.getProperty("timerBoosts", "0")));
            selectedCardBack = Math.max(0, Math.min(CARD_BACK_SKINS.length - 1,
                Integer.parseInt(saved.getProperty("selectedCardBack", "0"))));

            String[] unlocked = saved.getProperty("unlockedCardBacks", "1,0,0,0").split(",");
            for (int i = 0; i < Math.min(unlocked.length, unlockedCardBacks.length); i++) {
                unlockedCardBacks[i] = "1".equals(unlocked[i]);
            }
            unlockedCardBacks[0] = true;

            String[] savedStars = saved.getProperty("stars", "").split(",");
            for (int i = 0; i < Math.min(savedStars.length, starsByLevel.length); i++) {
                if (!savedStars[i].isEmpty()) {
                    starsByLevel[i] = Math.max(0, Math.min(3, Integer.parseInt(savedStars[i])));
                }
            }
            discoveredImages.clear();
            String albumData = saved.getProperty("albumImages", "");
            if (!albumData.isEmpty()) {
                for (String encoded : albumData.split(",")) {
                    try {
                        String imageName = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                        if (!imageName.isEmpty()) discoveredImages.add(imageName);
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            if (!unlockedCardBacks[selectedCardBack]) selectedCardBack = 0;
        } catch (Exception ex) {
            System.err.println("Could not load Squad Flip progress: " + ex.getMessage());
            resetProgress();
            level = 1;
            highestUnlockedLevel = 1;
            gameCompleted = false;
        }
    }

    private void saveProgress() {
        if (accountSaveFile == null) return;
        Properties saved = new Properties();
        if (accountSaveFile.isFile()) {
            try (FileInputStream input = new FileInputStream(accountSaveFile)) { saved.load(input); }
            catch (Exception ignored) { }
        }
        saved.setProperty("username", currentUsername == null ? "" : currentUsername);
        saved.setProperty("coins", String.valueOf(coins));
        saved.setProperty("highestUnlockedLevel", String.valueOf(highestUnlockedLevel));
        saved.setProperty("level", String.valueOf(level));
        saved.setProperty("gameCompleted", String.valueOf(gameCompleted));
        saved.setProperty("chainLockIntroSeen", String.valueOf(chainLockIntroSeen));
        saved.setProperty("magnifiers", String.valueOf(magnifiers));
        saved.setProperty("hints", String.valueOf(hints));
        saved.setProperty("shields", String.valueOf(shields));
        saved.setProperty("shieldArmed", String.valueOf(shieldArmed));
        saved.setProperty("timerBoosts", String.valueOf(timerBoosts));
        saved.setProperty("selectedCardBack", String.valueOf(selectedCardBack));
        saved.setProperty("unlockedCardBacks", joinBooleans(unlockedCardBacks));
        saved.setProperty("stars", joinInts(starsByLevel));
        StringJoiner album = new StringJoiner(",");
        for (String imageName : discoveredImages) {
            album.add(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(imageName.getBytes(StandardCharsets.UTF_8)));
        }
        saved.setProperty("albumImages", album.toString());
        try {
            writeProperties(accountSaveFile, saved, "Squad Flip account and saved progress");
        } catch (Exception ex) {
            System.err.println("Could not save Squad Flip progress: " + ex.getMessage());
        }
    }

    private void writeProperties(File file, Properties properties, String comment) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create the local save folder.");
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, comment);
        }
    }

    private String joinBooleans(boolean[] values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append(',');
            result.append(values[i] ? '1' : '0');
        }
        return result.toString();
    }

    private String joinInts(int[] values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append(',');
            result.append(values[i]);
        }
        return result.toString();
    }

    private void updateLabels() {
        String bestStars = starsByLevel[level] > 0 ? "   " + "★ ".repeat(starsByLevel[level]) : "";
        levelLabel.setText("Level " + level + bestStars + (isChallengeLevel() ? "  CHALLENGE" : ""));
        moneyLabel.setText("🪙 " + coins);
        timerLabel.setText(String.format("⏱ %d:%02d", secondsLeft / 60, secondsLeft % 60));
        // The timer badge flashes red during the last ten seconds.
        boolean lowTime = secondsLeft <= 10 && secondsLeft > 0 && clock.isRunning();
        timerLabel.setUrgent(lowTime);
        inventoryLabel.setText("In your bag: 🔍 " + magnifiers + "   💡 " + hints
            + "   🛡 " + shields + (shieldArmed ? " (armed)" : "") + "   ⏱ " + timerBoosts);
        magnifyButton.setText(magnifierActive
            ? "🔍 X-Ray (" + magnifierSeconds + "s)"
            : "🔍 Magnify (" + magnifiers + ")");
        hintButton.setText("💡 Hint (" + hints + ")");
        shieldButton.setText(shieldArmed
            ? "🛡 Armed" : "🛡 Shield (" + shields + ")");
        timerButton.setText("⏱ +60 sec (" + timerBoosts + ")");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MemoryFogGame::new);
    }
}

