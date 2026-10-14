package org.accessformac.ui;

import org.accessformac.db.AccessDatabase;
import org.accessformac.db.AccessDatabase.ColumnInfo;
import org.accessformac.db.AccessDatabase.QueryResult;
import org.accessformac.db.AccessDatabase.SavedQuery;
import org.accessformac.db.AccessDatabase.FieldInfo;
import org.accessformac.db.AccessDatabase.ParameterInfo;
import org.accessformac.db.AccessDatabase.RelationshipInfo;
import org.accessformac.objects.AccessObjectCatalog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Independent Access-compatible desktop workspace. Engine operations are serialized off the EDT. */
public final class AccessForMacApp extends JFrame {
    private static final Color MAROON = WorkspaceStyle.ACCENT;
    private static final Color PAPER = WorkspaceStyle.CHROME;
    private static final Color LINE = WorkspaceStyle.LINE;
    private static final Color INK = WorkspaceStyle.INK;
    private static final Color MUTED = WorkspaceStyle.MUTED;
    private final ExecutorService engine = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "access-database");
        t.setDaemon(true);
        return t;
    });
    // Only the engine thread reads or writes this field.
    private AccessDatabase database;
    private Path originalPath, workingPath, recoveryBackupPath;
    private boolean databaseDirect;
    private final JTabbedPane objects = new JTabbedPane();
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("All Access Objects");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree navigation = new JTree(treeModel);
    private final JLabel status = new JLabel("Ready — open a database or create the practice sample.") {
        @Override public void setText(String text) { super.setText(text); setToolTipText(text); }
    };
    private final JLabel paths = new JLabel("No database open");
    private final JLabel documentTitle = new JLabel("Lattice");
    private final JLabel documentSubtitle = new JLabel("Access databases · local SQL");
    private final JLabel documentBadge = new JLabel("No database");
    private final JTextField objectSearch = new JTextField();
    private final JProgressBar progress = new JProgressBar();
    private final List<AbstractButton> databaseCommands = new ArrayList<>();
    private final List<AbstractButton> idleCommands = new ArrayList<>();
    private final Map<String, JComponent> openObjects = new LinkedHashMap<>();
    private final Deque<Path> pendingOpens = new ArrayDeque<>();
    private AccessObjectCatalog.Snapshot nativeCatalog;
    private Catalog currentCatalog;
    private JSplitPane workspace;
    private int jobs, queryNumber, sidebarWidth = 225;
    private boolean rebuildingNavigation;
    private NavObject rememberedSelection;
    private final Set<String> collapsedNavigationGroups = new HashSet<>();
    private boolean closing, pendingQuit, databaseReadOnly, sidebarVisible = true;

    private record NavObject(String name, String sql, boolean table, AccessObjectCatalog.NativeObject nativeObject) {
        NavObject(String name, String sql, boolean table) { this(name, sql, table, null); }
        @Override public String toString() { return name; }
    }
    private record Catalog(List<String> tables, List<SavedQuery> queries) {}
    private record Opened(Path original, Path working, Path recoveryBackup, Catalog catalog, boolean direct, boolean readOnly, List<String> limitations) {}
    private record TableData(List<ColumnInfo> schema, List<FieldInfo> fields, QueryResult records) {}

    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("Lattice: java -jar build/access-for-mac.jar [database.accdb|database.mdb|--sample|--verify-install]");
            return;
        }
        if (args.length > 0 && args[0].equals("--verify-install")) {
            try { verifyInstallation(); }
            catch (Throwable failure) { System.err.println("Lattice installation verification FAILED: " + failure); failure.printStackTrace(System.err); System.exit(2); }
            return;
        }
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Lattice");
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { /* Swing's default remains usable. */ }
            AccessForMacApp app = new AccessForMacApp();
            app.setVisible(true);
            app.installDesktopHandlers();
            if (args.length > 0) {
                if (args[0].equals("--sample")) app.createSample();
                else app.requestOpen(Path.of(args[0]));
            }
        });
    }

    public AccessForMacApp() {
        super("Lattice — Database Workspace");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(960, 660));
        setSize(1240, 820);
        setLocationRelativeTo(null);
        WorkspaceStyle.tabs(objects);
        setJMenuBar(menu());
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(PAPER);
        JPanel header = new JPanel(new BorderLayout());
        header.add(titleStrip(), BorderLayout.NORTH);
        header.add(ribbon(), BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);
        workspace = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigationPane(), objects);
        workspace.setDividerLocation(225);
        workspace.setDividerSize(4);
        workspace.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LINE));
        root.add(workspace, BorderLayout.CENTER);
        root.add(statusBar(), BorderLayout.SOUTH);
        setContentPane(root);
        showWelcome();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
        });
        KeyStroke run = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(run, "runSQL");
        getRootPane().getActionMap().put("runSQL", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { runQuery(); }
        });
        objects.addChangeListener(e -> updateCommands());
        updateCommands();
    }

    private JComponent titleStrip() {
        JPanel p = new JPanel(new BorderLayout(18, 0)); p.setBackground(PAPER);
        p.setBorder(new EmptyBorder(10, 14, 8, 14));
        JPanel identity = new JPanel(new BorderLayout(10, 0)); identity.setOpaque(false);
        JLabel icon = new JLabel(new ImageIcon(AppIcon.render(32)));
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 2)); titles.setOpaque(false);
        documentTitle.setForeground(INK); documentTitle.setFont(documentTitle.getFont().deriveFont(Font.BOLD, 14f));
        documentSubtitle.setForeground(MUTED); documentSubtitle.setFont(documentSubtitle.getFont().deriveFont(11f));
        titles.add(documentTitle); titles.add(documentSubtitle); identity.add(icon, BorderLayout.WEST); identity.add(titles, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); actions.setOpaque(false);
        documentBadge.setOpaque(true); documentBadge.setBackground(new Color(232, 235, 239)); documentBadge.setForeground(MUTED);
        documentBadge.setFont(documentBadge.getFont().deriveFont(11f)); documentBadge.setBorder(new EmptyBorder(5, 9, 5, 9));
        actions.add(documentBadge); actions.add(button("Sidebar", this::toggleSidebar, false)); actions.add(button("Database Info", this::databaseInfo, true));
        p.add(identity, BorderLayout.CENTER); p.add(actions, BorderLayout.EAST);
        return p;
    }

    private static void verifyInstallation() throws Exception {
        org.accessformac.install.RuntimeVerification.verify();
        Path scratch = Files.createTempDirectory("lattice-install-");
        try {
            Path sample = scratch.resolve("sample.accdb"), working = scratch.resolve("working.accdb");
            AccessDatabase.createSample(sample);
            byte[] original = Files.readAllBytes(sample);
            try (AccessDatabase db = AccessDatabase.openCopy(sample, working)) {
                if (db.execute("SELECT * FROM Customers").rows().size() != 3) throw new IllegalStateException("Sample query result is incorrect.");
                if (db.updateRecord("Customers", Map.of("CustomerID", 1), "City", "Installation verified") != 1) throw new IllegalStateException("Native write failed.");
                db.flush();
            }
            try (AccessDatabase reopened = AccessDatabase.openCopy(working, scratch.resolve("reopened.accdb"))) {
                if (!reopened.execute("SELECT City FROM Customers WHERE CustomerID=1").rows().get(0).get(0).equals("Installation verified")) throw new IllegalStateException("Native reopening failed.");
            }
            if (!Arrays.equals(original, Files.readAllBytes(sample))) throw new IllegalStateException("Source file changed.");
            System.out.println("Lattice installation verification PASS — database libraries, ACCDB creation, SQL, prepared write, native reopen and source preservation.");
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private void toggleSidebar() {
        if (workspace == null) return;
        if (sidebarVisible) sidebarWidth = Math.max(180, workspace.getDividerLocation());
        sidebarVisible = !sidebarVisible;
        workspace.getLeftComponent().setVisible(sidebarVisible);
        workspace.setDividerSize(sidebarVisible ? 4 : 0);
        workspace.setDividerLocation(sidebarVisible ? sidebarWidth : 0);
    }

    private void databaseInfo() {
        if (workingPath == null) return;
        JTextArea text = inspectionText("Native Access database\n\n" + (databaseDirect ? "File:\n" : "Working copy:\n") + workingPath
                + (databaseDirect ? "" : "\n\nOriginal source (separate):\n" + originalPath)
                + "\n\nRecovery backup:\n" + recoveryBackupPath
                + "\n\nMode: " + (databaseReadOnly ? "Read only" : databaseDirect ? "Editing file — changes save here" : "Working copy — changes save to the copy")
                + "\n\nExperimental native query saves create an additional pre-query backup.\nUnsupported application objects are preserved; catalog inspection does not execute them.");
        text.setColumns(65); text.setRows(16);
        JOptionPane.showMessageDialog(this, new JScrollPane(text), "Database Info", JOptionPane.INFORMATION_MESSAGE);
    }

    private void tableAction(String action) {
        if (!ready()) return;
        if (!(objects.getSelectedComponent() instanceof TablePanel table)) { status.setText("Open a table datasheet first."); return; }
        switch (action) {
            case "insert" -> table.insertRecord();
            case "delete" -> table.deleteRecord();
            case "null" -> table.setSelectedNull();
            case "addField" -> table.addField();
            default -> throw new IllegalArgumentException("Unknown table action.");
        }
    }

    private JComponent ribbon() {
        Map<String, JComponent> pages = new LinkedHashMap<>();
        pages.put("File", ribbonPage(
            group("Database", button("Open Database…", this::chooseOpen, false), button("Open a Copy…", this::chooseOpenCopy, false), button("New Database…", this::createBlank, false), button("Practice Sample…", this::createSample, false)),
            group("Save", button("Export Database Copy…", this::exportCopy, true))));
        pages.put("Home", ribbonPage(
            group("Views", button("Datasheet", () -> tableView(false), true), button("Table Design", () -> tableView(true), true)),
            group("Query", button("New SQL Query", this::newQuery, true), button("Run SQL", this::runQuery, true)),
            group("Objects", button("Refresh", this::refresh, true), button("Close Object", this::closeObject, true))));
        pages.put("Create", ribbonPage(
            group("Tables", button("Table Design…", this::createTable, true)),
            group("Queries", button("Query Builder…", this::queryDesign, true), button("New SQL Query", this::newQuery, true), button("Open SQL File…", this::openSqlFile, true)),
            unavailable("Forms / Reports", "Design and execution unavailable")));
        pages.put("External Data", ribbonPage(
            group("Native Access file", button("Export Database Copy…", this::exportCopy, true)),
            unavailable("Import / Linked tables", "Unavailable in this milestone")));
        pages.put("Database Tools", ribbonPage(
            group("Inspect", button("Table Design", () -> tableView(true), true), button("Relationships", this::relationships, true), button("Object Catalog", this::nativeObjects, true)),
            unavailable("Macros / VBA", "Execution unavailable")));
        pages.put("Query", ribbonPage(
            group("Query", button("Run SQL", this::runQuery, true), button("Save SQL File…", this::saveSqlFile, true)),
            group("Design", button("Query Builder…", this::queryDesign, true), button("New SQL Query", this::newQuery, true)),
            group("Experimental", button("Save Native Query…", this::saveNativeQuery, true))));
        JPanel shell = new JPanel(new BorderLayout()); shell.setBackground(PAPER);
        JPanel categories = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0)); categories.setOpaque(false); categories.setBorder(new EmptyBorder(0, 12, 0, 12));
        CardLayout cards = new CardLayout(); JPanel content = new JPanel(cards); content.setOpaque(false); ButtonGroup group = new ButtonGroup();
        for (Map.Entry<String, JComponent> page : pages.entrySet()) {
            String label = page.getKey(); content.add(page.getValue(), label);
            JToggleButton category = new JToggleButton(label) {
                @Override protected void paintComponent(Graphics graphics) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    if (getModel().isRollover() || getModel().isPressed()) { g.setColor(new Color(232, 235, 240)); g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 8, 8); }
                    if (isFocusOwner()) { g.setColor(WorkspaceStyle.selection()); g.setStroke(new BasicStroke(2)); g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 8, 8); }
                    g.dispose(); super.paintComponent(graphics);
                    if (isSelected()) { graphics.setColor(MAROON); graphics.fillRect(9, getHeight() - 2, getWidth() - 18, 2); }
                }
            };
            category.setUI(new javax.swing.plaf.basic.BasicToggleButtonUI()); category.setContentAreaFilled(false); category.setBorderPainted(false);
            category.setFocusable(true); category.setRolloverEnabled(true); category.setMargin(new Insets(7, 11, 8, 11)); category.setFont(category.getFont().deriveFont(12f)); category.setForeground(INK);
            category.getAccessibleContext().setAccessibleName(label + " ribbon commands"); category.setToolTipText(label + " commands");
            category.addItemListener(e -> category.setFont(category.getFont().deriveFont(category.isSelected() ? Font.BOLD : Font.PLAIN)));
            category.addActionListener(e -> cards.show(content, label)); group.add(category); categories.add(category);
            if (label.equals("Home")) category.setSelected(true);
        }
        cards.show(content, "Home"); shell.add(categories, BorderLayout.NORTH); shell.add(content, BorderLayout.CENTER);
        return shell;
    }

    private JComponent ribbonPage(JComponent... groups) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setBackground(PAPER);
        p.setBorder(new EmptyBorder(7, 10, 7, 10));
        for (JComponent group : groups) if (group != null) p.add(group);
        if (p.getComponentCount() > 0 && p.getComponent(p.getComponentCount() - 1) instanceof JComponent last) last.setBorder(new EmptyBorder(0, 6, 0, 6));
        // Preserve every command at narrow widths instead of silently clipping the row.
        JScrollPane viewport = new JScrollPane(p, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        viewport.setBorder(null); viewport.setOpaque(false); viewport.getViewport().setOpaque(false);
        viewport.getHorizontalScrollBar().setUnitIncrement(40); viewport.setPreferredSize(new Dimension(800, 76));
        return viewport;
    }

    private JComponent group(String label, JButton... buttons) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, LINE), new EmptyBorder(0, 6, 0, 6)));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 1));
        row.setOpaque(false);
        for (JButton b : buttons) row.add(b);
        p.add(row, BorderLayout.CENTER);
        JLabel caption = new JLabel(label, SwingConstants.CENTER);
        caption.setForeground(MUTED);
        caption.setFont(caption.getFont().deriveFont(11f));
        caption.setBorder(new EmptyBorder(3, 0, 0, 0));
        p.add(caption, BorderLayout.SOUTH);
        return p;
    }

    private JComponent unavailable(String label, String reason) {
        // Capability details stay in About, Database Info and the object inspector.
        return null;
    }

    private JButton button(String text, Runnable action, boolean needsDatabase) {
        String lower = text.toLowerCase(Locale.ROOT);
        String icon = lower.contains("sidebar") ? "sidebar" : lower.contains("info") || lower.contains("about") ? "info" : lower.startsWith("run") ? "run" : lower.contains("open") ? "open" : lower.contains("sample") ? "sample" : lower.contains("export") ? "export" : lower.contains("save") ? "save" : lower.contains("refresh") ? "refresh" : lower.contains("close") ? "close" : lower.contains("relationship") ? "relationships" : lower.contains("catalog") ? "catalog" : lower.contains("builder") ? "builder" : lower.contains("query") || lower.contains("sql") ? "query" : lower.contains("design") ? "design" : lower.contains("database") ? "database" : "table";
        JButton b = WorkspaceStyle.command(text, icon, lower.startsWith("run"));
        b.addActionListener(e -> action.run());
        if (lower.startsWith("run")) b.setToolTipText("Run the current SQL statement (⌘Enter)");
        commandContext(b, text);
        (needsDatabase ? databaseCommands : idleCommands).add(b);
        return b;
    }

    private JMenuBar menu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(menuItem("Open Database…", KeyEvent.VK_O, this::chooseOpen, false));
        file.add(menuItem("Open a Copy…", 0, this::chooseOpenCopy, false));
        file.add(menuItem("New Database…", 0, this::createBlank, false));
        file.add(menuItem("Create Practice Sample…", 0, this::createSample, false));
        file.add(menuItem("New SQL Query", KeyEvent.VK_N, this::newQuery, true));
        file.add(menuItem("Open SQL File…", 0, this::openSqlFile, true));
        file.add(menuItem("Save SQL File…", KeyEvent.VK_S, this::saveSqlFile, true));
        file.addSeparator();
        file.add(menuItem("Export Database Copy…", 0, this::exportCopy, true));
        file.addSeparator();
        file.add(menuItem("Close Window", KeyEvent.VK_W, this::quit, false));
        JMenu query = new JMenu("Query");
        query.add(menuItem("Run SQL", KeyEvent.VK_R, this::runQuery, true));
        query.add(menuItem("Query Design…", 0, this::queryDesign, true));
        query.add(menuItem("Save Native Query (experimental)…", 0, this::saveNativeQuery, true));
        query.add(menuItem("Refresh Objects", 0, this::refresh, true));
        JMenu view = new JMenu("View");
        view.add(menuItem("Datasheet View", 0, () -> tableView(false), true));
        view.add(menuItem("Table Design View", 0, () -> tableView(true), true));
        view.addSeparator(); view.add(menuItem("Show / Hide Sidebar", 0, this::toggleSidebar, false));
        view.add(menuItem("Close Object", 0, this::closeObject, true));
        JMenu create = new JMenu("Create"); create.add(menuItem("Table Design…", 0, this::createTable, true)); create.add(menuItem("Query Builder…", 0, this::queryDesign, true));
        JMenu records = new JMenu("Records");
        records.add(menuItem("New Record…", 0, () -> tableAction("insert"), true)); records.add(menuItem("Delete Record…", 0, () -> tableAction("delete"), true)); records.add(menuItem("Set Field Null", 0, () -> tableAction("null"), true)); records.add(menuItem("Add Field…", 0, () -> tableAction("addField"), true));
        JMenu database = new JMenu("Database"); database.add(menuItem("Database Info…", 0, this::databaseInfo, true)); database.add(menuItem("Relationships", 0, this::relationships, true)); database.add(menuItem("Native Object Catalog", 0, this::nativeObjects, true));
        JMenu help = new JMenu("Help");
        help.add(menuItem("About Lattice", 0, this::about, false));
        bar.add(file); bar.add(create); bar.add(query); bar.add(records); bar.add(view); bar.add(database); bar.add(help);
        return bar;
    }

    private JMenuItem menuItem(String label, int key, Runnable action, boolean needsDatabase) {
        JMenuItem item = new JMenuItem(label);
        commandContext(item, label);
        if (key != 0) item.setAccelerator(KeyStroke.getKeyStroke(key, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        item.addActionListener(e -> action.run());
        (needsDatabase ? databaseCommands : idleCommands).add(item);
        return item;
    }

    private void commandContext(AbstractButton button, String label) {
        String text = label.toLowerCase(Locale.ROOT);
        String context = text.startsWith("run") || text.startsWith("save sql") ? "query"
            : text.startsWith("save native") ? "nativeQuery"
            : text.equals("datasheet") || text.equals("datasheet view") || text.equals("table design") || text.equals("table design view") ? "table"
            : text.equals("close object") ? "object"
            : text.startsWith("table design…") || text.startsWith("create table") ? "write" : "database";
        button.putClientProperty("commandContext", context);
    }

    private JComponent navigationPane() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(WorkspaceStyle.SIDEBAR);
        JPanel heading = new JPanel(new BorderLayout(0, 10)); heading.setOpaque(false); heading.setBorder(new EmptyBorder(15, 12, 12, 12));
        JLabel header = new JLabel("Objects"); header.setForeground(MUTED); header.setFont(header.getFont().deriveFont(Font.BOLD, 11f));
        objectSearch.putClientProperty("JTextField.variant", "search"); objectSearch.putClientProperty("JTextField.Search.Prompt", "Filter objects");
        objectSearch.setToolTipText("Filter object names (⇧⌘F); Escape clears the filter"); objectSearch.getAccessibleContext().setAccessibleName("Filter database objects");
        objectSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); } public void removeUpdate(DocumentEvent e) { filter(); } public void changedUpdate(DocumentEvent e) { filter(); }
            private void filter() { if (currentCatalog != null) fillNavigation(currentCatalog); }
        });
        objectSearch.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearFilter");
        objectSearch.getActionMap().put("clearFilter", new AbstractAction() { public void actionPerformed(ActionEvent e) { objectSearch.setText(""); navigation.requestFocusInWindow(); } });
        objectSearch.addActionListener(e -> { if (navigation.getSelectionPath() == null && navigation.getRowCount() > 1) navigation.setSelectionRow(1); navigation.requestFocusInWindow(); });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK), "findObject");
        getRootPane().getActionMap().put("findObject", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (!sidebarVisible) toggleSidebar(); objectSearch.requestFocusInWindow(); objectSearch.selectAll(); } });
        heading.add(header, BorderLayout.NORTH); heading.add(objectSearch, BorderLayout.CENTER); p.add(heading, BorderLayout.NORTH);
        navigation.setRootVisible(false);
        navigation.setShowsRootHandles(true);
        navigation.setRowHeight(30); navigation.setBackground(WorkspaceStyle.SIDEBAR);
        navigation.setFont(navigation.getFont().deriveFont(13f)); navigation.setBorder(new EmptyBorder(2, 8, 2, 6));
        navigation.getAccessibleContext().setAccessibleName("Database object navigation");
        navigation.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focused) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focused);
                setBackgroundNonSelectionColor(WorkspaceStyle.SIDEBAR); setBackgroundSelectionColor(tree.hasFocus() ? WorkspaceStyle.selection() : new Color(218, 223, 232)); setTextSelectionColor(tree.hasFocus() ? WorkspaceStyle.selectionText() : INK);
                Object item = ((DefaultMutableTreeNode) value).getUserObject();
                setBorder(new EmptyBorder(3, 4, 3, 5));
                if (item instanceof NavObject object) { setIcon(new WorkspaceStyle.Glyph(object.table ? "table" : object.nativeObject != null ? object.nativeObject.kind().name().toLowerCase(Locale.ROOT) : "query", 15, selected && tree.hasFocus() ? WorkspaceStyle.selectionText() : MUTED)); setFont(tree.getFont()); }
                else { setIcon(null); setFont(tree.getFont().deriveFont(Font.BOLD, 11f)); if (!selected) setForeground(MUTED); }
                return this;
            }
        });
        navigation.addTreeSelectionListener(e -> {
            if (!rebuildingNavigation && navigation.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof NavObject object) rememberedSelection = object;
            updateCommands();
        });
        navigation.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            public void treeExpanded(javax.swing.event.TreeExpansionEvent e) { remember(e, false); }
            public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) { remember(e, true); }
            private void remember(javax.swing.event.TreeExpansionEvent e, boolean collapsed) {
                if (rebuildingNavigation || !objectSearch.getText().isBlank()) return;
                String group = navigationGroup(e.getPath().getLastPathComponent().toString());
                if (collapsed) collapsedNavigationGroups.add(group); else collapsedNavigationGroups.remove(group);
            }
        });
        navigation.addFocusListener(new FocusAdapter() { public void focusGained(FocusEvent e) { navigation.repaint(); } public void focusLost(FocusEvent e) { navigation.repaint(); } });
        navigation.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) openSelected(); }
        });
        navigation.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openObject");
        navigation.getActionMap().put("openObject", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { openSelected(); }
        });
        JScrollPane scroll = new JScrollPane(navigation); scroll.setBorder(null); scroll.getViewport().setBackground(WorkspaceStyle.SIDEBAR); p.add(scroll, BorderLayout.CENTER);
        JLabel hint = new JLabel("Double-click or press Return to open");
        hint.setForeground(MUTED);
        hint.setFont(hint.getFont().deriveFont(11f));
        hint.setBorder(new EmptyBorder(8, 10, 8, 4));
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    private JComponent statusBar() {
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setBackground(PAPER);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LINE), new EmptyBorder(7, 12, 7, 12)));
        status.setFont(status.getFont().deriveFont(11f));
        status.setForeground(MUTED);
        p.add(status, BorderLayout.CENTER);
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(100, 13));
        progress.setVisible(false);
        p.add(progress, BorderLayout.EAST);
        return p;
    }

    private void showWelcome() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Color.WHITE);
        JPanel card = new JPanel();
        card.setBackground(Color.WHITE);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Work with an Access database");
        title.setForeground(INK);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        card.add(title); card.add(Box.createVerticalStrut(16));
        card.add(new JLabel("Open a real .accdb or .mdb file, or start with the practice sample."));
        card.add(Box.createVerticalStrut(7));
        card.add(new JLabel("Changes save to the file you open. Use Open a Copy to keep a separate source."));
        card.add(Box.createVerticalStrut(20));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        actions.add(button("Open Database…", this::chooseOpen, false));
        actions.add(Box.createHorizontalStrut(12));
        actions.add(button("New Database…", this::createBlank, false));
        actions.add(Box.createHorizontalStrut(12)); actions.add(button("Practice Sample…", this::createSample, false));
        card.add(actions); card.add(Box.createVerticalStrut(26));
        JLabel limits = new JLabel("SQL View, tables and saved queries • Forms, reports, macros and VBA unavailable");
        limits.setForeground(MUTED);
        limits.setFont(limits.getFont().deriveFont(12f));
        card.add(limits);
        p.add(card);
        objects.addTab("Start", p);
    }

    private void chooseOpen() { chooseOpen(false); }

    private void chooseOpenCopy() { chooseOpen(true); }

    private void chooseOpen(boolean copy) {
        if (!idle()) return;
        FileDialog picker = new FileDialog(this, copy ? "Open a copy of an Access database" : "Open Access database", FileDialog.LOAD);
        picker.setFilenameFilter((dir, name) -> isAccessFile(name));
        picker.setVisible(true);
        if (picker.getFile() == null) return;
        Path source = Path.of(picker.getDirectory(), picker.getFile());
        if (copy) withUnsavedChanges(null, () -> openDatabaseCopy(source));
        else requestOpen(source);
    }

    private static boolean isAccessFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".accdb") || lower.endsWith(".mdb");
    }

    private void requestOpen(Path source) {
        if (closing) return;
        if (jobs != 0) { pendingOpens.add(source); return; }
        // Reopening the current document must not discard SQL tabs or create a second connection.
        try {
            if (workingPath != null && Files.isSameFile(source, workingPath)) {
                status.setText("Already open: " + workingPath.getFileName());
                toFront();
                return;
            }
        } catch (java.io.IOException ignored) { /* The normal open validation explains missing/inaccessible files. */ }
        withUnsavedChanges(null, () -> openDatabase(source));
    }

    private Path checkedSource(Path source) {
        Path original = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(original) || !isAccessFile(original.getFileName().toString())) {
            error("Open database", new IllegalArgumentException("Choose an existing .accdb or .mdb file."));
            return null;
        }
        return original;
    }

    private void openDatabase(Path source) {
        Path selected = checkedSource(source);
        if (selected == null) return;
        submit("Opening database…", () -> prepareDatabase(AccessDatabase.open(selected)), this::opened);
    }

    private void openDatabaseCopy(Path source) {
        Path original = checkedSource(source);
        if (original == null) return;
        String file = original.getFileName().toString();
        int dot = file.lastIndexOf('.');
        Path target = chooseDestination("Create and open a working copy",
                file.substring(0, dot) + "-working" + file.substring(dot), original.getParent());
        if (target == null || !sameFileType(original, target) || !safeNewDestination(original, target)) return;
        submit("Opening working copy…", () -> prepareDatabase(AccessDatabase.openCopy(original, target)), this::opened);
    }

    private Opened prepareDatabase(AccessDatabase candidate) throws Exception {
        try { return replaceDatabase(candidate, catalog(candidate)); }
        catch (Exception | LinkageError failure) {
            try { candidate.close(); }
            catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void createSample() {
        if (!idle()) return;
        withUnsavedChanges(null, this::createSampleNow);
    }

    private void createBlank() {
        if (!idle()) return;
        withUnsavedChanges(null, () -> {
            Path source = chooseDestination("Create a new native Access database", "Database.accdb", null);
            if (source == null || !safeNewDestination(null, source)) return;
            submit("Creating blank native database…", () -> {
                AccessDatabase.createBlank(source);
                return prepareDatabase(AccessDatabase.open(source));
            }, this::opened);
        });
    }

    private void createSampleNow() {
        Path sample = chooseDestination("Create a new practice sample", "SQL-Practice.accdb", null);
        if (sample == null || !safeNewDestination(null, sample)) return;
        if (!sample.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".accdb")) {
            error("Sample file", new IllegalArgumentException("The practice sample must have an .accdb extension."));
            return;
        }
        submit("Creating practice sample…", () -> {
            AccessDatabase.createSample(sample);
            return prepareDatabase(AccessDatabase.open(sample));
        }, value -> {
            opened(value);
            addQuery("Practice: customers by city", "SELECT City, Count(*) AS CustomerCount\nFROM Customers\nGROUP BY City\nORDER BY City;", null);
            showExercises();
        });
    }

    private Path chooseDestination(String title, String filename, Path directory) {
        FileDialog picker = new FileDialog(this, title, FileDialog.SAVE);
        picker.setFile(filename);
        if (directory != null) picker.setDirectory(directory.toString());
        picker.setVisible(true);
        if (picker.getFile() == null) return null;
        String selected = picker.getFile();
        if (!isAccessFile(selected)) selected += filename.toLowerCase(Locale.ROOT).endsWith(".mdb") ? ".mdb" : ".accdb";
        return Path.of(picker.getDirectory(), selected).toAbsolutePath().normalize();
    }

    private boolean safeNewDestination(Path source, Path destination) {
        if (source != null && source.toAbsolutePath().normalize().equals(destination)) {
            error("Choose a new file", new IllegalArgumentException("The destination must be different from the original file."));
            return false;
        }
        if (Files.exists(destination)) {
            error("Choose a new file", new IllegalArgumentException("A file already exists at this destination. Choose a new name; existing files are never overwritten."));
            return false;
        }
        return true;
    }

    private boolean sameFileType(Path source, Path destination) {
        String expected = source.toString().toLowerCase(Locale.ROOT).endsWith(".mdb") ? ".mdb" : ".accdb";
        if (!destination.toString().toLowerCase(Locale.ROOT).endsWith(expected)) {
            error("File extension", new IllegalArgumentException("Keep the " + expected + " extension for this native Access file."));
            return false;
        }
        return true;
    }

    private static Catalog catalog(AccessDatabase db) throws Exception { return new Catalog(db.tables(), db.savedQueries()); }

    private Opened replaceDatabase(AccessDatabase candidate, Catalog catalog) throws Exception {
        AccessDatabase previous = database;
        if (previous != null) previous.flush();
        database = candidate;
        List<String> limitations = new ArrayList<>(candidate.limitations());
        if (previous != null) {
            try { previous.close(); }
            catch (Exception e) { limitations.add("Previous database was saved, but closing it failed: " + e.getMessage()); }
        }
        return new Opened(candidate.originalPath(), candidate.workingPath(), candidate.recoveryBackupPath(), catalog, candidate.isDirect(), candidate.isReadOnly(), limitations);
    }

    private void opened(Opened value) {
        originalPath = value.original;
        workingPath = value.working;
        databaseReadOnly = value.readOnly;
        databaseDirect = value.direct;
        recoveryBackupPath = value.recoveryBackup;
        nativeCatalog = null; rememberedSelection = null; collapsedNavigationGroups.clear();
        objects.removeAll(); openObjects.clear();
        fillNavigation(value.catalog);
        setTitle(workingPath.getFileName() + " — Lattice");
        documentTitle.setText(workingPath.getFileName().toString()); documentTitle.setToolTipText(workingPath.toString());
        documentSubtitle.setText("Native Access database · " + value.catalog.tables.size() + " tables");
        documentBadge.setText(value.readOnly ? "Read only" : value.direct ? "Editing file" : "Working copy");
        documentBadge.setToolTipText(value.readOnly ? String.join("; ", value.limitations) : value.direct ? "Changes save to this file. Recovery backup: " + value.recoveryBackup : "Changes save to the separate working copy. Recovery backup: " + value.recoveryBackup);
        documentBadge.setForeground(value.readOnly ? MAROON : MUTED);
        paths.setText((value.readOnly ? "READ ONLY — " : "") + (value.direct ? "File: " + workingPath : "Working copy: " + workingPath + "    |    Original: " + originalPath));
        paths.setToolTipText((value.readOnly ? "Read only. " : value.direct ? "Changes save to this file. " : "Changes save to the working copy. ") + "Recovery backup: " + value.recoveryBackup + (value.limitations.isEmpty() ? "" : " — " + String.join("; ", value.limitations)));
        if (value.catalog.tables.isEmpty()) {
            JPanel panel = new JPanel(new GridBagLayout()); panel.setBackground(Color.WHITE);
            JPanel card = new JPanel(); card.setOpaque(false); card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            JLabel title = new JLabel("Your database is ready"); title.setFont(title.getFont().deriveFont(Font.BOLD, 24f)); title.setForeground(INK);
            card.add(title); card.add(Box.createVerticalStrut(14)); card.add(new JLabel("Create a table to add fields and records.")); card.add(Box.createVerticalStrut(18));
            card.add(button("Create Table…", this::createTable, true)); panel.add(card); objects.addTab("New Database", panel);
        } else addQuery("Query1", "SELECT * FROM " + identifier(value.catalog.tables.get(0)) + ";", null);
        status.setText("Opened " + workingPath.getFileName() + (value.readOnly ? " — read only: " + String.join("; ", value.limitations) : value.direct ? " — changes save to this file. Recovery backup created automatically." : " — changes save to the working copy."));
        if (!value.readOnly && !value.limitations.isEmpty()) JOptionPane.showMessageDialog(this, String.join("\n", value.limitations), "Database limitations", JOptionPane.WARNING_MESSAGE);
        submit("Reading native object catalog…", () -> AccessObjectCatalog.inspect(database.workingPath()), snapshot -> {
            nativeCatalog = snapshot;
            fillNavigation(currentCatalog);
            status.setText(databaseReadOnly ? "Native catalog loaded — read only. Application objects are available for inspection only." : databaseDirect ? "Changes save to this file. Automatic recovery backup available in Database Info." : "Working copy ready. Original source remains separate.");
        });
    }

    private void fillNavigation(Catalog catalog) {
        currentCatalog = catalog;
        NavObject selected = navigation.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof NavObject object ? object : rememberedSelection;
        rebuildingNavigation = true;
        String filter = objectSearch.getText().strip().toLowerCase(Locale.ROOT);
        treeRoot.removeAllChildren();
        DefaultMutableTreeNode tables = new DefaultMutableTreeNode("Tables (" + catalog.tables.size() + ")");
        for (String table : catalog.tables) if (table.toLowerCase(Locale.ROOT).contains(filter)) tables.add(new DefaultMutableTreeNode(new NavObject(table, null, true)));
        DefaultMutableTreeNode queries = new DefaultMutableTreeNode("Queries (" + catalog.queries.size() + ")");
        for (SavedQuery query : catalog.queries) if (query.name().toLowerCase(Locale.ROOT).contains(filter)) queries.add(new DefaultMutableTreeNode(new NavObject(query.name(), query.sql(), false)));
        treeRoot.add(tables); treeRoot.add(queries);
        if (nativeCatalog != null) {
            Map<AccessObjectCatalog.Kind, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
            for (AccessObjectCatalog.NativeObject object : nativeCatalog.applicationObjects()) {
                if (!object.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
                DefaultMutableTreeNode group = groups.computeIfAbsent(object.kind(), kind -> new DefaultMutableTreeNode(kind.name().substring(0, 1) + kind.name().substring(1).toLowerCase(Locale.ROOT) + "s · inspect"));
                group.add(new DefaultMutableTreeNode(new NavObject(object.name(), null, false, object)));
            }
            for (DefaultMutableTreeNode group : groups.values()) treeRoot.add(group);
        }
        treeModel.reload();
        for (int index = 0; index < treeRoot.getChildCount(); index++) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) treeRoot.getChildAt(index);
            if (!filter.isEmpty() || !collapsedNavigationGroups.contains(navigationGroup(group.toString()))) navigation.expandPath(new javax.swing.tree.TreePath(group.getPath()));
        }
        if (selected != null) {
            var nodes = treeRoot.depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                if (node.getUserObject() instanceof NavObject object && object.name.equals(selected.name) && object.table == selected.table && Objects.equals(object.nativeObject == null ? null : object.nativeObject.kind(), selected.nativeObject == null ? null : selected.nativeObject.kind())) {
                    DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
                    boolean collapsed = filter.isEmpty() && collapsedNavigationGroups.contains(navigationGroup(parent.toString()));
                    navigation.setSelectionPath(new javax.swing.tree.TreePath((collapsed ? parent : node).getPath())); break;
                }
            }
        }
        rebuildingNavigation = false;
        updateCommands();
    }

    private static String navigationGroup(String label) { int count = label.indexOf(" ("); int note = label.indexOf(" ·"); return label.substring(0, count >= 0 ? count : note >= 0 ? note : label.length()); }

    private void openSelected() {
        if (!ready()) return;
        Object selected = navigation.getLastSelectedPathComponent();
        if (selected instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof NavObject item) {
            if (item.nativeObject != null) showNativeObject(item.nativeObject);
            else if (item.table) openTable(item.name);
            else {
                JComponent existing = openObjects.get("query:" + item.name);
                if (existing != null) objects.setSelectedComponent(existing);
                else addQuery(item.name, item.sql, item.name);
            }
        }
    }

    private void openTable(String name) { openTable(name, false); }

    private void openTable(String name, boolean design) {
        String key = "table:" + name;
        JComponent existing = openObjects.get(key);
        if (existing != null) {
            objects.setSelectedComponent(existing);
            if (existing instanceof TablePanel t) t.views.setSelectedIndex(design ? 1 : 0);
            return;
        }
        submit("Loading " + name + "…", () -> loadTable(name), data -> {
            TablePanel panel = new TablePanel(name, data);
            openObjects.put(key, panel);
            objects.addTab(name, new WorkspaceStyle.Glyph("table", 14, MUTED), panel);
            objects.setToolTipTextAt(objects.indexOfComponent(panel), "Table: " + name);
            objects.setSelectedComponent(panel);
            panel.views.setSelectedIndex(design ? 1 : 0);
            status.setText("Table " + name + ": " + rowSummary(data.records));
        });
    }

    private static String identifier(String name) { return "[" + name.replace("]", "]]") + "]"; }

    private TableData loadTable(String name) throws Exception {
        return new TableData(database.columns(name), database.fields(name), database.execute("SELECT * FROM " + identifier(name)));
    }

    private void newQuery() {
        if (!ready()) return;
        addQuery("Query" + (++queryNumber + 1), "SELECT * FROM [YourTable];", null);
    }

    private void addQuery(String title, String sql, String savedName) {
        QueryPanel p = new QueryPanel(sql, savedName);
        if (savedName != null) openObjects.put("query:" + savedName, p);
        objects.addTab(title, new WorkspaceStyle.Glyph("query", 14, MUTED), p);
        objects.setToolTipTextAt(objects.indexOfComponent(p), "SQL query: " + title); objects.setSelectedComponent(p);
        p.editor.requestFocusInWindow();
    }

    private QueryPanel currentQuery() {
        return objects.getSelectedComponent() instanceof QueryPanel q ? q : null;
    }

    private void runQuery() {
        if (!ready()) return;
        QueryPanel query = currentQuery();
        if (query == null) { status.setText("Open a SQL Query tab to run a statement."); return; }
        String sql = query.editor.getSelectedText();
        if (sql == null || sql.isBlank()) sql = query.editor.getText();
        if (sql.isBlank()) { status.setText("Enter an Access SQL statement first."); return; }
        String statement = sql;
        try {
            List<ParameterInfo> parameters = AccessDatabase.parameters(statement);
            List<Object> values = parameters.isEmpty() ? List.of() : parameterValues(parameters);
            if (values == null) return;
            executeQuery(query, statement, values);
        } catch (IllegalArgumentException invalid) { error("Query parameters", invalid); }
        catch (Exception | LinkageError invalid) { error("Query parameters", invalid); }
    }

    private void executeQuery(QueryPanel query, String statement, List<Object> values) {
        submit("Running SQL…", () -> {
            QueryResult result = database.execute(statement, values);
            database.flush();
            return new AbstractMap.SimpleImmutableEntry<>(result, catalog(database));
        }, value -> {
            query.showResult(value.getKey());
            fillNavigation(value.getValue());
            status.setText(rowSummary(value.getKey()));
        });
    }

    private List<Object> parameterValues(List<ParameterInfo> parameters) {
        JPanel fields = new JPanel(new GridLayout(0, 3, 8, 8));
        List<JTextField> values = new ArrayList<>();
        List<JCheckBox> nulls = new ArrayList<>();
        for (ParameterInfo parameter : parameters) {
            fields.add(new JLabel(parameter.name() + " (" + parameter.type() + ")"));
            JTextField value = new JTextField(24); values.add(value); fields.add(value);
            JCheckBox isNull = new JCheckBox("NULL"); nulls.add(isNull); fields.add(isNull);
        }
        while (JOptionPane.showConfirmDialog(this, fields, "Enter Parameter Values", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                List<Object> parsed = new ArrayList<>();
                for (int index = 0; index < parameters.size(); index++) parsed.add(nulls.get(index).isSelected() ? null : ValueParser.parse(parameters.get(index).type(), values.get(index).getText(), true));
                return parsed;
            } catch (IllegalArgumentException invalid) { error("Parameter value", invalid); }
        }
        return null;
    }

    private void saveSqlFile() {
        if (!ready()) return;
        QueryPanel query = currentQuery();
        if (query == null) { status.setText("Open a SQL Query tab before saving SQL text."); return; }
        saveSqlFile(query, () -> {});
    }

    private void saveNativeQuery() {
        if (!ready()) return;
        QueryPanel query = currentQuery();
        if (query == null) { status.setText("Open a SQL Query tab before saving a native query."); return; }
        if (databaseReadOnly) { status.setText("This database is read only; native query saving is unavailable."); return; }
        JTextField name = new JTextField(query.savedName == null ? "MyQuery" : query.savedName + "Copy", 26);
        JPanel panel = new JPanel(new BorderLayout(8, 12));
        JTextArea note = new JTextArea("Experimental native Access query saving\n\nSupported: one local table SELECT, expressions, WHERE,\nGROUP BY, HAVING and ORDER BY. Joins, parameters, UNION\nand advanced designs are not supported by this writer.\n\nA full pre-query backup is created before changing the database.\nVerified with Jackcess/UCanAccess; Windows Access reopening\nhas not been verified. Save SQL File remains a separate fallback.");
        note.setEditable(false); note.setOpaque(false); note.setFont(note.getFont().deriveFont(12f));
        panel.add(note, BorderLayout.CENTER); panel.add(name, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, panel, "Save Native Query — Experimental", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        String queryName = name.getText().trim(), sql = query.editor.getText();
        submit("Saving experimental native query…", () -> {
            database.saveQueryExperimental(queryName, sql); database.flush(); return catalog(database);
        }, saved -> {
            if (query.savedName != null) openObjects.remove("query:" + query.savedName);
            query.savedName = queryName; query.setDirty(false);
            openObjects.put("query:" + queryName, query);
            int index = objects.indexOfComponent(query); if (index >= 0) objects.setTitleAt(index, queryName);
            fillNavigation(saved);
            status.setText("Experimental query saved natively with a pre-query backup — Windows Access reopening remains unverified.");
        });
    }

    private void openSqlFile() {
        if (!ready()) return;
        FileDialog picker = new FileDialog(this, "Open SQL text in a new query tab", FileDialog.LOAD);
        picker.setFilenameFilter((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".sql"));
        picker.setVisible(true);
        if (picker.getFile() == null) return;
        Path source = Path.of(picker.getDirectory(), picker.getFile()).toAbsolutePath().normalize();
        submit("Loading SQL text…", () -> {
            if (Files.size(source) > 1_000_000) throw new IllegalArgumentException("SQL text files over 1 MB are not supported by this editor.");
            return Files.readString(source);
        }, sql -> {
            addQuery(source.getFileName().toString(), sql, null);
            currentQuery().sqlPath = source;
            currentQuery().setDirty(false);
            status.setText("Opened SQL text from " + source);
        });
    }

    private void saveSqlFile(QueryPanel query, Runnable finished) {
        Path target = query.sqlPath;
        if (target == null) {
        FileDialog picker = new FileDialog(this, "Save SQL text separately from native Access queries", FileDialog.SAVE);
        picker.setFile((query.savedName == null ? "Query" : query.savedName) + ".sql");
        picker.setDirectory(workingPath.getParent().toString());
        picker.setVisible(true);
        if (picker.getFile() == null) return;
        String filename = picker.getFile();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".sql")) filename += ".sql";
        target = Path.of(picker.getDirectory(), filename).toAbsolutePath().normalize();
        if (!safeNewDestination(null, target)) return;
        }
        Path destination = target;
        boolean newFile = query.sqlPath == null;
        String sql = query.editor.getText();
        submit("Saving SQL text…", () -> {
            if (newFile) Files.writeString(destination, sql, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            else Files.writeString(destination, sql, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return destination;
        }, saved -> {
            query.sqlPath = saved;
            query.setDirty(false);
            status.setText("Saved SQL text: " + saved + " — separate from native Access saved queries.");
            finished.run();
        });
    }

    private void withUnsavedChanges(QueryPanel only, Runnable continuation) {
        List<QueryPanel> dirty = new ArrayList<>();
        for (int index = 0; index < objects.getTabCount(); index++) {
            if (objects.getComponentAt(index) instanceof QueryPanel query && query.dirty && (only == null || query == only)) dirty.add(query);
        }
        if (dirty.isEmpty()) { continuation.run(); return; }
        String[] options = {"Save SQL files…", "Discard changes", "Cancel"};
        int decision = JOptionPane.showOptionDialog(this,
                "SQL editor changes have not been saved. Native Access query writing is experimental.\n"
                + "Save the changed editors as separate .sql files before continuing?",
                "Unsaved SQL", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (decision == 0) saveDirtyQueries(dirty, 0, continuation);
        else if (decision == 1) continuation.run();
    }

    private void saveDirtyQueries(List<QueryPanel> queries, int index, Runnable continuation) {
        if (index == queries.size()) { continuation.run(); return; }
        saveSqlFile(queries.get(index), () -> saveDirtyQueries(queries, index + 1, continuation));
    }

    private void refresh() {
        if (!ready()) return;
        JComponent selected = (JComponent) objects.getSelectedComponent();
        submit("Refreshing objects…", () -> {
            Catalog list = catalog(database);
            TableData data = selected instanceof TablePanel t ? loadTable(t.name) : null;
            return new AbstractMap.SimpleImmutableEntry<>(list, data);
        }, value -> {
            fillNavigation(value.getKey());
            if (selected instanceof TablePanel t && value.getValue() != null) t.load(value.getValue());
            status.setText("Objects refreshed from the database.");
        });
    }

    private void createTable() {
        if (!ready()) return;
        if (databaseReadOnly) { status.setText("This database is read only; table creation is unavailable."); return; }
        String sql = TableDesignDialog.show(this);
        if (sql == null) return;
        List<String> before = currentCatalog.tables;
        submit("Creating table in database…", () -> {
            database.execute(sql); database.flush(); return catalog(database);
        }, created -> {
            fillNavigation(created); status.setText("Table created in the database.");
            created.tables.stream().filter(table -> !before.contains(table)).findFirst().ifPresent(this::openTable);
        });
    }

    private void queryDesign() {
        if (!ready()) return;
        submit("Loading fields for Query Design…", () -> {
            Map<String, List<ColumnInfo>> schema = new LinkedHashMap<>();
            for (String table : database.tables()) schema.put(table, database.columns(table));
            return schema;
        }, schema -> {
            if (schema.isEmpty()) { status.setText("Create a table before designing a query."); return; }
            String sql = QueryDesignDialog.show(this, schema);
            if (sql != null) {
                addQuery("Designed Query", sql, null);
                currentQuery().setDirty(true);
                status.setText("Query designed. Run it in SQL View to see the result.");
            }
        });
    }

    private void relationships() {
        if (!ready()) return;
        submit("Reading native relationships…", () -> database.relationships(), relationships -> {
            List<List<Object>> rows = new ArrayList<>();
            for (RelationshipInfo rel : relationships) rows.add(Arrays.asList(rel.name(), rel.fromTable(), String.join(", ", rel.fromColumns()), rel.toTable(), String.join(", ", rel.toColumns()), rel.referentialIntegrity(), rel.cascadeUpdates(), rel.cascadeDeletes()));
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JScrollPane(grid(List.of("Relationship", "From Table", "From Fields", "To Table", "To Fields", "Enforce Integrity", "Cascade Updates", "Cascade Deletes"), rows)), BorderLayout.CENTER);
            JLabel hint = new JLabel(" " + relationships.size() + " native relationship(s). Inspector only; relationship creation/deletion is unavailable.");
            hint.setBorder(new EmptyBorder(8, 8, 8, 8)); panel.add(hint, BorderLayout.SOUTH);
            objects.addTab("Relationships", panel); objects.setSelectedComponent(panel);
            status.setText("Relationships inspected from the native database.");
        });
    }

    private void nativeObjects() {
        if (!ready()) return;
        submit("Inspecting native objects…", () -> AccessObjectCatalog.inspect(database.workingPath()), snapshot -> {
            nativeCatalog = snapshot; fillNavigation(currentCatalog);
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("Native Access catalog");
            Map<AccessObjectCatalog.Kind, DefaultMutableTreeNode> kinds = new LinkedHashMap<>();
            for (AccessObjectCatalog.NativeObject object : snapshot.objects()) {
                DefaultMutableTreeNode kind = kinds.computeIfAbsent(object.kind(), value -> new DefaultMutableTreeNode(value.name()));
                kind.add(new DefaultMutableTreeNode(object));
            }
            for (DefaultMutableTreeNode kind : kinds.values()) root.add(kind);
            DefaultMutableTreeNode storage = new DefaultMutableTreeNode("Native storage nodes");
            for (var node : snapshot.storageNodes()) storage.add(new DefaultMutableTreeNode(node));
            root.add(storage);
            JTree tree = new JTree(root); tree.setRowHeight(25);
            tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
                @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focused) {
                    super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focused);
                    Object item = ((DefaultMutableTreeNode) value).getUserObject();
                    if (item instanceof AccessObjectCatalog.NativeObject object) setText(object.name() + (object.system() ? " (system)" : ""));
                    else if (item instanceof AccessObjectCatalog.StorageNode node) setText(node.name() == null ? "ID " + node.id() : node.name());
                    return this;
                }
            });
            JTextArea detail = inspectionText("Catalog metadata only. Select an object or storage node.\n\n" + String.join("\n", snapshot.diagnostics()));
            tree.addTreeSelectionListener(e -> {
                Object selected = tree.getLastSelectedPathComponent();
                if (selected instanceof DefaultMutableTreeNode node) {
                    Object item = node.getUserObject();
                    if (item instanceof AccessObjectCatalog.NativeObject object) detail.setText(nativeObjectText(object));
                    else if (item instanceof AccessObjectCatalog.StorageNode raw) detail.setText("Native storage node — opaque definition data\n\n" + raw.toString());
                    detail.setCaretPosition(0);
                }
            });
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), new JScrollPane(detail)); split.setDividerLocation(300);
            objects.addTab("Native Object Catalog", split); objects.setSelectedComponent(split);
            status.setText("Catalog metadata inspected; application-object rendering and execution are unavailable.");
        });
    }

    private void showNativeObject(AccessObjectCatalog.NativeObject object) {
        JScrollPane pane = new JScrollPane(inspectionText(nativeObjectText(object)));
        objects.addTab(object.name() + " (inspect)", pane); objects.setSelectedComponent(pane);
        status.setText("Native " + object.kind().name().toLowerCase(Locale.ROOT) + " metadata only; rendering/execution unavailable.");
    }

    private static JTextArea inspectionText(String text) {
        JTextArea area = new JTextArea(text); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); area.setMargin(new Insets(16, 16, 16, 16)); return area;
    }

    private static String nativeObjectText(AccessObjectCatalog.NativeObject object) {
        StringBuilder text = new StringBuilder("Native Access catalog metadata — inspection only\n\n")
                .append("Name: ").append(object.name()).append("\nKind: ").append(object.kind())
                .append("\nNative type: ").append(object.rawType()).append("\nFlags: ").append(object.flags())
                .append("\nObject ID: ").append(object.id()).append("\nParent ID: ").append(object.parentId())
                .append("\n\nRendering, design editing and execution of this object are unavailable.\n\nCatalog fields:\n");
        object.metadata().forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
        text.append("\nOpaque blob fingerprints (not decoded definitions):\n");
        object.blobs().forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
        return text.toString();
    }

    private void tableView(boolean design) {
        if (!ready()) return;
        if (objects.getSelectedComponent() instanceof TablePanel table) table.views.setSelectedIndex(design ? 1 : 0);
        else {
            Object selected = navigation.getLastSelectedPathComponent();
            if (selected instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof NavObject item && item.table) {
                openTable(item.name, design);
            } else status.setText("Open a table to choose Datasheet View or Design View.");
        }
    }

    private void closeObject() {
        if (!ready()) return;
        Component selected = objects.getSelectedComponent();
        if (selected != null) {
            Runnable close = () -> {
            openObjects.values().removeIf(c -> c == selected);
            objects.remove(selected);
            };
            if (selected instanceof QueryPanel query) withUnsavedChanges(query, close);
            else close.run();
        }
    }

    private void exportCopy() {
        if (!ready()) return;
        String name = workingPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path target = chooseDestination("Export a native Access database copy", name.substring(0, dot) + "-export" + name.substring(dot), workingPath.getParent());
        if (target == null || !sameFileType(workingPath, target) || !safeNewDestination(originalPath, target) || !safeNewDestination(workingPath, target)) return;
        submit("Exporting native Access database copy…", () -> {
            database.flush();
            Files.copy(database.workingPath(), target);
            return target;
        }, saved -> status.setText("Exported native Access file: " + saved));
    }

    private void showExercises() {
        JTextArea text = new JTextArea("Practice with this sample database:\n\n1. SELECT FullName, City FROM Customers ORDER BY FullName;\n\n2. SELECT City, Count(*) AS CustomerCount\n   FROM Customers GROUP BY City;\n\n3. SELECT Customers.FullName, Orders.Amount\n   FROM Customers INNER JOIN Orders\n   ON Customers.CustomerID = Orders.CustomerID;\n\n4. SELECT * FROM Orders WHERE Amount > 100;\n\n5. PARAMETERS [Chosen City] Text;\n   SELECT * FROM Customers WHERE City = [Chosen City];\n\nUse SQL View for INSERT, UPDATE, DELETE, SELECT INTO and DDL.\nRun SQL with ⌘Enter; save SQL text separately with ⌘S.\nNative saved-query writing is explicitly experimental and limited.\nChanges save directly to the sample file you created.");
        text.setEditable(false); text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        text.setBorder(new EmptyBorder(20, 20, 20, 20));
        objects.addTab("Practice exercises", new JScrollPane(text));
    }

    private void about() {
        JOptionPane.showMessageDialog(this,
            "Lattice — independent Access-compatible desktop application\n\n"
            + "Real .accdb/.mdb files and Access SQL through UCanAccess / Jackcess.\n"
            + "Changes save to the file you open. Open a Copy keeps a separate source.\nAn automatic recovery backup is available in Database Info.\n"
            + "Basic primary-key tables support cell editing, insertion and deletion.\n"
            + "Basic table creation, field addition and a SELECT query builder are available.\n"
            + "Existing native saved queries can be read; limited native query writing is experimental.\n"
            + "Save SQL File writes separate text, not a saved Access query.\n\n"
            + "Relationships and native application objects can be inspected.\n"
            + "Forms/report rendering, macros, VBA and relationship editing are unavailable.\n"
            + "Advanced/application-object files open read only; encrypted/linked files may be refused.\n"
            + "Microsoft Access visual and behavioral parity is not yet verified.\n"
            + "This is not an official Microsoft product.", "About & limitations", JOptionPane.INFORMATION_MESSAGE);
    }

    private boolean idle() { return jobs == 0 && !closing; }
    private boolean ready() { return idle() && workingPath != null; }

    private void updateCommands() {
        for (AbstractButton button : idleCommands) button.setEnabled(idle());
        boolean tableAvailable = objects.getSelectedComponent() instanceof TablePanel
            || navigation.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof NavObject item && item.table;
        for (AbstractButton button : databaseCommands) {
            String context = String.valueOf(button.getClientProperty("commandContext"));
            boolean available = switch (context) {
                case "query" -> currentQuery() != null;
                case "nativeQuery" -> currentQuery() != null && !databaseReadOnly;
                case "table" -> tableAvailable;
                case "object" -> objects.getSelectedComponent() != null;
                case "write" -> !databaseReadOnly;
                default -> true;
            };
            button.setEnabled(ready() && available);
        }
        navigation.setEnabled(ready());
        for (int index = 0; index < objects.getTabCount(); index++) {
            if (objects.getComponentAt(index) instanceof QueryPanel query) query.editor.setEditable(idle());
            if (objects.getComponentAt(index) instanceof TablePanel table) table.updateButtons();
        }
        progress.setVisible(jobs > 0);
        setCursor(jobs > 0 ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private <T> void submit(String message, Callable<T> task, Consumer<T> finished) {
        jobs++; status.setText(message); updateCommands();
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                try { finished.accept(get()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); error(message, e); }
                catch (ExecutionException e) { error(message, e.getCause()); }
                catch (RuntimeException | LinkageError e) { error(message, e); }
                finally {
                    jobs--;
                    if (jobs == 0 && closing && isDisplayable()) closing = false;
                    updateCommands();
                    if (jobs == 0 && !closing && pendingQuit) { pendingQuit = false; quit(); }
                    else if (jobs == 0 && !closing && !pendingOpens.isEmpty()) requestOpen(pendingOpens.removeFirst());
                }
            }
        };
        engine.execute(worker);
    }

    private void error(String action, Throwable failure) {
        String message = errorMessage(failure);
        status.setText("Failed: " + message);
        JTextArea detail = new JTextArea(message, 7, 65);
        detail.setEditable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true);
        detail.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(detail), action, JOptionPane.ERROR_MESSAGE);
    }

    private static String errorMessage(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            String detail = String.valueOf(cause.getMessage());
            if (detail.contains("javax/script/") || detail.contains("javax.script.") || detail.contains("java.scripting")) {
                return "This Lattice installation is missing a component required to open some Access databases. "
                    + "Install the updated Lattice app, then reopen your database. Your database has not been renamed or moved.";
            }
        }
        if (failure instanceof LinkageError) {
            return "This Lattice installation is missing a required database component. "
                + "Install the updated Lattice app, then try again.";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private void quit() {
        if (closing) return;
        if (jobs != 0) { pendingQuit = true; status.setText("Finishing the current operation before closing…"); return; }
        withUnsavedChanges(null, this::beginQuit);
    }

    private void beginQuit() {
        closing = true;
        submit("Saving and closing database…", () -> {
            if (database != null) { database.flush(); database.close(); database = null; }
            return null;
        }, unused -> { engine.shutdown(); dispose(); });
        // A failed flush must keep the window and database path available for recovery.
    }

    private void installDesktopHandlers() {
        if (!Desktop.isDesktopSupported()) return;
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) desktop.setOpenFileHandler(event -> {
            for (java.io.File file : event.getFiles()) SwingUtilities.invokeLater(() -> requestOpen(file.toPath()));
        });
        if (desktop.isSupported(Desktop.Action.APP_ABOUT)) desktop.setAboutHandler(event -> SwingUtilities.invokeLater(this::about));
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler((event, response) -> {
            response.cancelQuit(); SwingUtilities.invokeLater(this::quit);
        });
    }

    private static JTable grid(List<String> columns, List<List<Object>> rows) {
        Object[][] cells = new Object[rows.size()][columns.size()];
        for (int r = 0; r < rows.size(); r++) for (int c = 0; c < columns.size(); c++) cells[r][c] = rows.get(r).get(c);
        JTable table = new JTable(new DefaultTableModel(cells, columns.toArray()) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        }) {
            @Override public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
                Component cell = super.prepareRenderer(renderer, row, column);
                if (!isCellSelected(row, column)) { cell.setBackground(row % 2 == 0 ? Color.WHITE : new Color(249, 250, 252)); cell.setForeground(INK); }
                else if (!hasFocus()) { cell.setBackground(new Color(218, 223, 232)); cell.setForeground(INK); }
                if (cell instanceof JLabel label) label.setHorizontalAlignment(getValueAt(row, column) instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);
                if (cell instanceof JComponent component) component.setBorder(hasFocus() && isCellSelected(row, column) ? BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkspaceStyle.selection()), new EmptyBorder(0, 9, 0, 9)) : new EmptyBorder(0, 10, 0, 10));
                return cell;
            }
        };
        WorkspaceStyle.designGrid(table);
        table.addFocusListener(new FocusAdapter() { public void focusGained(FocusEvent e) { table.repaint(); } public void focusLost(FocusEvent e) { table.repaint(); } });
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(30); table.setFont(table.getFont().deriveFont(13f));
        table.setGridColor(LINE);
        table.setShowVerticalLines(false); table.setShowHorizontalLines(true);
        table.setSelectionBackground(WorkspaceStyle.selection()); table.setSelectionForeground(WorkspaceStyle.selectionText());
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD, 12f)); table.getTableHeader().setPreferredSize(new Dimension(100, 31));
        for (int c = 0; c < columns.size(); c++) table.getColumnModel().getColumn(c).setPreferredWidth(155);
        return table;
    }

    private static String rowSummary(QueryResult result) {
        if (result.columns().isEmpty()) return result.updateCount() + " record(s) affected — saved to the database file.";
        return result.rows().size() + " row(s)" + (result.truncated() ? " shown — result limit reached; more rows exist." : " returned.");
    }

    private final class QueryPanel extends JPanel {
        final JTextArea editor = new JTextArea();
        final JPanel results = new JPanel(new BorderLayout());
        final JLabel resultLabel = new JLabel("Run the SQL statement to view results.");
        String savedName;
        Path sqlPath;
        boolean dirty;
        QueryPanel(String sql, String savedName) {
            super(new BorderLayout()); this.savedName = savedName;
            editor.setText(sql); editor.setFont(new Font("Menlo", Font.PLAIN, 13));
            editor.getAccessibleContext().setAccessibleName("Access SQL editor");
            editor.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { setDirty(true); }
                public void removeUpdate(DocumentEvent e) { setDirty(true); }
                public void changedUpdate(DocumentEvent e) { setDirty(true); }
            });
            editor.setTabSize(4); editor.setMargin(new Insets(14, 14, 14, 14));
            editor.setLineWrap(false);
            editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "runSQL");
            editor.getActionMap().put("runSQL", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { runQuery(); }
            });
            JPanel sqlPane = new JPanel(new BorderLayout());
            JPanel heading = new JPanel(new BorderLayout()); heading.setBackground(Color.WHITE); heading.setBorder(new EmptyBorder(10, 16, 10, 16));
            JLabel view = new JLabel("SQL View"); view.setForeground(INK); view.setFont(view.getFont().deriveFont(Font.BOLD, 12f));
            JLabel shortcuts = new JLabel("⌘Enter to run   ·   ⌘S to save SQL"); shortcuts.setForeground(MUTED); shortcuts.setFont(shortcuts.getFont().deriveFont(11f));
            heading.add(view, BorderLayout.WEST); heading.add(shortcuts, BorderLayout.EAST);
            JScrollPane editorScroll = new JScrollPane(editor); editorScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, LINE));
            JTextArea lineNumbers = new JTextArea(); lineNumbers.setEditable(false); lineNumbers.setFocusable(false); lineNumbers.setFont(editor.getFont());
            lineNumbers.setBackground(PAPER); lineNumbers.setForeground(MUTED); lineNumbers.setMargin(new Insets(14, 8, 14, 8));
            Runnable updateNumbers = () -> { StringBuilder text = new StringBuilder(); for (int line = 1; line <= editor.getLineCount(); line++) text.append(String.format("%3d", line)).append('\n'); lineNumbers.setText(text.toString()); };
            editor.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { updateNumbers.run(); } public void removeUpdate(DocumentEvent e) { updateNumbers.run(); } public void changedUpdate(DocumentEvent e) { updateNumbers.run(); }
            });
            updateNumbers.run(); editorScroll.setRowHeaderView(lineNumbers);
            sqlPane.add(heading, BorderLayout.NORTH); sqlPane.add(editorScroll, BorderLayout.CENTER);
            resultLabel.setBorder(new EmptyBorder(8, 12, 8, 12));
            results.add(resultLabel, BorderLayout.NORTH);
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sqlPane, results);
            split.setResizeWeight(0.35); split.setDividerLocation(220); split.setBorder(null);
            add(split, BorderLayout.CENTER);
        }
        void setDirty(boolean changed) {
            dirty = changed;
            int index = objects.indexOfComponent(this);
            if (index >= 0) {
                String title = objects.getTitleAt(index).replaceFirst(" \\*$", "");
                objects.setTitleAt(index, title + (changed ? " *" : ""));
                if (sqlPath != null) objects.setToolTipTextAt(index, sqlPath.toString());
            }
        }
        void showResult(QueryResult result) {
            results.removeAll(); resultLabel.setText(rowSummary(result));
            results.add(resultLabel, BorderLayout.NORTH);
            if (!result.columns().isEmpty()) results.add(new JScrollPane(grid(result.columns(), result.rows())), BorderLayout.CENTER);
            results.revalidate(); results.repaint();
        }
    }

    private final class TablePanel extends JPanel {
        final String name;
        final JTabbedPane views = new JTabbedPane();
        final JButton insert = WorkspaceStyle.command("New Record…", "add", false), delete = WorkspaceStyle.command("Delete Record", "delete", false), setNull = WorkspaceStyle.command("Set Field Null", "null", false), addField = WorkspaceStyle.command("Add Field…", "field", false), dropField = new JButton("Delete Field (unavailable)");
        TableData data;
        JTable records;
        TablePanel(String name, TableData data) {
            super(new BorderLayout()); this.name = name;
            WorkspaceStyle.tabs(views);
            insert.addActionListener(e -> insertRecord()); delete.addActionListener(e -> deleteRecord()); setNull.addActionListener(e -> setSelectedNull());
            addField.addActionListener(e -> addField());
            dropField.setToolTipText("The current UCanAccess engine does not support ALTER TABLE DROP COLUMN.");
            JPanel commands = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); commands.setBackground(PAPER);
            commands.add(insert); commands.add(delete); commands.add(setNull); commands.add(addField);
            add(commands, BorderLayout.NORTH);
            add(views, BorderLayout.CENTER); load(data);
        }
        void load(TableData data) {
            this.data = data;
            int selected = Math.max(0, views.getSelectedIndex()); views.removeAll();
            JPanel datasheet = new JPanel(new BorderLayout());
            records = grid(data.records.columns(), data.records.rows());
            records.setModel(new javax.swing.table.AbstractTableModel() {
                public int getRowCount() { return data.records.rows().size(); }
                public int getColumnCount() { return data.records.columns().size(); }
                public String getColumnName(int column) { return data.records.columns().get(column); }
                public Object getValueAt(int row, int column) { return data.records.rows().get(row).get(column); }
                public boolean isCellEditable(int row, int column) {
                    FieldInfo field = fieldAt(column);
                    return ready() && !databaseReadOnly && hasPrimaryKey() && field != null && field.writable() && !field.primaryKey() && !field.autoNumber();
                }
                public void setValueAt(Object value, int row, int column) { updateCell(row, column, value, false); }
            });
            records.setAutoCreateRowSorter(true);
            for (int column = 0; column < records.getColumnCount(); column++) records.getColumnModel().getColumn(column).setPreferredWidth(150);
            records.getSelectionModel().addListSelectionListener(e -> updateButtons());
            records.getColumnModel().getSelectionModel().addListSelectionListener(e -> updateButtons());
            datasheet.add(new JScrollPane(records), BorderLayout.CENTER);
            JLabel hint = new JLabel(" " + rowSummary(data.records) + (databaseReadOnly ? " Read only: advanced/native application objects are protected." : hasPrimaryKey() ? " Double-click a basic non-key field to edit. Primary key/AutoNumber changes use SQL View." : " Cell edits/deletion require a primary key; use SQL View for this table."));
            hint.setBorder(new EmptyBorder(8, 8, 8, 8)); hint.setFont(hint.getFont().deriveFont(11f));
            datasheet.add(hint, BorderLayout.SOUTH);
            JPanel design = new JPanel(new BorderLayout());
            List<List<Object>> schemaRows = new ArrayList<>();
            for (FieldInfo c : data.fields) schemaRows.add(Arrays.asList(c.primaryKey() ? "Primary key" : "", c.name(), c.type(), c.nullable(), c.autoNumber(), c.writable()));
            design.add(new JScrollPane(grid(List.of("Key", "Field Name", "Data Type", "Nullable", "AutoNumber", "Writable"), schemaRows)), BorderLayout.CENTER);
            JLabel readOnly = new JLabel(" Add basic fields with the command above. Field deletion/renaming, defaults, validation and advanced field editors are unavailable.");
            readOnly.setBorder(new EmptyBorder(8, 8, 8, 8)); readOnly.setFont(readOnly.getFont().deriveFont(11f));
            design.add(readOnly, BorderLayout.SOUTH);
            views.addTab("Datasheet View", datasheet); views.addTab("Design View", design);
            views.setSelectedIndex(Math.min(selected, 1));
            updateButtons();
        }
        boolean hasPrimaryKey() { return data.fields.stream().anyMatch(FieldInfo::primaryKey); }
        FieldInfo fieldAt(int column) {
            if (column < 0 || column >= data.records.columns().size()) return null;
            String name = data.records.columns().get(column);
            return data.fields.stream().filter(field -> field.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        }
        Map<String, Object> keys(int row) {
            Map<String, Object> keys = new LinkedHashMap<>();
            for (int column = 0; column < data.records.columns().size(); column++) {
                FieldInfo field = fieldAt(column);
                if (field != null && field.primaryKey()) keys.put(field.name(), data.records.rows().get(row).get(column));
            }
            return keys;
        }
        void updateButtons() {
            boolean writable = ready() && !databaseReadOnly;
            insert.setEnabled(writable);
            delete.setEnabled(writable && hasPrimaryKey() && records != null && records.getSelectedRow() >= 0);
            int column = records == null ? -1 : records.getSelectedColumn();
            FieldInfo field = column < 0 ? null : fieldAt(records.convertColumnIndexToModel(column));
            setNull.setEnabled(writable && hasPrimaryKey() && records.getSelectedRow() >= 0 && field != null && field.nullable() && field.writable() && !field.primaryKey());
            addField.setEnabled(writable); dropField.setEnabled(false);
            if (records != null) records.setEnabled(idle());
        }
        void updateCell(int row, int column, Object input, boolean explicitNull) {
            if (!ready() || databaseReadOnly) return;
            FieldInfo field = fieldAt(column);
            if (field == null || !field.writable() || field.primaryKey() || field.autoNumber()) return;
            try {
                Object value = explicitNull ? null : ValueParser.parse(field.type(), String.valueOf(input), field.nullable());
                if (Objects.equals(value, data.records.rows().get(row).get(column))) return;
                Map<String, Object> keys = keys(row);
                submit("Updating record in " + name + "…", () -> {
                    database.updateRecord(name, keys, field.name(), value); database.flush(); return loadTable(name);
                }, updated -> { load(updated); status.setText("Record updated — saved to the database file."); });
            } catch (IllegalArgumentException invalid) { error("Field value", invalid); }
        }
        void setSelectedNull() {
            if (!ready() || records.getSelectedRow() < 0 || records.getSelectedColumn() < 0) return;
            updateCell(records.convertRowIndexToModel(records.getSelectedRow()), records.convertColumnIndexToModel(records.getSelectedColumn()), null, true);
        }
        void insertRecord() {
            if (!ready() || databaseReadOnly) return;
            List<FieldInfo> fields = data.fields.stream().filter(field -> !field.autoNumber() && (field.writable() || field.primaryKey())).toList();
            JPanel panel = new JPanel(new GridLayout(0, 4, 7, 7));
            panel.add(new JLabel("Include")); panel.add(new JLabel("Field (type)")); panel.add(new JLabel("Value")); panel.add(new JLabel("NULL"));
            List<JCheckBox> include = new ArrayList<>(), nulls = new ArrayList<>(); List<JTextField> values = new ArrayList<>();
            for (FieldInfo field : fields) {
                JCheckBox included = new JCheckBox("", field.primaryKey() || !field.nullable()); include.add(included);
                JTextField value = new JTextField(20); values.add(value);
                JCheckBox isNull = new JCheckBox(); isNull.setEnabled(field.nullable()); nulls.add(isNull);
                panel.add(included); panel.add(new JLabel(field.name() + " (" + field.type() + ")")); panel.add(value); panel.add(isNull);
            }
            while (JOptionPane.showConfirmDialog(AccessForMacApp.this, new JScrollPane(panel), "New Record — " + name, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                try {
                    Map<String, Object> valuesToInsert = new LinkedHashMap<>();
                    for (int index = 0; index < fields.size(); index++) if (include.get(index).isSelected()) {
                        FieldInfo field = fields.get(index);
                        valuesToInsert.put(field.name(), nulls.get(index).isSelected() ? null : ValueParser.parse(field.type(), values.get(index).getText(), field.nullable()));
                    }
                    submit("Inserting record into " + name + "…", () -> {
                        database.insertRecord(name, valuesToInsert); database.flush(); return loadTable(name);
                    }, inserted -> { load(inserted); status.setText("Record inserted — saved to the database file."); });
                    return;
                } catch (IllegalArgumentException invalid) { error("Record values", invalid); }
            }
        }
        void deleteRecord() {
            if (!ready() || databaseReadOnly || records.getSelectedRow() < 0 || !hasPrimaryKey()) return;
            Map<String, Object> keys = keys(records.convertRowIndexToModel(records.getSelectedRow()));
            if (JOptionPane.showConfirmDialog(AccessForMacApp.this, "Delete the selected record from this database?\n" + keys + (databaseDirect ? "\n\nThis changes the open file." : "\n\nThe original source remains separate."), "Delete Record", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            submit("Deleting record from " + name + "…", () -> {
                database.deleteRecord(name, keys); database.flush(); return loadTable(name);
            }, deleted -> { load(deleted); status.setText("Record deleted — saved to the database file."); });
        }
        void addField() {
            if (!ready() || databaseReadOnly) return;
            JTextField name = new JTextField(24); JComboBox<String> type = new JComboBox<>(Arrays.copyOf(TableDesignDialog.TYPES, TableDesignDialog.TYPES.length - 1));
            JCheckBox required = new JCheckBox("Required (NOT NULL)");
            JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8)); panel.add(new JLabel("Field Name")); panel.add(name); panel.add(new JLabel("Data Type")); panel.add(type); panel.add(required);
            if (JOptionPane.showConfirmDialog(AccessForMacApp.this, panel, "Add Field — " + this.name, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            try {
                String field = TableDesignDialog.checkedName(name.getText());
                alter("ALTER TABLE " + identifier(this.name) + " ADD COLUMN " + identifier(field) + " " + type.getSelectedItem() + (required.isSelected() ? " NOT NULL" : "") + ";", "Field added");
            } catch (IllegalArgumentException invalid) { error("Add field", invalid); }
        }
        void alter(String sql, String message) {
            submit("Changing table design…", () -> {
                database.execute(sql); database.flush(); return loadTable(name);
            }, changed -> { load(changed); status.setText(message + " — saved to the database file."); });
        }
    }
}
