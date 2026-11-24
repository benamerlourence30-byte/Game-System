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
import java.awt.event.AWTEventListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;


public class MemoryFogGame {
    private static final int MAX_LEVEL = 50;
    private static final int VARIANTS_PER_IMAGE = 9;
    private static final Color BACKGROUND = new Color(22, 28, 48);
    private static final Color PANEL = new Color(35, 44, 70);
    private static final Color ACCENT = new Color(111, 190, 255);
    private static final String[] CARD_IMAGES = {
        "blue-face.png", "yellow-face.png", "dress-collage.png",
        "monkey.png", "jun.jpg", "tandang-nick.jpg"
    };
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
    private static final File SAVE_FILE = new File(System.getProperty("user.dir"), "memoryfog-save.properties");
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
            @Override public void mouseEntered(MouseEvent e) { if (b.isEnabled()) animateHover(b, 1f); }
            @Override public void mouseExited(MouseEvent e) { animateHover(b, 0f); }
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

    /** Paints rounded, glossy buttons. Reads "hover", "pop", "pulse" and "outline" client properties. */
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
                double s = Math.max(0.02, easeOutBack(pop));
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, pop * 2.5f)));
                g.translate(w * (1.0 - s) / 2.0, h * (1.0 - s) / 2.0);
                g.scale(s, s);
            }

            int arc = 18;
            int lift = pressed ? 3 : 0;
            Color base = b.getBackground();
            if (enabled) {
                g.setColor(new Color(0, 0, 0, 80));
                g.fillRoundRect(1, 4, w - 2, h - 5, arc, arc);
            }
            Color top = mix(base, Color.WHITE, Math.max(0f, 0.10f + 0.14f * hover - (pressed ? 0.08f : 0f)));
            Color bottom = mix(base, Color.BLACK, 0.14f);
            g.setPaint(new GradientPaint(0, lift, top, 0, h - 4 + lift, bottom));
            g.fillRoundRect(1, 1 + lift, w - 2, h - 5, arc, arc);
            g.setColor(new Color(255, 255, 255, 36));
            g.fillRoundRect(4, 3 + lift, Math.max(1, w - 8), Math.max(1, (h - 5) / 2 - 2), arc - 6, arc - 6);

            Object ol = b.getClientProperty("outline");
            Color outline = ol instanceof Color ? (Color) ol : mix(base, Color.WHITE, 0.4f);
            g.setStroke(new BasicStroke(1.6f));
            g.setColor(new Color(outline.getRed(), outline.getGreen(), outline.getBlue(),
                Math.min(255, (int) (170 + 85 * hover))));
            g.drawRoundRect(1, 1 + lift, w - 3, h - 6, arc, arc);
            if (pulse > 0f) {
                g.setStroke(new BasicStroke(2.4f));
                g.setColor(new Color(255, 226, 130, (int) (40 + 190 * pulse)));
                g.drawRoundRect(3, 3 + lift, w - 7, h - 10, arc - 3, arc - 3);
            }
            g.translate(0, lift - 1);
            super.paint(g, c);
            g.dispose();
        }
    }

    /** A card button that can flip, spin, and fade using Swing's repaint loop. */
    private static class AnimatedCardButton extends JButton {
        private double flipProgress = 1.0;
        private double matchProgress = -1.0;
        private BufferedImage cardImage;
        private String variantLabel = "A";
        private boolean hintVisible;
        private boolean xrayVisible;
        private boolean frozenVisible;
        private double xrayProgress = 1.0;
        private double hover = 0.0;
        private double dealProgress = 1.0;
        private double shakeProgress = -1.0;
        private javax.swing.Timer hoverTimer;

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
            frozenVisible = false;
            putClientProperty("imageName", null);
            setText(image == null ? "?" : "");
            repaint();
        }

        void showImage(BufferedImage image, String imageName, String variant) {
            cardImage = image;
            variantLabel = variant;
            xrayVisible = false;
            frozenVisible = false;
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

        void setFrozenVisible(boolean visible) { frozenVisible = visible; repaint(); }

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

            // Rounded, layered card artwork: navy patterned backs and bright faces.
            g.setColor(new Color(5, 9, 24, 90));
            g.fillRoundRect(3, 5, w - 6, h - 7, 22, 22);
            Color base = getBackground();
            Color top = base.brighter();
            Color bottom = base.darker();
            g.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
            g.fillRoundRect(2, 2, w - 6, h - 7, 22, 22);

            boolean faceDown = "?".equals(getText()) && !xrayVisible && !hintVisible;
            if (faceDown) {
                g.setColor(new Color(208, 224, 255, 45));
                g.drawRoundRect(7, 7, w - 16, h - 17, 17, 17);
                g.drawOval(w / 2 - 23, h / 2 - 23, 42, 42);
                g.setColor(new Color(213, 231, 255, 70));
                g.fillOval(w / 2 - 19, h / 2 - 19, 34, 34);
                g.setColor(new Color(235, 243, 255, 100));
                g.setFont(new Font("SansSerif", Font.BOLD, 10));
                g.drawString("✦   ✧", 11, 22);
                g.drawString("✧   ✦", Math.max(10, w - 44), h - 12);
                // Occasional shine sweeping across face-down cards.
                double sweepT = (System.nanoTime() / 1e9 + getX() * 0.0025 + getY() * 0.0035) % 5.0;
                if (sweepT < 0.8) {
                    double sp = sweepT / 0.8;
                    int sx = (int) (-24 + sp * (w + 48));
                    Shape sweepClip = g.getClip();
                    g.clip(new RoundRectangle2D.Float(2, 2, w - 6, h - 7, 22, 22));
                    g.setColor(new Color(255, 255, 255, 46));
                    g.fillPolygon(new int[]{sx, sx + 16, sx - 8, sx - 24}, new int[]{0, 0, h, h}, 4);
                    g.setClip(sweepClip);
                }
            } else {
                if (cardImage != null) {
                    int ix = 8, iy = 8, iw = Math.max(1, w - 20), ih = Math.max(1, h - 20);
                    Shape oldClip = g.getClip();
                    g.clip(new RoundRectangle2D.Float(ix, iy, iw, ih, 15, 15));
                    double scale = Math.min((double) iw / cardImage.getWidth(), (double) ih / cardImage.getHeight());
                    if (!"dress-collage.png".equals(getClientProperty("imageName"))) {
                        scale = Math.max((double) iw / cardImage.getWidth(), (double) ih / cardImage.getHeight());
                    }
                    int dw = (int) (cardImage.getWidth() * scale);
                    int dh = (int) (cardImage.getHeight() * scale);
                    int dx = ix + (iw - dw) / 2;
                    int dy = iy + (ih - dh) / 2;
                    if (dh > ih) dy = iy + (int) ((ih - dh) * 0.38);
                    g.drawImage(cardImage, dx, dy, dw, dh, null);
                    g.setClip(oldClip);
                    g.setColor(new Color(255, 255, 255, 55));
                    g.drawRoundRect(ix, iy, iw, ih, 15, 15);
                    g.setColor(new Color(19, 27, 48, 205));
                    g.fillRoundRect(w - 28, 9, 17, 17, 8, 8);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("SansSerif", Font.BOLD, 10));
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(variantLabel, w - 28 + (17 - fm.stringWidth(variantLabel)) / 2, 21);
                } else {
                    g.setColor(new Color(255, 255, 255, 55));
                    g.fillRoundRect(8, 8, w - 20, 5, 5, 5);
                }
                g.setColor(new Color(255, 255, 255, 110));
                g.drawRoundRect(3, 3, w - 8, h - 9, 20, 20);
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
            if (frozenVisible) {
                g.setColor(new Color(70, 210, 255, 75));
                g.fillRoundRect(7, 7, w - 17, h - 18, 16, 16);
                g.setColor(new Color(132, 239, 255, 230));
                g.setStroke(new BasicStroke(3f));
                g.drawRoundRect(4, 4, w - 11, h - 14, 18, 18);
                g.setFont(new Font("SansSerif", Font.BOLD, 18));
                g.drawString("❄", w - 25, 26);
                g.setStroke(new BasicStroke(1f));
            }
            if (hover > 0.02 && matchProgress < 0.0) {
                g.setColor(new Color(255, 255, 255, (int) (120 * hover)));
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(3, 3, w - 8, h - 9, 20, 20);
                g.setStroke(new BasicStroke(1f));
            }
            if (shakeProgress >= 0.0) {
                g.setColor(new Color(255, 70, 90, (int) (120 * (1.0 - shakeProgress))));
                g.fillRoundRect(2, 2, w - 6, h - 7, 22, 22);
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
            g.setPaint(new GradientPaint(0, 0, new Color(18, 24, 48), w, h, new Color(48, 63, 105)));
            g.fillRect(0, 0, w, h);

            for (int k = 0; k < 2; k++) {
                double cx = w * (0.28 + 0.44 * k) + Math.sin(time * 0.25 + k * 2.1) * w * 0.12;
                double cy = h * (0.32 + 0.30 * k) + Math.cos(time * 0.21 + k) * h * 0.10;
                float radius = Math.max(120f, Math.min(w, h) * 0.55f);
                Color glow = k == 0 ? new Color(111, 160, 255) : new Color(160, 120, 255);
                g.setPaint(new RadialGradientPaint(new Point((int) cx, (int) cy), radius,
                    new float[]{0f, 1f},
                    new Color[]{new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 46),
                                new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 0)}));
                g.fillRect(0, 0, w, h);
            }

            for (double[] d : dots) {
                double y = (1.0 - ((d[1] + time * d[2]) % 1.0)) * h;
                double x = d[0] * w + Math.sin(time * 0.6 + d[4]) * 18;
                int a = (int) (26 + 34 * (Math.sin(time + d[4]) + 1) / 2);
                int size = (int) d[3];
                g.setColor(new Color(160, 205, 255, a));
                g.fillOval((int) x, (int) y, size, size);
                if (size > 8) {
                    g.setColor(new Color(200, 225, 255, a));
                    g.drawOval((int) x - 2, (int) y - 2, size + 4, size + 4);
                }
            }
            g.dispose();
        }
    }

    /** Title text with a bobbing motion, drop shadow and a golden shine sweeping across it. */
    private static class ShimmerLabel extends JLabel {
        ShimmerLabel(String text) {
            super(text, SwingConstants.CENTER);
            setBorder(new EmptyBorder(6, 0, 6, 0));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            String text = getText();
            int tw = fm.stringWidth(text);
            double now = System.nanoTime() / 1e9;
            int x = (getWidth() - tw) / 2;
            int bob = (int) (Math.sin(now * 2.0) * 3);
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent() + bob;
            g.setColor(new Color(0, 0, 0, 110));
            g.drawString(text, x + 3, y + 4);
            double sweep = (now % 3.0) / 3.0;
            float sx = (float) (x - tw * 0.3 + sweep * tw * 1.6);
            g.setPaint(new LinearGradientPaint(sx - 120f, 0f, sx + 120f, 0f,
                new float[]{0f, 0.5f, 1f},
                new Color[]{Color.WHITE, new Color(255, 218, 110), Color.WHITE}));
            g.drawString(text, x, y);
            g.dispose();
        }
    }

    /** A floating preview card for the main menu that bobs and flips on its own. */
    private static class FloatingCard extends JComponent {
        private final String imageName;
        private final double phase;

        FloatingCard(String imageName, double phase) {
            this.imageName = imageName;
            this.phase = phase;
            setOpaque(false);
            setPreferredSize(new Dimension(100, 130));
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
            int cw = 76, ch = 100;
            g.setColor(new Color(0, 0, 0, (int) (55 - bob * 2)));
            g.fillOval(w / 2 - 28, h - 12, 56, 9);
            g.translate(w / 2.0, h / 2.0 - 6 + bob);
            g.scale(sx, 1.0);
            RoundRectangle2D.Float shape = new RoundRectangle2D.Float(-cw / 2f, -ch / 2f, cw, ch, 18, 18);
            if (face) {
                g.setColor(new Color(235, 243, 255));
                g.fill(shape);
                BufferedImage img = loadCardImage(imageName);
                if (img != null) {
                    Shape old = g.getClip();
                    g.clip(new RoundRectangle2D.Float(-cw / 2f + 5, -ch / 2f + 5, cw - 10, ch - 10, 12, 12));
                    double sc = Math.max((cw - 10.0) / img.getWidth(), (ch - 10.0) / img.getHeight());
                    int dw = (int) (img.getWidth() * sc), dh = (int) (img.getHeight() * sc);
                    g.drawImage(img, -dw / 2, -dh / 2, dw, dh, null);
                    g.setClip(old);
                }
            } else {
                g.setPaint(new GradientPaint(0, -ch / 2f, new Color(98, 128, 190), 0, ch / 2f, new Color(62, 86, 138)));
                g.fill(shape);
                g.setColor(new Color(255, 255, 255, 70));
                g.drawOval(-16, -16, 32, 32);
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 38));
                FontMetrics fm = g.getFontMetrics();
                g.drawString("?", -fm.stringWidth("?") / 2, fm.getAscent() / 2 - 2);
            }
            g.setColor(new Color(255, 255, 255, 170));
            g.setStroke(new BasicStroke(2f));
            g.draw(shape);
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
            g.setColor(new Color(255, 255, 255, 60));
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
            for (int i = 0; i < 160; i++) {
                Particle q = new Particle();
                q.x = rnd.nextDouble() * Math.max(1, getWidth());
                q.y = -20 - rnd.nextDouble() * Math.max(1, getHeight()) * 0.5;
                q.vx = (rnd.nextDouble() - 0.5) * 160;
                q.vy = 80 + rnd.nextDouble() * 220;
                q.gravity = 90;
                q.maxLife = q.life = 2.4 + rnd.nextDouble() * 1.4;
                q.size = 6 + rnd.nextDouble() * 6;
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
            if (particles.isEmpty() && bannerStart < 0 && flashStart < 0) timer.stop();
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
    private final PillLabel levelLabel = new PillLabel(new Color(58, 88, 150, 170));
    private final PillLabel moneyLabel = new PillLabel(new Color(140, 100, 22, 170));
    private final PillLabel timerLabel = new PillLabel(new Color(40, 60, 100, 170));
    private final JLabel statusLabel = new JLabel("Find every matching pair!");
    private final JLabel inventoryLabel = new JLabel();
    private final JLabel levelSelectCoinsLabel = new JLabel();
    private final JLabel mainMenuCoinsLabel = new JLabel();
    private final JButton homeButton = new JButton("← LEVELS");
    private final JButton shopButton = new JButton("🛒 Shop");
    private final JButton magnifyButton = new JButton("🔍 Magnify");
    private final JButton hintButton = new JButton("💡 Hint");
    private final JButton shieldButton = new JButton("🛡 Shield");
    private final JButton timerButton = new JButton("⏱ +1 second");
    private final java.util.List<Card> cards = new ArrayList<>();
    private final Random random = new Random();
    private final javax.swing.Timer clock;
    private AudioClip startClip, winClip, uiClickClip;
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
    private int firstCard = -1;
    private int secondCard = -1;
    private int lockCount = 0;
    private int obstaclesRemaining = 0;
    private boolean obstacleActive = false;
    private Card frozenObstacleCard;
    private javax.swing.Timer obstacleTimer;
    private int lastLevelReward = 30;
    private javax.swing.Timer levelPulseTimer;
    private int magnifiers = 0, hints = 0, shields = 0, timerBoosts = 0;
    private boolean shieldArmed = false;
    private boolean magnifierActive = false;
    private int magnifierSeconds = 0;
    private javax.swing.Timer magnifierTimer;
    private int boardRows;
    private int boardColumns;

    private static class Card {
        String face;
        String imageName;
        String variant;
        final AnimatedCardButton button = new AnimatedCardButton();
        boolean matched = false;
        boolean revealed = false;
        boolean frozen = false;
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
        loadProgress();
        preloadAudio();
        buildWindow();
    }

    private void buildWindow() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 580));
        frame.setSize(1000, 800);
        frame.setLocationRelativeTo(null);
        JPanel gameScreen = new AnimatedBackground(new BorderLayout(12, 12), 18);
        gameScreen.setBorder(new EmptyBorder(16, 18, 16, 18));

        JPanel header = new JPanel(new BorderLayout(10, 7));
        header.setOpaque(false);
        JLabel title = new JLabel("SQUAD FLIP: WHO'S WHO?");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 26));
        JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        stats.setOpaque(false);
        for (JLabel label : new JLabel[]{levelLabel, moneyLabel, timerLabel}) {
            label.setForeground(Color.WHITE);
            label.setFont(new Font("SansSerif", Font.BOLD, 15));
            stats.add(label);
        }
        JPanel headerTop = new JPanel(new BorderLayout(10, 0));
        headerTop.setOpaque(false);
        JPanel leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftHeader.setOpaque(false);
        styleButton(homeButton);
        homeButton.setFont(new Font("SansSerif", Font.BOLD, 11));
        leftHeader.add(homeButton);
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
            styleButton(b);
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

        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof MouseEvent && ((MouseEvent) event).getID() == MouseEvent.MOUSE_PRESSED) {
                Object source = event.getSource();
                if (source instanceof JButton && !(source instanceof AnimatedCardButton)) {
                    if (uiClickClip == null || !uiClickClip.play()) playSound("click");
                }
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
        frame.setGlassPane(effects);
        effects.setVisible(true);

        shopButton.addActionListener(e -> showShop());
        magnifyButton.addActionListener(e -> useMagnifier());
        hintButton.addActionListener(e -> useHint());
        shieldButton.addActionListener(e -> useShield());
        timerButton.addActionListener(e -> useTimerBoost());
        homeButton.addActionListener(e -> returnToLevelSelect());
        startLevelPulse();
        frame.setVisible(true);
    }

    private JPanel createMainMenuScreen() {
        JPanel menu = new AnimatedBackground(new BorderLayout(12, 12), 34);
        menu.setBorder(new EmptyBorder(22, 28, 24, 28));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JLabel brand = new JLabel("SQUAD FLIP: WHO'S WHO?");
        brand.setForeground(Color.WHITE);
        brand.setFont(new Font("SansSerif", Font.BOLD, 19));
        mainMenuCoinsLabel.setForeground(new Color(255, 218, 118));
        mainMenuCoinsLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        top.add(brand, BorderLayout.WEST);
        top.add(mainMenuCoinsLabel, BorderLayout.EAST);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        JLabel clouds = new JLabel("☁     ☁     ☁");
        clouds.setAlignmentX(Component.CENTER_ALIGNMENT);
        clouds.setForeground(new Color(203, 220, 255));
        clouds.setFont(new Font("SansSerif", Font.PLAIN, 38));
        JLabel title = new ShimmerLabel("SQUAD FLIP: WHO'S WHO?");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 42));
        JLabel subtitle = new JLabel("Remember the pairs. Watch out for Freeze Tag.");
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setForeground(new Color(196, 210, 238));
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 17));

        JPanel cardPreview = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 4));
        cardPreview.setOpaque(false);
        cardPreview.setMaximumSize(new Dimension(620, 150));
        double[] phases = {0.0, 1.3, 2.6};
        int phaseIndex = 0;
        for (String imageName : new String[]{null, "blue-face.png", "tandang-nick.jpg"}) {
            cardPreview.add(new FloatingCard(imageName, phases[phaseIndex++]));
        }

        JButton play = new JButton("▶   PLAY GAME");
        JButton shop = new JButton("🛒   POWER-UP SHOP");
        JButton howTo = new JButton("?   HOW TO PLAY");
        JButton exit = new JButton("EXIT");
        for (JButton button : new JButton[]{play, shop, howTo, exit}) {
            styleButton(button);
            button.setAlignmentX(Component.CENTER_ALIGNMENT);
            button.setPreferredSize(new Dimension(280, 46));
            button.setMaximumSize(new Dimension(280, 46));
            button.setFont(new Font("SansSerif", Font.BOLD, 14));
        }
        play.setBackground(new Color(60, 148, 126));
        play.putClientProperty("outline", new Color(133, 226, 196));
        play.addActionListener(event -> {
            refreshLevelSelect();
            animateLevelTiles();
            ((CardLayout) root.getLayout()).show(root, "levels");
        });
        shop.addActionListener(event -> showShop());
        howTo.addActionListener(event -> JOptionPane.showMessageDialog(frame,
            "Choose any unlocked level from the table.\n"
            + "Flip two cards to find a matching pair. Every third wrong guess shuffles face-down cards.\n"
            + "From Level 6, Freeze Tag briefly locks a random card.\n"
            + "Clear a level for 30 coins the first time (5 coins on replay). Stars show your best rating.",
            "How to play", JOptionPane.INFORMATION_MESSAGE));
        exit.addActionListener(event -> frame.dispose());

        center.add(clouds);
        center.add(Box.createVerticalStrut(5));
        center.add(title);
        center.add(Box.createVerticalStrut(7));
        center.add(subtitle);
        center.add(Box.createVerticalStrut(14));
        center.add(cardPreview);
        center.add(Box.createVerticalStrut(14));
        for (JButton button : new JButton[]{play, shop, howTo, exit}) {
            center.add(button);
            center.add(Box.createVerticalStrut(9));
        }

        menu.add(top, BorderLayout.NORTH);
        menu.add(center, BorderLayout.CENTER);
        return menu;
    }

    private JPanel createLevelSelectScreen() {
        JPanel menu = new AnimatedBackground(new BorderLayout(16, 14), 26);
        menu.setBorder(new EmptyBorder(18, 24, 16, 24));

        JPanel heading = new JPanel(new BorderLayout(10, 6));
        heading.setOpaque(false);
        JPanel headingText = new JPanel();
        headingText.setOpaque(false);
        headingText.setLayout(new BoxLayout(headingText, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("SQUAD FLIP: WHO'S WHO?");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 30));
        JLabel subtitle = new JLabel("SELECT A LEVEL  •  CLEARED LEVELS SHOW YOUR STARS");
        subtitle.setForeground(new Color(196, 210, 238));
        subtitle.setFont(new Font("SansSerif", Font.BOLD, 12));
        headingText.add(title);
        headingText.add(Box.createVerticalStrut(4));
        headingText.add(subtitle);
        levelSelectCoinsLabel.setForeground(new Color(255, 218, 118));
        levelSelectCoinsLabel.setFont(new Font("SansSerif", Font.BOLD, 17));
        heading.add(headingText, BorderLayout.WEST);
        heading.add(levelSelectCoinsLabel, BorderLayout.EAST);

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

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        footer.setOpaque(false);
        JButton back = new JButton("← MAIN MENU");
        JButton shop = new JButton("🛒 POWER-UP SHOP");
        JButton howTo = new JButton("? HOW TO PLAY");
        JButton exit = new JButton("EXIT");
        for (JButton button : new JButton[]{back, shop, howTo, exit}) {
            styleButton(button);
            button.setPreferredSize(new Dimension(170, 42));
        }
        back.addActionListener(event -> ((CardLayout) root.getLayout()).show(root, "main"));
        shop.addActionListener(event -> showShop());
        howTo.addActionListener(event -> JOptionPane.showMessageDialog(frame,
            "Choose any unlocked level from the table.\n"
            + "Flip two cards to find a matching pair. Every third wrong guess shuffles face-down cards.\n"
            + "From Level 6, Freeze Tag briefly locks a random card.\n"
            + "Clear a level for 30 coins the first time (5 coins on replay). Stars show your best rating.",
            "How to play", JOptionPane.INFORMATION_MESSAGE));
        exit.addActionListener(event -> frame.dispose());
        footer.add(back);
        footer.add(shop);
        footer.add(howTo);
        footer.add(exit);

        menu.add(heading, BorderLayout.NORTH);
        menu.add(scroller, BorderLayout.CENTER);
        menu.add(footer, BorderLayout.SOUTH);
        refreshLevelSelect();
        return menu;
    }

    private void refreshLevelSelect() {
        levelSelectCoinsLabel.setText("🪙 " + coins + " COINS");
        mainMenuCoinsLabel.setText("🪙 " + coins + " COINS");
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
                tile.setForeground(Color.WHITE);
                tile.setBackground(number == level
                    ? new Color(48, 111, 105) : new Color(54, 73, 112));
                tile.putClientProperty("outline", number == level
                    ? new Color(134, 226, 196) : new Color(104, 132, 184));
                tile.setToolTipText("Play Level " + number + (stars > 0 ? " — best: " + stars + " stars" : ""));
            } else {
                tile.setText("<html><center>🔒<br>LEVEL " + number + "</center></html>");
                tile.setEnabled(false);
                tile.setForeground(new Color(160, 170, 190));
                tile.setBackground(new Color(35, 43, 63));
                tile.putClientProperty("outline", new Color(70, 80, 105));
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
        clearFreezeTag();
        refreshLevelSelect();
        animateLevelTiles();
        ((CardLayout) root.getLayout()).show(root, "levels");
    }

    private void styleButton(JButton b) {
        b.setForeground(Color.WHITE);
        b.setBackground(new Color(62, 82, 125));
        b.setFont(new Font("SansSerif", Font.BOLD, 13));
        installFancy(b);
    }

    private void startLevel() {
        clock.stop();
        deactivateMagnifier();
        clearFreezeTag();
        secondsLeft = 300;
        wrongGuesses = 0;
        matchedPairs = 0;
        comboStreak = 0;
        firstCard = secondCard = -1;
        lockCount = 0;
        obstaclesRemaining = level >= 6
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
        board.setLayout(new GridLayout(boardRows, boardColumns, 8, 8));
        for (int i = 0; i < faces.size(); i++) {
            String[] faceParts = faces.get(i).split("\\|");
            Card card = new Card(faces.get(i), faceParts[0], faceParts[1]);
            card.button.setFont(new Font("Segoe UI Emoji", Font.BOLD, 30));
            card.button.setFocusPainted(false);
            card.button.setForeground(Color.WHITE);
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
            ? "FREEZE TAG CHALLENGE! Cards may lock briefly. " + pairs + " pairs — " + THEME_NAMES[theme] + " theme."
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
        if (card.frozen) {
            statusLabel.setText("❄ Freeze Tag! Try another card for a moment.");
            return;
        }
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
            matchedPairs++;
            firstCard = secondCard = -1;
            lockCount++;
            statusLabel.setText("Match! Nice one!");
            comboStreak++;
            Point pa = centerOf(first), pb = centerOf(card);
            effects.burst(pa, new Color(120, 235, 200), 22);
            effects.burst(pb, new Color(255, 214, 110), 22);
            Point mid = new Point((pa.x + pb.x) / 2, (pa.y + pb.y) / 2 - 28);
            effects.floatText(comboStreak >= 2 ? "COMBO x" + comboStreak + "!" : "MATCH!", mid,
                comboStreak >= 2 ? new Color(255, 214, 110) : new Color(136, 235, 196));
            final int[] completedAnimations = {0};
            Runnable finishOne = () -> {
                completedAnimations[0]++;
                if (completedAnimations[0] == 2) {
                    first.button.setVisible(false);
                    card.button.setVisible(false);
                    lockCount--;
                    if (matchedPairs == cards.size() / 2) levelWon();
                    else maybeTriggerObstacle();
                }
            };
            first.button.playMatch(finishOne);
            card.button.playMatch(finishOne);
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
            javax.swing.Timer pause = new javax.swing.Timer(850, e -> {
                hide(a);
                hide(b);
                firstCard = secondCard = -1;
                lockCount--;
                if (shielded) maybeTriggerObstacle();
                else if (wrongGuesses % 3 == 0) shuffleUnflippedCards();
                else maybeTriggerObstacle();
                if (statusLabel.getText().startsWith("Not a match")) statusLabel.setText("Keep going!");
            });
            pause.setRepeats(false);
            pause.start();
        }
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

        GridLayout originalLayout = board.getLayout() instanceof GridLayout
            ? (GridLayout) board.getLayout() : new GridLayout(boardRows, boardColumns, 8, 8);
        board.setLayout(null);
        long started = System.nanoTime();
        int durationMs = 620;
        javax.swing.Timer animation = new javax.swing.Timer(16, null);
        animation.addActionListener(event -> {
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
                maybeTriggerObstacle();
            }
        });
        animation.start();
    }

    private void maybeTriggerObstacle() {
        if (level < 6 || obstaclesRemaining <= 0 || obstacleActive) return;
        int chance = isChallengeLevel() ? 45 : 28;
        if (random.nextInt(100) < chance) triggerFreezeTag();
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

    private void triggerFreezeTag() {
        java.util.List<Card> hidden = new ArrayList<>();
        for (Card card : cards) {
            if (!card.matched && !card.revealed && !card.frozen && !card.button.isXrayVisible()) hidden.add(card);
        }
        if (hidden.isEmpty()) return;
        Card frozen = hidden.get(random.nextInt(hidden.size()));
        obstaclesRemaining--;
        obstacleActive = true;
        frozenObstacleCard = frozen;
        frozen.frozen = true;
        frozen.button.setFrozenVisible(true);
        playSound("freeze");
        effects.flash(new Color(90, 220, 255, 65));
        statusLabel.setText("❄ FREEZE TAG! One card is locked for 3 seconds—try another.");

        if (obstacleTimer != null) obstacleTimer.stop();
        obstacleTimer = new javax.swing.Timer(3000, event -> clearFreezeTag());
        obstacleTimer.setRepeats(false);
        obstacleTimer.start();
    }

    private void clearFreezeTag() {
        if (obstacleTimer != null) obstacleTimer.stop();
        obstacleTimer = null;
        obstacleActive = false;
        if (frozenObstacleCard != null) {
            frozenObstacleCard.frozen = false;
            frozenObstacleCard.button.setFrozenVisible(false);
            frozenObstacleCard = null;
        }
    }

    private void levelWon() {
        clock.stop();
        clearFreezeTag();
        lastLevelReward = starsByLevel[level] == 0 ? 30 : 5;
        coins += lastLevelReward;
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
        effects.floatText("+" + lastLevelReward + " COINS", boardCenter, new Color(255, 218, 118));
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
        panel.setBackground(new Color(20, 29, 51));
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(109, 157, 204), 2),
            new EmptyBorder(22, 26, 22, 26)));

        JPanel headline = new JPanel();
        headline.setOpaque(false);
        headline.setLayout(new BoxLayout(headline, BoxLayout.Y_AXIS));
        JLabel badge = new JLabel(finalLevel ? "✦  GAME COMPLETE  ✦" : "✦  LEVEL CLEARED  ✦");
        badge.setAlignmentX(Component.CENTER_ALIGNMENT);
        badge.setForeground(new Color(136, 235, 196));
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
            playSound("flip");
            if (shownStars[0] >= earnedStars) ((javax.swing.Timer) e.getSource()).stop();
        });
        starTimer.setInitialDelay(450);
        starTimer.start();
        headline.add(starDisplay);
        headline.add(Box.createVerticalStrut(3));
        headline.add(subtitle);

        JPanel stats = new JPanel(new GridLayout(1, 2, 12, 0));
        stats.setOpaque(false);
        stats.add(makeLevelStat("REWARD", "+" + lastLevelReward, "COINS", new Color(255, 216, 118)));
        stats.add(finalLevel
            ? makeLevelStat("CAMPAIGN", "50", "LEVELS DONE", new Color(142, 199, 255))
            : makeLevelStat("UP NEXT", "LEVEL " + (level + 1), "READY", new Color(142, 199, 255)));

        JButton stay = new JButton("STAY HERE");
        JButton next = new JButton(finalLevel ? "RETURN TO MENU" : "CONTINUE  →");
        styleButton(stay);
        styleButton(next);
        stay.setPreferredSize(new Dimension(150, 44));
        next.setPreferredSize(new Dimension(180, 44));
        next.setBackground(new Color(50, 143, 119));
        next.putClientProperty("outline", new Color(112, 218, 181));
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
        card.setBackground(new Color(36, 49, 77));
        card.setBorder(BorderFactory.createLineBorder(new Color(75, 99, 139), 1));
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
            clearFreezeTag();
            int choice = JOptionPane.showConfirmDialog(frame,
                "Time is up! The level will restart. Your coins and power-ups are safe.",
                "Time up", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE);
            startLevel();
        }
    }

    private void showShop() {
        JDialog dialog = new JDialog(frame, "POWER-UP SHOP", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(850, 570);
        dialog.setResizable(false);

        JPanel content = new JPanel(new BorderLayout(16, 16));
        content.setBackground(new Color(21, 29, 51));
        content.setBorder(new EmptyBorder(22, 24, 20, 24));

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JLabel title = new JLabel("POWER-UP SHOP");
        title.setForeground(Color.WHITE);
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
        for (int i = 0; i < names.length; i++) {
            final int item = i;
            JButton tile = new JButton("<html><div style='text-align:center;width:190px'>"
                + "<font size='+4'>" + icons[i] + "</font><br><br>"
                + "<b><font size='+1'>" + names[i] + "</font></b><br><br>"
                + "<font color='#D4DEF2'>" + details[i] + "</font><br><br>"
                + "<font color='#FFDA76'><b>" + costs[i] + " COINS</b></font></div></html>");
            tile.setFont(new Font("SansSerif", Font.PLAIN, 13));
            tile.setForeground(Color.WHITE);
            tile.setBackground(new Color(43, 56, 88));
            tile.setPreferredSize(new Dimension(300, 185));
            installFancy(tile);
            tile.setBorder(new EmptyBorder(2, 6, 4, 6));
            tile.putClientProperty("outline", new Color(104, 132, 184));
            tile.addActionListener(event -> {
                if (coins < costs[item]) {
                    statusLabel.setText("You need " + costs[item] + " coins for " + names[item] + ".");
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
            tiles.add(tile);
        }

        JButton backsTile = new JButton("<html><div style='text-align:center;width:190px'>"
            + "<font size='+4'>🎨</font><br><br><b><font size='+1'>CARD BACKS</font></b><br><br>"
            + "Unlock new designs and equip your favorite card style.<br><br>OPEN DESIGNS →</div></html>");
        backsTile.setFont(new Font("SansSerif", Font.PLAIN, 13));
        backsTile.setForeground(Color.WHITE);
        backsTile.setBackground(new Color(54, 67, 96));
        backsTile.setPreferredSize(new Dimension(260, 185));
        installFancy(backsTile);
        backsTile.setBorder(new EmptyBorder(2, 6, 4, 6));
        backsTile.putClientProperty("outline", new Color(205, 175, 255));
        backsTile.addActionListener(event -> {
            dialog.dispose();
            showCardBackShop();
        });
        tiles.add(backsTile);

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
        dialog.setVisible(true);
    }

    private void showCardBackShop() {
        JDialog dialog = new JDialog(frame, "CARD BACK DESIGNS", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(620, 500);
        dialog.setResizable(false);

        JPanel content = new JPanel(new BorderLayout(14, 14));
        content.setBackground(new Color(21, 29, 51));
        content.setBorder(new EmptyBorder(20, 22, 18, 22));
        JLabel title = new JLabel("CARD BACK DESIGNS");
        title.setForeground(Color.WHITE);
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
                        JOptionPane.showMessageDialog(dialog,
                            "You need " + CARD_BACK_PRICES[skin] + " coins to unlock this design.",
                            "Not enough coins", JOptionPane.INFORMATION_MESSAGE);
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
        dialog.setVisible(true);
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

    private static File findAssetFile(String assetName) {
        java.util.List<File> locations = new ArrayList<>();
        File working = new File(System.getProperty("user.dir"));
        locations.add(new File(working, "assets/" + assetName));
        locations.add(new File(working, "outputs/assets/" + assetName));
        try {
            File classes = new File(MemoryFogGame.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            if (classes.isFile()) classes = classes.getParentFile();
            locations.add(new File(classes, "assets/" + assetName));
            locations.add(new File(classes, "outputs/assets/" + assetName));
            File parent = classes.getParentFile();
            if (parent != null) {
                locations.add(new File(parent, "assets/" + assetName));
                locations.add(new File(parent, "outputs/assets/" + assetName));
            }
        } catch (Exception ignored) {
            // The working-directory locations still work if class metadata is unavailable.
        }
        for (File file : locations) if (file.isFile()) return file;
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

        AudioClip(String assetName) {
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
                    + "$player=New-Object System.Windows.Media.MediaPlayer; $player.Volume=1.0; "
                    + "$player.add_MediaOpened({$script:ready=$true;if($script:pending){$player.Position=[TimeSpan]::Zero;$player.Play();$script:pending=$false}}); "
                    + "$player.add_MediaFailed({$script:ready=$false}); $player.Open([Uri]::new('" + uri + "')); "
                    + "$frame=New-Object System.Windows.Threading.DispatcherFrame; $timer=New-Object System.Windows.Threading.DispatcherTimer; "
                    + "$timer.Interval=[TimeSpan]::FromMilliseconds(25); $timer.add_Tick({ "
                    + "try{$v=[IO.File]::ReadAllText('" + requestPath + "').Trim();if($v -ne $script:last){$script:last=$v;$script:pending=$true;if($script:ready){$player.Stop();$player.Position=[TimeSpan]::Zero;$player.Play();$script:pending=$false}}}catch{}; "
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
                String request = Integer.toString(++requestId);
                java.nio.file.Files.write(requestFile.toPath(), request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    /** Loads the bundled MP3 files once during startup so they play with no delay. */
    private void preloadAudio() {
        startClip = new AudioClip("game-start.mp3");
        winClip = new AudioClip("universfield-game-bonus-144751.mp3");
        uiClickClip = new AudioClip("universfield-bubble-pop-05-323639.mp3");
    }

    /** Plays the level-start clip as soon as a level is selected. */
    private void requestLevelStartAudio() {
        if (startClip == null || !startClip.play()) playSound("start");
    }

    /** Plays the bonus jingle when a level is won, just before the next screen appears. */
    private void playWinSound() {
        if (winClip == null || !winClip.play()) playSound("win");
    }

    /** Generates short, distinct sound cues with Java's built-in audio support. */
    private void playSound(String kind) {
        Thread soundThread = new Thread(() -> {
            final float sampleRate = 22050f;
            AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
            double[][] notes;
            switch (kind) {
                case "wrong": notes = new double[][]{{290, 100}, {205, 155}}; break;
                case "match": notes = new double[][]{{523, 85}, {659, 85}, {784, 170}}; break;
                case "shuffle": notes = new double[][]{{260, 60}, {360, 65}, {480, 75}, {330, 95}}; break;
                case "freeze": notes = new double[][]{{880, 65}, {660, 90}, {440, 140}}; break;
                case "start": notes = new double[][]{{523, 90}, {659, 90}, {784, 160}}; break;
                case "win": notes = new double[][]{{523, 90}, {659, 90}, {784, 90}, {1047, 260}}; break;
                case "click": notes = new double[][]{{880, 45}, {1175, 55}}; break;
                default: notes = new double[][]{{740, 55}}; break;
            }

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
                            * envelope * 0.22;
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
        if (!SAVE_FILE.isFile()) return;
        Properties saved = new Properties();
        try (FileInputStream input = new FileInputStream(SAVE_FILE)) {
            saved.load(input);
            coins = Math.max(0, Integer.parseInt(saved.getProperty("coins", "0")));
            // Level access is session-only: each fresh launch starts with Level 1 unlocked.
            highestUnlockedLevel = 1;
            level = 1;
            gameCompleted = false;
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
            level = 1;
            highestUnlockedLevel = 1;
            gameCompleted = false;
        }
    }

    private void saveProgress() {
        Properties saved = new Properties();
        saved.setProperty("coins", String.valueOf(coins));
        saved.setProperty("magnifiers", String.valueOf(magnifiers));
        saved.setProperty("hints", String.valueOf(hints));
        saved.setProperty("shields", String.valueOf(shields));
        saved.setProperty("shieldArmed", String.valueOf(shieldArmed));
        saved.setProperty("timerBoosts", String.valueOf(timerBoosts));
        saved.setProperty("selectedCardBack", String.valueOf(selectedCardBack));
        saved.setProperty("unlockedCardBacks", joinBooleans(unlockedCardBacks));
        saved.setProperty("stars", joinInts(starsByLevel));
        try (FileOutputStream output = new FileOutputStream(SAVE_FILE)) {
            saved.store(output, "Squad Flip saved progress");
        } catch (Exception ex) {
            System.err.println("Could not save Squad Flip progress: " + ex.getMessage());
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
            ? (secondsLeft % 2 == 0 ? new Color(190, 45, 70, 230) : new Color(110, 30, 50, 200))
            : new Color(40, 60, 100, 170));
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
