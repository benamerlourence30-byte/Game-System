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

    /** A card button that can flip, spin, and fade using Swing's repaint loop. */
    private static class AnimatedCardButton extends JButton {
        private double flipProgress = 1.0;
        private double matchProgress = -1.0;
        private BufferedImage cardImage;
        private String variantLabel = "A";
        private boolean hintVisible;
        private boolean xrayVisible;
        private boolean distractionVisible;
        private double xrayProgress = 1.0;
        private double hover = 0.0;
        private double dealProgress = 1.0;
        private double shakeProgress = -1.0;
        private javax.swing.Timer hoverTimer;
        private Color frameColor = KIT_YELLOW;

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

    /** Rounded stat badge used in the game header. */
    private static class PillLabel extends JLabel {
        private Color fill;

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

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g.setColor(new Color(255, 255, 255, 24));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /**
     * Transparent overlay (the window's glass pane) for sparkle bursts, confetti,
     * floating combo text, screen flashes and the big level banner.
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
        private static final int MATCH_POP = 420;        // ms: merged card pops up
        private static final int MATCH_UNLOCK = 1500;    // ms: the board can be played again
        private static final int MATCH_HOLD_END = 2300;  // ms: showcase held on screen until here
        private static final int MATCH_END = 2700;       // ms: showcase fully faded
        private long matchStart = -1;
        private BufferedImage matchImage;
        private String matchBadge = "A";
        private Color matchFrame = Color.WHITE;
        private Rectangle matchFromA = new Rectangle(), matchFromB = new Rectangle();
        private Runnable matchDone;
        private boolean matchBurst, matchDoneFired;

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

        /** Starts the fly-merge-pop-up animation. {@code onUnlock} runs when the board may be used again. */
        void showMatch(BufferedImage image, String badge, Color frame, Rectangle fromA, Rectangle fromB, Runnable onUnlock) {
            matchImage = image;
            matchBadge = badge;
            matchFrame = frame;
            matchFromA = new Rectangle(fromA);
            matchFromB = new Rectangle(fromB);
            matchDone = onUnlock;
            matchBurst = false;
            matchDoneFired = false;
            matchStart = System.nanoTime();
            wake();
        }

        void cancelMatch() {
            matchStart = -1;
            matchDone = null;
            repaint();
        }

        private Point matchCenter() {
            return new Point(getWidth() / 2, (int) (getHeight() * 0.46));
        }

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

        private void paintMatch(Graphics2D g0, double ms) {
            Point c = matchCenter();
            int showH = (int) Math.min(getHeight() * 0.66, 560);
            int showW = (int) (showH * 0.75);
            if (ms < MATCH_FLY) {
                double t = ms / MATCH_FLY;
                double e = t * t * (3 - 2 * t);
                for (int k = 1; k >= 0; k--) {          // light motion trail behind each card
                    double ek = Math.max(0.0, e - 0.045 * k);
                    float alpha = k == 0 ? 1f : 0.16f / k;
                    drawGhost(g0, matchFromA, c, ek, -1, showW, showH, alpha);
                    drawGhost(g0, matchFromB, c, ek, +1, showW, showH, alpha);
                }
                return;
            }
            double pop = Math.min(1.0, (ms - MATCH_FLY) / MATCH_POP);
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
                if (!matchBurst && ms >= MATCH_FLY) {
                    matchBurst = true;
                    burst(matchCenter(), KIT_YELLOW, 10);
                    burst(matchCenter(), Color.WHITE, 4);
                    flash(new Color(255, 255, 255, 90));
                    playSound("pop");
                }
                if (!matchDoneFired && ms >= MATCH_UNLOCK) {
                    matchDoneFired = true;
                    Runnable done = matchDone;
                    matchDone = null;
                    if (done != null) done.run();
                }
                if (ms >= MATCH_END) matchStart = -1;
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
    private final JButton mainMenuMusicButton = new JButton("♫ MUSIC ON");
    private final JButton gameMusicButton = new JButton("♫");
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
    private AudioClip menuMusicClip, gameplayMusicClip;
    private boolean musicEnabled = true;
    private boolean gameplayMusicActive = false;
    private static AudioClip uiClickClip;
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
    private int wrongGuesses = 0;
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
    private String currentUsername;
    private File accountSaveFile;
    private int shopPauseDepth;
    private boolean shopClockWasRunning;

    private static class Card {
        String face;
        String imageName;
        String variant;
        final AnimatedCardButton button = new AnimatedCardButton();
        boolean matched = false;
        boolean revealed = false;
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

        JPanel header = new JPanel(new BorderLayout(10, 7));
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
        styleButton(gameMusicButton, "secondary");
        gameMusicButton.setPreferredSize(new Dimension(44, 40));
        updateMusicToggleButtons();
        stats.add(gameMusicButton);
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
        root.add(mainMenuScreen, "main");
        root.add(levelSelectScreen, "levels");
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
        gameMusicButton.addActionListener(e -> toggleMusic());
        startLevelPulse();
        frame.setVisible(true);
    }

    private void showHowToPlay() {
        JOptionPane.showMessageDialog(frame,
            "Choose any unlocked level from the table.\n"
            + "Flip two cards to find a matching pair. Every third wrong guess shuffles face-down cards.\n"
            + "From Level 6, brief glitch decoys may flash false pictures.\n"
            + "Clear a level to unlock the next one and earn " + LEVEL_REWARD + " coins. Stars show your best rating.",
            "How to play", JOptionPane.INFORMATION_MESSAGE);
    }

    private void openLevelSelect() {
        refreshLevelSelect();
        animateLevelTiles();
        ((CardLayout) root.getLayout()).show(root, "levels");
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
        styleButton(mainMenuMusicButton, "secondary");
        mainMenuMusicButton.setFont(new Font("SansSerif", Font.BOLD, 11));
        mainMenuMusicButton.setPreferredSize(new Dimension(112, 42));
        mainMenuMusicButton.setToolTipText("Turn background music on or off");
        mainMenuMusicButton.addActionListener(event -> toggleMusic());
        JButton info = new JButton("?");
        styleButton(info);
        info.setPreferredSize(new Dimension(44, 44));
        info.setToolTipText("How to play");
        info.addActionListener(event -> showHowToPlay());
        tools.add(mainMenuCoinsLabel);
        tools.add(mainMenuMusicButton);
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
        NavBar nav = new NavBar(new String[]{"🏠", "🎯", "🛒", "🚪"},
            new String[]{"HOME", "LEVELS", "SHOP", "QUIT"}, 0,
            new Runnable[]{() -> { }, this::openLevelSelect, this::showShop, () -> frame.dispose()});
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
                level = selectedLevel;
                gameCompleted = false;
                requestLevelStartAudio();
                ((CardLayout) root.getLayout()).show(root, "game");
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
        NavBar nav = new NavBar(new String[]{"🏠", "🎯", "🛒", "🚪"},
            new String[]{"HOME", "LEVELS", "SHOP", "QUIT"}, 1,
            new Runnable[]{() -> ((CardLayout) root.getLayout()).show(root, "main"), () -> { },
                this::showShop, () -> frame.dispose()});
        screen.add(content, BorderLayout.CENTER);
        screen.add(nav, BorderLayout.SOUTH);
        refreshLevelSelect();
        return screen;
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
        setGameplayMusicActive(false);
        deactivateMagnifier();
        clearDistraction();
        refreshLevelSelect();
        animateLevelTiles();
        ((CardLayout) root.getLayout()).show(root, "levels");
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
        setGameplayMusicActive(true);
        levelGeneration++;
        effects.cancelMatch();
        clock.stop();
        deactivateMagnifier();
        clearDistraction();
        secondsLeft = 300;
        wrongGuesses = 0;
        matchedPairs = 0;
        comboStreak = 0;
        firstCard = secondCard = -1;
        lockCount = 0;
        distractionsRemaining = level >= 6
            ? (isChallengeLevel() ? 5 + random.nextInt(3) : 3 + random.nextInt(3))
            : 0;
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
            card.button.addActionListener(e -> flip(cards.indexOf(card)));
            card.button.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) {
                    if (magnifierActive && !card.matched && !card.revealed) {
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
        statusLabel.setText(challenge
            ? "CHALLENGE LEVEL! More glitch decoys are coming. " + pairs + " pairs — " + THEME_NAMES[theme] + " theme."
            : "Find " + pairs + " pairs — " + THEME_NAMES[theme] + " theme.");
        updateLabels();
        board.revalidate();
        board.repaint();
        effects.banner("LEVEL " + level,
            challenge ? "CHALLENGE LEVEL  •  " + pairs + " PAIRS"
                      : pairs + " PAIRS  •  " + THEME_NAMES[theme] + " THEME",
            challenge ? new Color(255, 120, 120) : new Color(136, 235, 196));
        clock.start();
    }

    private void flip(int index) {
        if (lockCount > 0 || index < 0 || index >= cards.size()) return;
        Card card = cards.get(index);
        if (card.matched || card.revealed) return;
        playSound("flip");
        reveal(index);
        if (firstCard < 0) {
            firstCard = index;
            return;
        }
        secondCard = index;
        Card first = cards.get(firstCard);
        if (first.face.equals(card.face)) {
            playSound("match");
            first.matched = card.matched = true;
            first.button.setBackground(new Color(52, 126, 104));
            card.button.setBackground(new Color(52, 126, 104));
            first.button.setFrameColor(KIT_TEAL);
            card.button.setFrameColor(KIT_TEAL);
            matchedPairs++;
            firstCard = secondCard = -1;
            lockCount++;
            statusLabel.setText("Match! Nice one!");
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
                effects.showMatch(art, first.variant, KIT_TEAL, ra, rb, () -> {
                    if (generation != levelGeneration) return;
                    lockCount--;
                    if (matchedPairs == cards.size() / 2) levelWon();
                    else maybeTriggerDistraction();
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
            if (shielded) {
                shieldArmed = false;
                saveProgress();
                updateLabels();
            } else {
                wrongGuesses++;
            }
            int a = firstCard, b = secondCard;
            lockCount++;
            statusLabel.setText(shielded
                ? "🛡 Shield absorbed the mistake — no shuffle!"
                : wrongGuesses % 3 == 0
                ? "⚠ Three wrong guesses! Unflipped cards are being shuffled!"
                : "Not a match — try again.");
            final int generation = levelGeneration;
            javax.swing.Timer pause = new javax.swing.Timer(850, e -> {
                if (generation != levelGeneration) return;
                hide(a);
                hide(b);
                firstCard = secondCard = -1;
                lockCount--;
                if (shielded) maybeTriggerDistraction();
                else if (wrongGuesses % 3 == 0) shuffleUnflippedCards();
                else maybeTriggerDistraction();
                if (statusLabel.getText().startsWith("Not a match")) statusLabel.setText("Keep going!");
            });
            pause.setRepeats(false);
            pause.start();
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
                maybeTriggerDistraction();
            }
        });
        animation.start();
    }

    private void maybeTriggerDistraction() {
        if (level < 6 || distractionsRemaining <= 0 || distractionActive) return;
        int chance = isChallengeLevel() ? 45 : 28;
        if (random.nextInt(100) < chance) triggerDistraction();
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

    private void triggerDistraction() {
        java.util.List<Card> hidden = new ArrayList<>();
        for (Card card : cards) {
            if (!card.matched && !card.revealed && !card.button.isXrayVisible()) hidden.add(card);
        }
        if (hidden.size() < 2) return;
        Collections.shuffle(hidden, random);
        int flashes = Math.min(hidden.size(), 2 + random.nextInt(2));
        distractionsRemaining--;
        distractionActive = true;
        playSound("glitch");
        effects.flash(new Color(255, 60, 90, 80));
        statusLabel.setText("⚡ GLITCH! A few cards are showing decoy pictures!");

        java.util.List<Card> flashingCards = hidden.subList(0, flashes);
        for (Card card : flashingCards) {
            String decoy = CARD_IMAGES[random.nextInt(CARD_IMAGES.length)];
            for (int tries = 0; tries < CARD_IMAGES.length && decoy.equals(card.imageName); tries++) {
                decoy = CARD_IMAGES[random.nextInt(CARD_IMAGES.length)];
            }
            card.button.showDistraction(loadCardImage(decoy), decoy);
        }

        if (distractionTimer != null) distractionTimer.stop();
        distractionTimer = new javax.swing.Timer(1050, event -> {
            for (Card card : flashingCards) {
                if (card.button.isDistractionVisible() && !card.revealed) {
                    card.button.hideDistraction();
                    card.button.setBackground(getCardBackColor());
                }
            }
            distractionActive = false;
            distractionTimer = null;
            statusLabel.setText("The glitch is gone. Keep finding pairs!");
        });
        distractionTimer.setRepeats(false);
        distractionTimer.start();
    }

    private void clearDistraction() {
        if (distractionTimer != null) distractionTimer.stop();
        distractionTimer = null;
        distractionActive = false;
        for (Card card : cards) {
            if (card.button.isDistractionVisible()) card.button.hideDistraction();
        }
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
                setGameplayMusicActive(false);
                refreshLevelSelect();
                animateLevelTiles();
                ((CardLayout) root.getLayout()).show(root, "levels");
            } else {
                level++;
                saveProgress();
                ((CardLayout) root.getLayout()).show(root, "game");
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
        if (wrongGuesses <= Math.max(1, pairs / 3) && timeLeftRatio >= 0.35) return 3;
        if (wrongGuesses <= pairs && secondsLeft > 0) return 2;
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
        dialog.setSize(440, 390);
        dialog.setResizable(false);
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBackground(NAVY);
        panel.setBorder(new EmptyBorder(20, 24, 18, 24));
        JLabel title = new JLabel("WELCOME TO SQUAD FLIP", SwingConstants.CENTER);
        title.setForeground(KIT_YELLOW);
        title.setFont(new Font("SansSerif", Font.BOLD, 21));
        panel.add(title, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(PANEL_DARK);
        tabs.setForeground(Color.WHITE);
        JComboBox<String> accounts = new JComboBox<>(listAccountNames());
        accounts.setEditable(true);
        JPasswordField loginPassword = new JPasswordField(18);
        JPanel login = accountForm();
        addAccountRow(login, "Saved account", accounts);
        addAccountRow(login, "Password", loginPassword);
        JButton signIn = new JButton("SIGN IN");
        styleButton(signIn, "primary");
        login.add(signIn);
        tabs.addTab("SIGN IN", login);

        JTextField newName = new JTextField(18);
        JPasswordField newPassword = new JPasswordField(18);
        JPasswordField confirmPassword = new JPasswordField(18);
        JPanel create = accountForm();
        addAccountRow(create, "Username", newName);
        addAccountRow(create, "Password", newPassword);
        addAccountRow(create, "Confirm password", confirmPassword);
        JButton createAccount = new JButton("CREATE ACCOUNT");
        styleButton(createAccount, "success");
        create.add(createAccount);
        tabs.addTab("CREATE ACCOUNT", create);
        panel.add(tabs, BorderLayout.CENTER);

        JLabel message = new JLabel("Create an account to save your levels and stars.", SwingConstants.CENTER);
        message.setForeground(new Color(195, 203, 232));
        message.setFont(new Font("SansSerif", Font.PLAIN, 12));
        panel.add(message, BorderLayout.SOUTH);
        signIn.addActionListener(event -> {
            String username = Objects.toString(accounts.getEditor().getItem(), "").trim();
            try {
                if (!authenticateAccount(username, loginPassword.getPassword())) {
                    message.setForeground(KIT_PINK);
                    message.setText("Account or password is incorrect.");
                    playSound("wrong");
                    return;
                }
                activateAccount(username);
                dialog.dispose();
                openLevelSelect();
            } catch (Exception ex) {
                message.setForeground(KIT_PINK);
                message.setText("Could not open account: " + ex.getMessage());
            }
        });
        createAccount.addActionListener(event -> {
            String username = newName.getText().trim();
            char[] password = newPassword.getPassword();
            char[] confirmation = confirmPassword.getPassword();
            try {
                if (!username.matches("[A-Za-z0-9_-]{3,20}")) {
                    message.setForeground(KIT_PINK);
                    message.setText("Username: 3–20 letters, numbers, _ or -.");
                    return;
                }
                if (password.length < 6) {
                    message.setForeground(KIT_PINK);
                    message.setText("Password must be at least 6 characters.");
                    return;
                }
                if (!Arrays.equals(password, confirmation)) {
                    message.setForeground(KIT_PINK);
                    message.setText("The passwords do not match.");
                    return;
                }
                File file = accountFile(username);
                if (file.exists()) {
                    message.setForeground(KIT_PINK);
                    message.setText("That username already exists. Sign in instead.");
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
                dialog.dispose();
                openLevelSelect();
            } catch (Exception ex) {
                message.setForeground(KIT_PINK);
                message.setText("Could not create account: " + ex.getMessage());
            } finally {
                Arrays.fill(password, '\0');
                Arrays.fill(confirmation, '\0');
            }
        });
        if (accounts.getItemCount() == 0) tabs.setSelectedIndex(1);
        dialog.setContentPane(panel);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private JPanel accountForm() {
        JPanel panel = new JPanel(new GridLayout(0, 1, 8, 8));
        panel.setBackground(NAVY);
        panel.setBorder(new EmptyBorder(12, 12, 8, 12));
        return panel;
    }

    private void addAccountRow(JPanel panel, String label, JComponent input) {
        JLabel text = new JLabel(label);
        text.setForeground(Color.WHITE);
        text.setFont(new Font("SansSerif", Font.BOLD, 12));
        panel.add(text);
        panel.add(input);
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
        highestUnlockedLevel = 1;
        level = 1;
        gameCompleted = false;
        magnifiers = hints = shields = timerBoosts = 0;
        shieldArmed = false;
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
            if (!c.matched && !c.revealed) found.computeIfAbsent(c.face, k -> new ArrayList<>()).add(i);
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

    private void flashBoard(Color color) {
        Color original = board.getBackground();
        board.setBackground(color);
        javax.swing.Timer t = new javax.swing.Timer(350, e -> board.setBackground(original));
        t.setRepeats(false); t.start();
    }

    /** A bundled MP3 loaded once into a hidden Windows media player so it can start instantly. */
    private static class AudioClip {
        private Process process;
        private File requestFile;
        private int requestId;
        private final boolean loop;

        AudioClip(String assetName) {
            this(assetName, false);
        }

        AudioClip(String assetName, boolean loop) {
            this.loop = loop;
            File audio = findAssetFile(assetName);
            if (audio == null || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) return;
            try {
                requestFile = File.createTempFile("squad-flip-audio-", ".txt");
                requestFile.deleteOnExit();
                java.nio.file.Files.write(requestFile.toPath(), "0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String uri = audio.toURI().toString().replace("'", "''");
                String requestPath = requestFile.getAbsolutePath().replace("'", "''");
                String parentPid = Long.toString(ProcessHandle.current().pid());
                String script = "$ErrorActionPreference='SilentlyContinue'; Add-Type -AssemblyName PresentationCore,WindowsBase; "
                    + "$script:ready=$false; $script:pending=$false; $script:last='0'; $script:parent=" + parentPid + "; "
                    + "$script:loop=" + (loop ? "$true" : "$false") + "; "
                    + "$player=New-Object System.Windows.Media.MediaPlayer; $player.Volume=1.0; "
                    + "$player.add_MediaOpened({$script:ready=$true;if($script:pending){$player.Position=[TimeSpan]::Zero;$player.Play();$script:pending=$false}}); "
                    + "$player.add_MediaEnded({if($script:loop){$player.Position=[TimeSpan]::Zero;$player.Play()}}); "
                    + "$player.add_MediaFailed({$script:ready=$false}); $player.Open([Uri]::new('" + uri + "')); "
                    + "$frame=New-Object System.Windows.Threading.DispatcherFrame; $timer=New-Object System.Windows.Threading.DispatcherTimer; "
                    + "$timer.Interval=[TimeSpan]::FromMilliseconds(25); $timer.add_Tick({ "
                    + "try{$v=[IO.File]::ReadAllText('" + requestPath + "').Trim();if($v -ne $script:last){$script:last=$v;if($v.StartsWith('S:')){$script:pending=$false;$player.Stop()}else{$script:pending=$true;if($script:ready){$player.Stop();$player.Position=[TimeSpan]::Zero;$player.Play();$script:pending=$false}}}}catch{}; "
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
                String request = "P:" + (++requestId);
                java.nio.file.Files.write(requestFile.toPath(), request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        void stop() {
            if (process == null || !process.isAlive() || requestFile == null) return;
            try {
                String request = "S:" + (++requestId);
                java.nio.file.Files.write(requestFile.toPath(), request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
    }

    /** Loads the bundled MP3 files once during startup so they play with no delay. */
    private void preloadAudio() {
        startClip = new AudioClip("game-start.mp3");
        winClip = new AudioClip("universfield-game-bonus-144751.mp3");
        uiClickClip = new AudioClip("universfield-bubble-pop-05-323639.mp3");
        starClip = new AudioClip("nintendo-game-boy-startup.mp3");
        menuMusicClip = new AudioClip("Main menu backround.mp3", true);
        gameplayMusicClip = new AudioClip("main gameplay music.mp4", true);
        setGameplayMusicActive(false);
    }

    private void toggleMusic() {
        musicEnabled = !musicEnabled;
        updateMusicToggleButtons();
        setGameplayMusicActive(gameplayMusicActive);
    }

    private void updateMusicToggleButtons() {
        String state = musicEnabled ? "ON" : "OFF";
        mainMenuMusicButton.setText("♫ MUSIC " + state);
        mainMenuMusicButton.setToolTipText(musicEnabled ? "Mute background music" : "Enable background music");
        gameMusicButton.setText(musicEnabled ? "♫" : "♪×");
        gameMusicButton.setToolTipText(musicEnabled ? "Mute background music" : "Enable background music");
    }

    private void setGameplayMusicActive(boolean gameplay) {
        gameplayMusicActive = gameplay;
        if (menuMusicClip == null || gameplayMusicClip == null) return;
        if (!musicEnabled) {
            menuMusicClip.stop();
            gameplayMusicClip.stop();
        } else if (gameplay) {
            menuMusicClip.stop();
            gameplayMusicClip.play();
        } else {
            gameplayMusicClip.stop();
            menuMusicClip.play();
        }
    }

    /** Plays the level-start clip as soon as a level is selected. */
    private void requestLevelStartAudio() {
        if (startClip == null || !startClip.play()) playSound("start");
    }

    /** Plays the bonus jingle when a level is won, just before the next screen appears. */
    private void playWinSound() {
        if (winClip == null || !winClip.play()) playSound("win");
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
        saved.setProperty("magnifiers", String.valueOf(magnifiers));
        saved.setProperty("hints", String.valueOf(hints));
        saved.setProperty("shields", String.valueOf(shields));
        saved.setProperty("shieldArmed", String.valueOf(shieldArmed));
        saved.setProperty("timerBoosts", String.valueOf(timerBoosts));
        saved.setProperty("selectedCardBack", String.valueOf(selectedCardBack));
        saved.setProperty("unlockedCardBacks", joinBooleans(unlockedCardBacks));
        saved.setProperty("stars", joinInts(starsByLevel));
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
        timerLabel.setFill(lowTime
            ? (secondsLeft % 2 == 0 ? KIT_PINK : new Color(120, 24, 66))
            : PANEL_DARK);
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
