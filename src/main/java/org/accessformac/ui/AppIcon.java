package org.accessformac.ui;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** Original interwoven Lattice mark, drawn as vectors at every macOS icon resolution. */
public final class AppIcon {
    public static void main(String[] args) throws Exception {
        Path iconset = Path.of(args[0]); Files.createDirectories(iconset);
        for (int points : new int[]{16, 32, 128, 256, 512}) for (int scale : new int[]{1, 2}) {
            ImageIO.write(render(points * scale), "png", iconset.resolve("icon_" + points + "x" + points + (scale == 2 ? "@2x" : "") + ".png").toFile());
        }
    }

    static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.scale(size / 1024.0, size / 1024.0);
        g.setColor(new Color(41, 25, 31, 18)); g.fillRoundRect(86, 106, 852, 850, 190, 190);
        g.setPaint(new GradientPaint(160, 100, new Color(253, 249, 250), 820, 900, new Color(233, 225, 231)));
        g.fillRoundRect(86, 82, 852, 852, 190, 190);
        g.setColor(new Color(255, 255, 255, 205)); g.setStroke(new BasicStroke(3));
        g.drawRoundRect(88, 84, 848, 848, 188, 188);
        g.translate(512, 506); g.rotate(Math.PI / 4);
        // Four substantial ribbons form a small open diamond: clear even at16px.
        g.setStroke(new BasicStroke(92, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(new GradientPaint(-280, -300, new Color(111, 30, 48), 280, 300, new Color(155, 57, 78)));
        for(int x : new int[]{-128, 128}) g.drawLine(x, -276, x, 276);
        g.setPaint(new GradientPaint(-290, -240, new Color(186, 92, 108), 290, 240, new Color(139, 43, 62)));
        for(int y : new int[]{-128, 128}) g.drawLine(-276, y, 276, y);
        // Alternate overpasses make the crossing read as connected strands rather than a grid.
        for(int coordinate : new int[]{-128, 128}) {
            g.setStroke(new BasicStroke(108, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(52, 15, 28, 35)); g.drawLine(coordinate, coordinate-54, coordinate, coordinate+54);
            g.setStroke(new BasicStroke(92, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.setPaint(new GradientPaint(coordinate-46, coordinate-54, new Color(149, 49, 69), coordinate+46, coordinate+54, new Color(114, 30, 48)));
            g.drawLine(coordinate, coordinate-55, coordinate, coordinate+55);
        }
        g.dispose();
        return image;
    }
}
