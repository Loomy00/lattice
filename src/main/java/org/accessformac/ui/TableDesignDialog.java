package org.accessformac.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Basic native table creation. Existing-table alteration is handled separately. */
final class TableDesignDialog extends JDialog {
    static final String[] TYPES = {"TEXT(255)", "LONG", "DOUBLE", "CURRENCY", "DATETIME", "YESNO", "COUNTER"};
    record FieldDefinition(String name, String type, boolean required, boolean primaryKey) {}
    private final JTextField name = new JTextField("NewTable", 28);
    private final DefaultTableModel fields = new DefaultTableModel(new Object[]{"Field Name", "Data Type", "Required", "Primary Key"}, 0) {
        @Override public Class<?> getColumnClass(int column) { return column >= 2 ? Boolean.class : String.class; }
    };
    private final JTable grid = new JTable(fields);
    private String sql;

    static String show(Frame owner) {
        TableDesignDialog dialog = new TableDesignDialog(owner);
        dialog.setVisible(true);
        return dialog.sql;
    }

    private TableDesignDialog(Frame owner) {
        super(owner, "Create Table — Design View", true);
        JPanel content = new JPanel(new BorderLayout(0, 12)); content.setBackground(WorkspaceStyle.CHROME);
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16)); setContentPane(content);
        JPanel heading = new JPanel(new BorderLayout(0, 10)); heading.setOpaque(false);
        JLabel title = new JLabel("Create a table", new WorkspaceStyle.Glyph("design", 20, WorkspaceStyle.ACCENT), SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f)); title.setForeground(WorkspaceStyle.INK); heading.add(title, BorderLayout.NORTH);
        JPanel tableName = new JPanel(new BorderLayout(12, 0)); tableName.setOpaque(false);
        JLabel label = new JLabel("Table name"); label.setLabelFor(name); tableName.add(label, BorderLayout.WEST); tableName.add(name, BorderLayout.CENTER);
        name.getAccessibleContext().setAccessibleName("New table name"); heading.add(tableName, BorderLayout.CENTER);
        add(heading, BorderLayout.NORTH);
        fields.addRow(new Object[]{"ID", "COUNTER", true, true});
        fields.addRow(new Object[]{"Name", "TEXT(255)", false, false});
        WorkspaceStyle.designGrid(grid); grid.getAccessibleContext().setAccessibleName("New table field definitions");
        grid.getColumnModel().getColumn(0).setPreferredWidth(230); grid.getColumnModel().getColumn(1).setPreferredWidth(170);
        grid.getColumnModel().getColumn(2).setPreferredWidth(85); grid.getColumnModel().getColumn(3).setPreferredWidth(95);
        grid.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(TYPES)));
        JScrollPane fieldScroll = new JScrollPane(grid); fieldScroll.setBorder(BorderFactory.createLineBorder(WorkspaceStyle.LINE));
        add(fieldScroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.setOpaque(false);
        JButton add = WorkspaceStyle.command("Add Field", "field", false), remove = WorkspaceStyle.command("Remove Field", "delete", false), create = WorkspaceStyle.command("Create Table", "table", true), cancel = WorkspaceStyle.command("Cancel", "close", false);
        remove.setEnabled(false); grid.getSelectionModel().addListSelectionListener(e -> remove.setEnabled(grid.getSelectedRow() >= 0));
        add.addActionListener(e -> fields.addRow(new Object[]{"Field" + (fields.getRowCount() + 1), "TEXT(255)", false, false}));
        remove.addActionListener(e -> { if (grid.getSelectedRow() >= 0) fields.removeRow(grid.getSelectedRow()); });
        create.addActionListener(e -> accept()); cancel.addActionListener(e -> dispose());
        actions.add(add); actions.add(remove); actions.add(cancel); actions.add(create);
        JPanel footer = new JPanel(new BorderLayout(0, 8)); footer.setOpaque(false);
        JLabel hint = new JLabel("<html>Define basic fields and a primary key.<br>Advanced types, defaults and validation are not configured here.</html>");
        hint.setForeground(WorkspaceStyle.MUTED); hint.setFont(hint.getFont().deriveFont(12f)); footer.add(hint, BorderLayout.NORTH); footer.add(actions, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(create);
        WorkspaceStyle.dismissOnEscape(this); setMinimumSize(new Dimension(680, 400));
        setSize(790, 470); setLocationRelativeTo(owner);
    }

    private void accept() {
        if (grid.isEditing() && !grid.getCellEditor().stopCellEditing()) return;
        try {
            List<FieldDefinition> design = new ArrayList<>();
            for (int row = 0; row < fields.getRowCount(); row++) {
                design.add(new FieldDefinition(String.valueOf(fields.getValueAt(row, 0)), String.valueOf(fields.getValueAt(row, 1)), Boolean.TRUE.equals(fields.getValueAt(row, 2)), Boolean.TRUE.equals(fields.getValueAt(row, 3))));
            }
            sql = buildSql(name.getText(), design);
            dispose();
        } catch (IllegalArgumentException invalid) { JOptionPane.showMessageDialog(this, invalid.getMessage(), "Table Design", JOptionPane.ERROR_MESSAGE); }
    }

    static String buildSql(String tableName, List<FieldDefinition> fields) {
            String table = checkedName(tableName);
            if (fields.isEmpty()) throw new IllegalArgumentException("Add at least one field.");
            Set<String> names = new HashSet<>(); List<String> definitions = new ArrayList<>(), keys = new ArrayList<>(); int counters = 0;
            for (FieldDefinition definition : fields) {
                String field = checkedName(definition.name());
                if (!names.add(field.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Field names must be unique.");
                String type = definition.type();
                if (!Arrays.asList(TYPES).contains(type)) throw new IllegalArgumentException("Choose a supported data type.");
                if (type.equals("COUNTER")) counters++;
                boolean primary = definition.primaryKey();
                definitions.add(ValueParser.identifier(field) + " " + type + (definition.required() || primary ? " NOT NULL" : ""));
                if (primary) keys.add(ValueParser.identifier(field));
            }
            if (counters > 1) throw new IllegalArgumentException("Only one AutoNumber (COUNTER) field is allowed.");
            if (!keys.isEmpty()) definitions.add("PRIMARY KEY (" + String.join(", ", keys) + ")");
            return "CREATE TABLE " + ValueParser.identifier(table) + " (\n  " + String.join(",\n  ", definitions) + "\n);";
    }

    static String checkedName(String input) {
        String name = input.trim();
        if (name.isEmpty() || name.length() > 64 || name.contains("\u0000") || name.contains(".") || name.contains("!") || name.contains("[") || name.contains("]")) throw new IllegalArgumentException("Use a name of 1–64 characters without ., !, [ or ].");
        return name;
    }
}
