import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * Draws the launcher icon: a dark badge with a green "revive" arrow circling a sun and cloud.
 * Usage: java tools/IconGen.java res   (writes res/drawable-{m,h,xh,xxh}dpi/ic_launcher.png)
 */
public class IconGen {
    private static final Color GREEN = new Color(0x8C, 0xC6, 0x3F);

    public static void main(String[] args) throws Exception {
        String res = args.length > 0 ? args[0] : "res";
        String[] dirs = {"drawable-mdpi", "drawable-hdpi", "drawable-xhdpi", "drawable-xxhdpi"};
        int[] sizes = {48, 72, 96, 144};
        for (int i = 0; i < dirs.length; i++) {
            File dir = new File(res, dirs[i]);
            dir.mkdirs();
            ImageIO.write(draw(sizes[i]), "png", new File(dir, "ic_launcher.png"));
        }
        ImageIO.write(draw(512), "png", new File(res, "../tools/ic_launcher_512.png"));
    }

    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 100.0, size / 100.0);  // draw in a 100x100 space

        // Soft drop shadow, then the badge with a top-lit gradient and a fine rim.
        for (int i = 4; i > 0; i--) {
            g.setColor(new Color(0, 0, 0, 14));
            g.fill(new Ellipse2D.Double(6 - i * 0.5, 7 - i * 0.2 + 1.5, 88 + i, 88 + i));
        }
        Ellipse2D badge = new Ellipse2D.Double(6, 5, 88, 88);
        g.setPaint(new GradientPaint(0, 5, new Color(0x4A, 0x50, 0x57), 0, 93, new Color(0x1C, 0x1F, 0x23)));
        g.fill(badge);
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(new Color(255, 255, 255, 40));
        g.draw(new Ellipse2D.Double(6.6, 5.6, 86.8, 86.8));

        // Revive arrow: an open ring ending in an arrowhead (clockwise, gap at the top right).
        double cx = 50, cy = 49, r = 32;
        g.setColor(GREEN);
        g.setStroke(new BasicStroke(6.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double start = 70, extent = 290;  // degrees, counter-clockwise from 3 o'clock in Java2D
        g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, start, extent, Arc2D.OPEN));
        double endRad = Math.toRadians(start + extent);
        double ex = cx + r * Math.cos(endRad), ey = cy - r * Math.sin(endRad);
        // Tangent direction at the arc end (counter-clockwise travel in screen space).
        double tx = -Math.sin(endRad), ty = -Math.cos(endRad);
        double nx = Math.cos(endRad), ny = -Math.sin(endRad);
        Path2D head = new Path2D.Double();
        head.moveTo(ex + tx * 9, ey + ty * 9);
        head.lineTo(ex + nx * 8 - tx * 2, ey + ny * 8 - ty * 2);
        head.lineTo(ex - nx * 8 - tx * 2, ey - ny * 8 - ty * 2);
        head.closePath();
        g.fill(head);

        // Sun peeking out behind the cloud.
        g.setPaint(new GradientPaint(0, 30, new Color(0xFF, 0xD5, 0x4A), 0, 54, new Color(0xFF, 0xA8, 0x00)));
        g.fill(new Ellipse2D.Double(48, 31, 21, 21));

        // Cloud: overlapping circles on a rounded base, with a faint underside shade.
        Area cloud = new Area(new RoundRectangle2D.Double(30, 50, 42, 15, 15, 15));
        cloud.add(new Area(new Ellipse2D.Double(33, 43, 17, 17)));
        cloud.add(new Area(new Ellipse2D.Double(41, 36, 22, 22)));
        cloud.add(new Area(new Ellipse2D.Double(56, 45, 16, 16)));
        AffineTransform shadow = AffineTransform.getTranslateInstance(0, 1.6);
        g.setColor(new Color(0, 0, 0, 60));
        g.fill(cloud.createTransformedArea(shadow));
        g.setPaint(new GradientPaint(0, 36, Color.WHITE, 0, 65, new Color(0xDD, 0xE3, 0xE8)));
        g.fill(cloud);

        g.dispose();
        return img;
    }
}
