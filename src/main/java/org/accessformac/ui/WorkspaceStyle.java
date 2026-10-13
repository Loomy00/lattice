package org.accessformac.ui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.*;

/** Independent vector controls and neutral macOS-oriented workspace styling. */
final class WorkspaceStyle {
    static final Color ACCENT = new Color(145, 47, 59);
    static final Color CHROME = new Color(246, 247, 249);
    static final Color SIDEBAR = new Color(242, 243, 246);
    static final Color LINE = new Color(220, 223, 228);
    static final Color INK = new Color(40, 43, 49);
    static final Color MUTED = new Color(100, 105, 115);
    private static final Color ACCENT_HOVER = new Color(132, 39, 51);
    private static final Color ACCENT_PRESSED = new Color(112, 31, 42);
    private static final Color DISABLED_TEXT = new Color(137, 141, 149);
    private WorkspaceStyle() {}

    static Color selection() { Color color = UIManager.getColor("Table.selectionBackground"); return color == null ? new Color(0, 105, 210) : color; }
    static Color selectionText() { Color color = UIManager.getColor("Table.selectionForeground"); return color == null ? Color.WHITE : color; }

    static JButton command(String label, String symbol, boolean primary) {
        JButton button = new JButton(label, new Glyph(symbol, 16, primary ? Color.WHITE : MUTED)) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean pressed = getModel().isArmed() && getModel().isPressed();
                if (primary) {
                    g.setColor(!isEnabled() ? new Color(231, 233, 237) : pressed ? ACCENT_PRESSED : getModel().isRollover() ? ACCENT_HOVER : ACCENT);
                    g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 9, 9);
                } else if (isEnabled() && (getModel().isRollover() || pressed)) {
                    g.setColor(pressed ? new Color(213, 217, 225) : new Color(232, 235, 240));
                    g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 9, 9);
                }
                if (isFocusOwner() && isEnabled()) {
                    // Separate the ring from the accent fill; keep it visible at small control sizes.
                    g.setColor(selection()); g.setStroke(new BasicStroke(2));
                    g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 11, 11);
                }
                g.dispose(); super.paintComponent(graphics);
            }
        };
        button.setUI(new BasicButtonUI() {
            @Override protected void paintText(Graphics graphics, AbstractButton control, Rectangle rectangle, String text) {
                if (control.isEnabled()) { super.paintText(graphics, control, rectangle, text); return; }
                // BasicButtonUI's default embossed disabled text is faint against light chrome.
                graphics.setColor(DISABLED_TEXT);
                javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(graphics, text,
                    control.getDisplayedMnemonicIndex(), rectangle.x, rectangle.y + graphics.getFontMetrics().getAscent());
            }
        }); button.setContentAreaFilled(false); button.setBorderPainted(false);
        button.setOpaque(false); button.setFocusable(true); button.setRolloverEnabled(true);
        button.setForeground(primary ? Color.WHITE : INK); button.setFont(UIManager.getFont("Button.font").deriveFont(12f));
        button.setMargin(new Insets(7, 7, 7, 7)); button.setIconTextGap(6);
        button.setToolTipText(label); button.getAccessibleContext().setAccessibleName(label);
        return button;
    }

    static void tabs(JTabbedPane pane) {
        pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        pane.setBackground(CHROME); pane.setForeground(INK); pane.setFont(UIManager.getFont("Label.font").deriveFont(12f));
        pane.setUI(new BasicTabbedPaneUI() {
            @Override protected void installDefaults() {
                super.installDefaults(); tabAreaInsets = new Insets(3, 8, 0, 8); tabInsets = new Insets(8, 13, 8, 13);
                contentBorderInsets = new Insets(1, 0, 0, 0); selectedTabPadInsets = new Insets(0, 0, 0, 0);
            }
            @Override protected void paintTabBackground(Graphics g, int placement, int index, int x, int y, int width, int height, boolean selected) {
                g.setColor(selected ? Color.WHITE : CHROME); g.fillRect(x, y, width, height);
                if (selected) { g.setColor(ACCENT); g.fillRect(x + 10, y + height - 2, width - 20, 2); }
            }
            @Override protected void paintTabBorder(Graphics g, int placement, int index, int x, int y, int width, int height, boolean selected) {}
            @Override protected void paintContentBorder(Graphics g, int placement, int selected) {
                g.setColor(LINE); g.drawLine(0, calculateTabAreaHeight(placement, runCount, maxTabHeight), tabPane.getWidth(), calculateTabAreaHeight(placement, runCount, maxTabHeight));
            }
        });
    }

    static void designGrid(JTable grid) {
        grid.setRowHeight(30); grid.setFont(UIManager.getFont("Table.font").deriveFont(13f));
        grid.setGridColor(LINE); grid.setShowVerticalLines(false); grid.setShowHorizontalLines(true);
        grid.setSelectionBackground(selection()); grid.setSelectionForeground(selectionText());
        grid.setFillsViewportHeight(true); grid.setBackground(Color.WHITE);
        grid.getTableHeader().setReorderingAllowed(false);
        grid.getTableHeader().setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 12f));
        grid.getTableHeader().setPreferredSize(new Dimension(100, 32));
        grid.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    }

    static JComponent section(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout()); panel.setBackground(Color.WHITE);
        JLabel label = new JLabel(title); label.setForeground(MUTED); label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setBorder(new EmptyBorder(9, 12, 9, 12)); panel.add(label, BorderLayout.NORTH); panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    static void dismissOnEscape(JDialog dialog) {
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    static final class Glyph implements Icon {
        private final String kind; private final int size; private final Color color;
        Glyph(String kind, int size, Color color) { this.kind = kind; this.size = size; this.color = color; }
        public int getIconWidth() { return size; }
        public int getIconHeight() { return size; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create(); g.translate(x, y); g.scale(size / 20.0, size / 20.0);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(c.isEnabled() ? color : DISABLED_TEXT);
            g.setStroke(new BasicStroke(1.45f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case "run" -> g.fill(new Path2D.Double() {{ moveTo(6, 3); lineTo(16, 10); lineTo(6, 17); closePath(); }});
                case "open" -> { g.drawRoundRect(2, 6, 16, 11, 2, 2); g.drawLine(3, 6, 3, 3); g.drawLine(3, 3, 8, 3); g.drawLine(8, 3, 11, 6); }
                case "export" -> { g.drawLine(10, 2, 10, 12); g.drawLine(6, 8, 10, 12); g.drawLine(14, 8, 10, 12); g.drawLine(3, 13, 3, 17); g.drawLine(3, 17, 17, 17); g.drawLine(17, 13, 17, 17); }
                case "refresh" -> { g.draw(new Arc2D.Double(3, 3, 14, 14, 35, 280, Arc2D.OPEN)); g.drawLine(16, 2, 17, 7); g.drawLine(12, 7, 17, 7); }
                case "save" -> { g.drawRoundRect(3, 2, 14, 16, 2, 2); g.drawRect(6, 2, 8, 5); g.drawRect(6, 11, 8, 7); }
                case "database" -> { g.drawOval(3, 2, 14, 5); g.drawLine(3, 4, 3, 15); g.drawLine(17, 4, 17, 15); g.draw(new Arc2D.Double(3, 12, 14, 6, 180, 180, Arc2D.OPEN)); g.draw(new Arc2D.Double(3, 7, 14, 5, 180, 180, Arc2D.OPEN)); }
                case "delete" -> { g.drawLine(3, 5, 17, 5); g.drawLine(7, 2, 13, 2); g.drawLine(5, 5, 6, 17); g.drawLine(6, 17, 14, 17); g.drawLine(14, 17, 15, 5); g.drawLine(8, 8, 8, 14); g.drawLine(12, 8, 12, 14); }
                case "design", "builder" -> { g.drawRoundRect(2, 3, 12, 14, 2, 2); g.drawLine(2, 8, 14, 8); g.drawLine(7, 3, 7, 17); g.drawLine(10, 14, 17, 7); g.drawLine(11, 16, 18, 9); g.drawLine(17, 7, 18, 9); g.drawLine(10, 14, 11, 16); }
                case "field" -> { g.drawRoundRect(2, 4, 16, 12, 2, 2); g.drawLine(7, 4, 7, 16); g.drawLine(13, 7, 13, 13); g.drawLine(10, 10, 16, 10); }
                case "null" -> { g.drawOval(3, 3, 14, 14); g.drawLine(5, 15, 15, 5); }
                case "catalog" -> { g.drawRoundRect(2, 2, 16, 16, 2, 2); for (int row : new int[]{6, 10, 14}) { g.fillOval(5, row - 1, 2, 2); g.drawLine(10, row, 15, row); } }
                case "form" -> { g.drawRoundRect(2, 3, 16, 14, 2, 2); g.drawLine(5, 7, 7, 7); g.drawRect(10, 6, 5, 3); g.drawLine(5, 13, 7, 13); g.drawRect(10, 12, 5, 3); }
                case "report" -> { g.drawRoundRect(3, 2, 14, 16, 2, 2); g.drawLine(6, 6, 14, 6); g.drawRect(6, 9, 3, 6); g.drawRect(11, 12, 3, 3); }
                case "module", "macro" -> { g.drawLine(7, 5, 3, 10); g.drawLine(3, 10, 7, 15); g.drawLine(13, 5, 17, 10); g.drawLine(17, 10, 13, 15); g.drawLine(11, 3, 9, 17); }
                case "close" -> { g.drawLine(5, 5, 15, 15); g.drawLine(5, 15, 15, 5); }
                case "add", "sample" -> { g.drawRoundRect(3, 3, 14, 14, 3, 3); g.drawLine(10, 6, 10, 14); g.drawLine(6, 10, 14, 10); }
                case "sidebar" -> { g.drawRoundRect(2, 3, 16, 14, 2, 2); g.drawLine(7, 3, 7, 17); }
                case "info" -> { g.drawOval(2, 2, 16, 16); g.drawLine(10, 9, 10, 14); g.fillOval(9, 5, 2, 2); }
                case "query" -> { g.drawRoundRect(3, 2, 14, 16, 2, 2); g.drawLine(6, 6, 14, 6); g.drawLine(6, 10, 12, 10); g.drawLine(6, 14, 14, 14); }
                case "relationships" -> { g.drawRoundRect(1, 3, 6, 6, 1, 1); g.drawRoundRect(13, 11, 6, 6, 1, 1); g.drawLine(7, 6, 10, 6); g.drawLine(10, 6, 10, 14); g.drawLine(10, 14, 13, 14); }
                default -> { g.drawRoundRect(2, 3, 16, 14, 2, 2); g.drawLine(2, 8, 18, 8); g.drawLine(2, 12, 18, 12); g.drawLine(8, 3, 8, 17); }
            }
            g.dispose();
        }
    }
}
