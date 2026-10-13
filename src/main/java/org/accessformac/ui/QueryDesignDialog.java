package org.accessformac.ui;

import org.accessformac.db.AccessDatabase.ColumnInfo;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Functional two-table SELECT designer; its output is ordinary editable Access SQL. */
final class QueryDesignDialog extends JDialog {
    record Projection(String table, ColumnInfo field, String aggregate, String sort) {}
    record Join(String type, String table, String leftColumn, String rightColumn) {}
    record Filter(String table, ColumnInfo field, String operator, String value) {}
    private record Field(String table, ColumnInfo column) {
        String sql() { return ValueParser.identifier(table) + "." + ValueParser.identifier(column.name()); }
        @Override public String toString() { return table + "." + column.name(); }
    }
    private final Map<String, List<ColumnInfo>> schema;
    private final JComboBox<String> from, joined;
    private final JComboBox<Field> leftKey = new JComboBox<>(), rightKey = new JComboBox<>(), filterField = new JComboBox<>();
    private final JComboBox<String> joinType = new JComboBox<>(new String[]{"INNER JOIN", "LEFT JOIN"});
    private final JCheckBox distinct = new JCheckBox("Unique values (DISTINCT)"), filter = new JCheckBox("Filter");
    private final JComboBox<String> operator = new JComboBox<>(new String[]{"=", "<>", ">", ">=", "<", "<=", "LIKE", "IS NULL", "IS NOT NULL"});
    private final JTextField filterValue = new JTextField(22);
    private final JTextArea preview = new JTextArea(7, 65);
    private final List<Field> available = new ArrayList<>();
    private final DefaultTableModel selection = new DefaultTableModel(new Object[]{"Show", "Field", "Aggregate", "Sort"}, 0) {
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column != 1; }
    };
    private final JTable grid = new JTable(selection);
    private String output;
    private boolean rebuilding;

    static String show(Frame owner, Map<String, List<ColumnInfo>> schema) {
        QueryDesignDialog dialog = new QueryDesignDialog(owner, schema);
        dialog.setVisible(true);
        return dialog.output;
    }

    private QueryDesignDialog(Frame owner, Map<String, List<ColumnInfo>> schema) {
        super(owner, "Query Design — SELECT Builder", true);
        this.schema = schema;
        from = new JComboBox<>(schema.keySet().toArray(String[]::new));
        List<String> joinChoices = new ArrayList<>(); joinChoices.add("(none)"); joinChoices.addAll(schema.keySet());
        joined = new JComboBox<>(joinChoices.toArray(String[]::new));
        JPanel content = new JPanel(new BorderLayout(0, 12)); content.setBackground(WorkspaceStyle.CHROME);
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16)); setContentPane(content);
        JPanel sources = new JPanel(new GridLayout(0, 1, 4, 4)); sources.setOpaque(false);
        JPanel source = new JPanel(new FlowLayout(FlowLayout.LEFT));
        source.setOpaque(false); joinType.setToolTipText("Choose inner join or left join");
        JLabel sourceLabel = new JLabel("Source table"); sourceLabel.setLabelFor(from); source.add(sourceLabel); source.add(from); source.add(distinct);
        from.getAccessibleContext().setAccessibleName("Source table"); joined.getAccessibleContext().setAccessibleName("Optional joined table");
        JPanel join = new JPanel(new FlowLayout(FlowLayout.LEFT));
        join.setOpaque(false); join.add(new JLabel("Join table")); join.add(joined); join.add(joinType); join.add(new JLabel("On")); join.add(leftKey); join.add(new JLabel("=")); join.add(rightKey);
        leftKey.getAccessibleContext().setAccessibleName("Source join field"); rightKey.getAccessibleContext().setAccessibleName("Joined table field");
        JPanel condition = new JPanel(new FlowLayout(FlowLayout.LEFT));
        condition.setOpaque(false); condition.add(filter); condition.add(filterField); condition.add(operator); condition.add(filterValue);
        filterField.getAccessibleContext().setAccessibleName("Filter field"); operator.getAccessibleContext().setAccessibleName("Filter comparison"); filterValue.getAccessibleContext().setAccessibleName("Filter value");
        filterValue.putClientProperty("JTextField.placeholderText", "Value");
        sources.add(source); sources.add(join); sources.add(condition);
        JPanel heading = new JPanel(new BorderLayout(0, 8)); heading.setOpaque(false);
        JLabel title = new JLabel("Build a SELECT query", new WorkspaceStyle.Glyph("builder", 20, WorkspaceStyle.ACCENT), SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f)); title.setForeground(WorkspaceStyle.INK); heading.add(title, BorderLayout.NORTH); heading.add(sources, BorderLayout.CENTER);
        add(heading, BorderLayout.NORTH);
        WorkspaceStyle.designGrid(grid); grid.getAccessibleContext().setAccessibleName("Query output fields, aggregates and sorting");
        grid.getColumnModel().getColumn(0).setPreferredWidth(60);
        grid.getColumnModel().getColumn(1).setPreferredWidth(310);
        grid.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"(none)", "COUNT", "SUM", "AVG", "MIN", "MAX"})));
        grid.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"(none)", "Ascending", "Descending"})));
        preview.setEditable(false); preview.setFont(new Font("Menlo", Font.PLAIN, 13)); preview.setMargin(new Insets(10, 10, 10, 10));
        preview.getAccessibleContext().setAccessibleName("Generated SQL preview");
        JScrollPane fieldsScroll = new JScrollPane(grid); fieldsScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, WorkspaceStyle.LINE));
        JScrollPane previewScroll = new JScrollPane(preview); previewScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, WorkspaceStyle.LINE));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, WorkspaceStyle.section("Output fields", fieldsScroll), WorkspaceStyle.section("SQL preview", previewScroll));
        split.setResizeWeight(.65); split.setDividerSize(5); split.setBorder(BorderFactory.createLineBorder(WorkspaceStyle.LINE)); split.setDividerLocation(260); add(split, BorderLayout.CENTER);
        JButton generate = WorkspaceStyle.command("Open in SQL View", "query", true), cancel = WorkspaceStyle.command("Cancel", "close", false);
        JPanel footer = new JPanel(new BorderLayout(0, 8)); footer.setOpaque(false);
        JLabel hint = new JLabel("<html>Select fields, aggregates and sorting.<br>Supports one filter and one equality join. Use SQL View for advanced queries.</html>");
        hint.setForeground(WorkspaceStyle.MUTED); hint.setFont(hint.getFont().deriveFont(12f)); footer.add(hint, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.setOpaque(false); buttons.add(cancel); buttons.add(generate); footer.add(buttons, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(generate); WorkspaceStyle.dismissOnEscape(this);
        from.addActionListener(e -> rebuildFields()); joined.addActionListener(e -> rebuildFields());
        distinct.addActionListener(e -> refreshPreview()); filter.addActionListener(e -> refreshPreview()); operator.addActionListener(e -> refreshPreview());
        joinType.addActionListener(e -> refreshPreview()); leftKey.addActionListener(e -> refreshPreview()); rightKey.addActionListener(e -> refreshPreview()); filterField.addActionListener(e -> refreshPreview());
        selection.addTableModelListener(e -> { if (!rebuilding) refreshPreview(); });
        filterValue.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshPreview(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshPreview(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshPreview(); }
        });
        cancel.addActionListener(e -> dispose());
        generate.addActionListener(e -> {
            if (grid.isEditing() && !grid.getCellEditor().stopCellEditing()) return;
            try { output = sql(); dispose(); }
            catch (IllegalArgumentException invalid) { JOptionPane.showMessageDialog(this, invalid.getMessage(), "Query Design", JOptionPane.ERROR_MESSAGE); }
        });
        rebuildFields(); setMinimumSize(new Dimension(900, 610)); setSize(1050, 720); setLocationRelativeTo(owner);
    }

    private void rebuildFields() {
        rebuilding = true;
        available.clear(); selection.setRowCount(0); leftKey.removeAllItems(); rightKey.removeAllItems(); filterField.removeAllItems();
        String base = (String) from.getSelectedItem(), second = (String) joined.getSelectedItem();
        if (base != null) for (ColumnInfo column : schema.get(base)) {
            Field field = new Field(base, column); available.add(field); leftKey.addItem(field); filterField.addItem(field);
        }
        boolean join = second != null && !second.equals("(none)");
        if (join) for (ColumnInfo column : schema.get(second)) {
            Field field = new Field(second, column); available.add(field); rightKey.addItem(field); filterField.addItem(field);
        }
        for (Field field : available) selection.addRow(new Object[]{true, field.toString(), "(none)", "(none)"});
        leftKey.setEnabled(join); rightKey.setEnabled(join); joinType.setEnabled(join);
        rebuilding = false; refreshPreview();
    }

    private void refreshPreview() {
        if (rebuilding) return;
        filterField.setEnabled(filter.isSelected()); operator.setEnabled(filter.isSelected());
        filterValue.setEnabled(filter.isSelected() && !String.valueOf(operator.getSelectedItem()).startsWith("IS "));
        try { preview.setText(sql()); preview.setForeground(WorkspaceStyle.INK); }
        catch (IllegalArgumentException invalid) { preview.setText("Design needs attention: " + invalid.getMessage()); preview.setForeground(WorkspaceStyle.ACCENT); }
    }

    private String sql() {
        String base = (String) from.getSelectedItem(), second = (String) joined.getSelectedItem();
        List<Projection> projections = new ArrayList<>();
        for (int row = 0; row < selection.getRowCount(); row++) if (Boolean.TRUE.equals(selection.getValueAt(row, 0))) {
            Field field = available.get(row);
            projections.add(new Projection(field.table(), field.column(), String.valueOf(selection.getValueAt(row, 2)), String.valueOf(selection.getValueAt(row, 3))));
        }
        Join join = null;
        if (second != null && !second.equals("(none)")) {
            Field left = (Field) leftKey.getSelectedItem(), right = (Field) rightKey.getSelectedItem();
            if (left == null || right == null) throw new IllegalArgumentException("Choose a field on both sides of the join.");
            join = new Join((String) joinType.getSelectedItem(), second, left.column().name(), right.column().name());
        }
        Filter condition = null;
        if (filter.isSelected()) {
            Field field = (Field) filterField.getSelectedItem(); if (field == null) throw new IllegalArgumentException("Choose a filter field.");
            condition = new Filter(field.table(), field.column(), (String) operator.getSelectedItem(), filterValue.getText());
        }
        return buildSql(base, projections, distinct.isSelected(), join, condition);
    }

    static String buildSql(String base, List<Projection> projections, boolean distinct, Join join, Filter filter) {
        if (base == null) throw new IllegalArgumentException("Choose a source table.");
        if (join != null && base.equals(join.table())) throw new IllegalArgumentException("Self joins require aliases; use SQL View for a self join.");
        List<String> fields = new ArrayList<>(), grouped = new ArrayList<>(), sorted = new ArrayList<>(); boolean aggregates = false;
        for (Projection field : projections) {
            String expression = ValueParser.identifier(field.table()) + "." + ValueParser.identifier(field.field().name()), aggregate = field.aggregate();
            if (!List.of("(none)", "COUNT", "SUM", "AVG", "MIN", "MAX").contains(aggregate)) throw new IllegalArgumentException("Choose a supported aggregate.");
            if (!aggregate.equals("(none)")) { expression = aggregate + "(" + expression + ")"; aggregates = true; }
            else grouped.add(expression);
            fields.add(expression);
            String sort = field.sort();
            if (!List.of("(none)", "Ascending", "Descending").contains(sort)) throw new IllegalArgumentException("Choose a supported sort direction.");
            if (!sort.equals("(none)")) sorted.add(expression + (sort.equals("Ascending") ? " ASC" : " DESC"));
        }
        if (fields.isEmpty()) throw new IllegalArgumentException("Select at least one field to show.");
        StringBuilder sql = new StringBuilder("SELECT ").append(distinct ? "DISTINCT " : "").append(String.join(", ", fields)).append("\nFROM ").append(ValueParser.identifier(base));
        if (join != null) {
            if (!List.of("INNER JOIN", "LEFT JOIN").contains(join.type())) throw new IllegalArgumentException("Choose a supported join type.");
            sql.append("\n").append(join.type()).append(" ").append(ValueParser.identifier(join.table())).append(" ON ").append(ValueParser.identifier(base)).append('.').append(ValueParser.identifier(join.leftColumn())).append(" = ").append(ValueParser.identifier(join.table())).append('.').append(ValueParser.identifier(join.rightColumn()));
        }
        if (filter != null) {
            String op = filter.operator();
            if (!List.of("=", "<>", ">", ">=", "<", "<=", "LIKE", "IS NULL", "IS NOT NULL").contains(op)) throw new IllegalArgumentException("Choose a supported filter operator.");
            sql.append("\nWHERE ").append(ValueParser.identifier(filter.table())).append('.').append(ValueParser.identifier(filter.field().name())).append(" ").append(op);
            if (!op.startsWith("IS ")) sql.append(" ").append(ValueParser.literal(filter.field().type(), filter.value()));
        }
        if (aggregates && !grouped.isEmpty()) sql.append("\nGROUP BY ").append(String.join(", ", grouped));
        if (!sorted.isEmpty()) sql.append("\nORDER BY ").append(String.join(", ", sorted));
        return sql.append(';').toString();
    }
}
