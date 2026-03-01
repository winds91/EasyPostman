package com.laker.postman.common.component.table;

import com.formdev.flatlaf.FlatClientProperties;
import com.laker.postman.common.component.SearchTextField;
import com.laker.postman.util.FontsUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.*;
import java.util.Arrays;
import java.util.List;

/**
 * 增强型通用表格组件
 * <ul>
 *   <li>自动铺满视口宽度（内容窄时拉伸，超宽时出横向滚动条）</li>
 *   <li>顶部工具栏：实时搜索过滤 + 列多选过滤 + 总行数显示</li>
 *   <li>分页：每页条数下拉 20/50/100/All、翻页、页码跳转</li>
 *   <li>列标题单击排序（升/降，带箭头指示）</li>
 *   <li>行悬停高亮 + 斑马纹</li>
 *   <li>右键菜单：复制单元格、复制整行</li>
 *   <li>支持运行时动态重置列结构（{@link #resetAndSetData}）</li>
 * </ul>
 */
@Slf4j
public class EnhancedTablePanel extends JPanel {

    // ── 列名（可变，支持动态重置）───────────────────────────────────────────
    private String[] currentColumns;

    // ── 数据 ──────────────────────────────────────────────────────────────
    private final List<Object[]> allRows      = new ArrayList<>();
    private final List<Object[]> filteredRows = new ArrayList<>();

    // ── 分页 ──────────────────────────────────────────────────────────────
    private int     pageSize    = 20;
    private int     currentPage = 0;

    // ── 排序 ──────────────────────────────────────────────────────────────
    private int     sortCol = -1;
    private boolean sortAsc = true;

    // ── 过滤 ─────────────────────────────────────────────────────────────
    private String      filterText    = "";
    /** null = 搜索全列；否则只在这些列索引中搜索 */
    private Set<Integer> filterCols   = null;

    // ── Hover ─────────────────────────────────────────────────────────────
    private int hoveredRow = -1;

    // ── 分页大小选项 ───────────────────────────────────────────────────────
    private static final int[]    PAGE_SIZES  = {20, 50, 100, 0};
    private static final String[] SIZE_LABELS = {"20", "50", "100", "All"};
    private static final String   ROWS_SUFFIX = " rows";

    // ── Swing 组件 ────────────────────────────────────────────────────────
    @Getter
    private JTable            table;
    private DefaultTableModel tableModel;
    private SearchTextField   searchField;
    private JButton           colFilterBtn;     // 列选择下拉按钮
    private JLabel            hintLabel;
    private JLabel            pageInfoLabel;
    private JButton           btnPrev;
    private JButton           btnNext;
    private JComboBox<String> pageSizeCombo;
    private JTextField        pageJumpField;

    // ─────────────────────────────────────────────────────────────────────

    public EnhancedTablePanel(String[] columns) {
        this.currentColumns = columns.clone();
        buildUI();
    }

    public static EnhancedTablePanel create(String... cols) {
        return new EnhancedTablePanel(cols);
    }

    // ═══════════════════ Public API ══════════════════════════════════════

    /**
     * 动态重置列结构并替换全量数据。
     */
    public void resetAndSetData(String[] newColumns, List<Object[]> rows) {
        boolean colsChanged = !Arrays.equals(currentColumns, newColumns);
        this.currentColumns = newColumns.clone();
        this.sortCol        = -1;
        this.currentPage    = 0;
        this.filterText     = "";
        searchField.setText("");

        if (colsChanged) {
            // 列结构变化：清除超出新列数的无效索引；若清后覆盖全部列则置 null（全选）
            if (filterCols != null && !filterCols.isEmpty()) {
                filterCols.removeIf(ci -> ci >= newColumns.length);
                if (filterCols.size() >= newColumns.length) filterCols = null;
            }
            updateColFilterBtnLabel();  // 同步按钮文字 + 搜索框 placeholder
        }

        // 重置 tableModel 列
        tableModel.setRowCount(0);
        tableModel.setColumnCount(0);
        for (String col : currentColumns) tableModel.addColumn(col);

        allRows.clear();
        if (rows != null) allRows.addAll(rows);

        hintLabel.setText(allRows.size() + ROWS_SUFFIX);
        applyFilterAndSort();
    }

    /** 保持列不变，仅替换数据 */
    public void setData(List<Object[]> rows) {
        allRows.clear();
        if (rows != null) allRows.addAll(rows);
        currentPage = 0;
        sortCol     = -1;
        hintLabel.setText(allRows.size() + ROWS_SUFFIX);
        applyFilterAndSort();
    }

    /** 追加一行 */
    public void appendRow(Object[] row) {
        allRows.add(row);
        hintLabel.setText(allRows.size() + ROWS_SUFFIX);
        applyFilterAndSort();
    }

    /** 清空数据（保持列结构） */
    public void clearData() {
        allRows.clear();
        filteredRows.clear();
        currentPage = 0;
        sortCol     = -1;
        hintLabel.setText("0" + ROWS_SUFFIX);
        tableModel.setRowCount(0);
        updatePaginationControls();
    }

    public void setPageSize(int size) {
        pageSize    = size;
        currentPage = 0;
        applyFilterAndSort();
    }

    /** 返回全量数据行数（不受分页/过滤影响） */
    public int getTotalRowCount() {
        return allRows.size();
    }

    /** 返回过滤后数据行数 */
    public int getFilteredRowCount() {
        return filteredRows.size();
    }

    public Object[] getSelectedRowData() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return new Object[0];
        int idx = getPageOffset() + viewRow;
        if (idx < 0 || idx >= filteredRows.size()) return new Object[0];
        return filteredRows.get(idx);
    }

    // ═══════════════════ UI Build ════════════════════════════════════════

    private void buildUI() {
        setLayout(new BorderLayout(0, 0));
        setBorder(BorderFactory.createEmptyBorder());
        add(buildToolBar(),    BorderLayout.NORTH);
        add(buildTableArea(),  BorderLayout.CENTER);
        add(buildPagination(), BorderLayout.SOUTH);
    }

    // ── 工具栏：搜索框 + 列筛选按钮 + 行数提示 ────────────────────────────
    private JPanel buildToolBar() {
        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0,
                        UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        searchField = new SearchTextField();
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, null);
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "搜索（全列）...");
        searchField.setPreferredSize(new Dimension(200, 28));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { onSearch(); }
            public void removeUpdate(DocumentEvent e)  { onSearch(); }
            public void changedUpdate(DocumentEvent e) { onSearch(); }
        });

        // 列筛选按钮（下拉多选弹窗）
        colFilterBtn = new JButton("列筛选 ▾");
        colFilterBtn.setFont(colFilterBtn.getFont().deriveFont(Font.PLAIN, 11f));
        colFilterBtn.setFocusPainted(false);
        colFilterBtn.setPreferredSize(new Dimension(80, 28));
        colFilterBtn.addActionListener(e -> showColFilterPopup());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        left.add(searchField);
        left.add(colFilterBtn);

        hintLabel = new JLabel("0" + ROWS_SUFFIX);
        hintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 11f));

        bar.add(left,       BorderLayout.WEST);
        bar.add(hintLabel,  BorderLayout.EAST);
        return bar;
    }

    // ── 列筛选 popup（持久化，避免每次重建导致状态丢失）──────────────────────
    private JPopupMenu colFilterPopup = null;
    private JCheckBox[] colFilterBoxes = null;
    private String[]    colFilterPopupColumns = null;  // 记录 popup 对应的列，列变化时重建

    /**
     * 弹出列多选下拉面板（持久化实例，关闭再打开状态保留）
     */
    private void showColFilterPopup() {
        if (currentColumns.length == 0) return;

        // 列结构变化时重建 popup
        if (colFilterPopup == null || !Arrays.equals(colFilterPopupColumns, currentColumns)) {
            colFilterPopup = buildColFilterPopup();
            colFilterPopupColumns = currentColumns.clone();
        } else {
            // 列未变化，只同步 filterCols → checkbox 勾选状态
            syncCheckboxesToFilterCols();
        }

        colFilterPopup.show(colFilterBtn, 0, colFilterBtn.getHeight());
    }

    /** 构建列筛选 popup（只在列结构变化时调用）*/
    private JPopupMenu buildColFilterPopup() {
        JPopupMenu popup = new JPopupMenu();
        popup.setLayout(new BorderLayout(0, 0));

        JPanel outer = new JPanel(new BorderLayout(0, 0));
        outer.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        // 顶部按钮行
        JButton btnAll  = makeSmallBtn("全选");
        JButton btnNone = makeSmallBtn("全不选");
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        topRow.setOpaque(false);
        topRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        topRow.add(btnAll);
        topRow.add(btnNone);
        outer.add(topRow, BorderLayout.NORTH);

        // 中间复选框列表
        JPanel checkPanel = new JPanel(new GridLayout(0, 1, 0, 1));
        checkPanel.setOpaque(false);
        colFilterBoxes = new JCheckBox[currentColumns.length];
        for (int i = 0; i < currentColumns.length; i++) {
            // 初始勾选状态：null=全选，否则按 filterCols 判断
            boolean checked = (filterCols == null || filterCols.contains(i));
            colFilterBoxes[i] = new JCheckBox(currentColumns[i], checked);
            colFilterBoxes[i].setFont(colFilterBoxes[i].getFont().deriveFont(Font.PLAIN, 12f));
            colFilterBoxes[i].setOpaque(false);
            checkPanel.add(colFilterBoxes[i]);
        }
        JScrollPane checkScroll = new JScrollPane(checkPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        checkScroll.setBorder(BorderFactory.createEmptyBorder());
        checkScroll.setPreferredSize(new Dimension(180, Math.min(currentColumns.length * 24, 240)));
        outer.add(checkScroll, BorderLayout.CENTER);

        // 底部确定按钮
        JButton btnOk = makeSmallBtn("确定");
        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        bottomRow.setOpaque(false);
        bottomRow.add(btnOk);
        outer.add(bottomRow, BorderLayout.SOUTH);

        // 事件
        btnAll.addActionListener(e -> {
            for (JCheckBox cb : colFilterBoxes) cb.setSelected(true);
        });
        btnNone.addActionListener(e -> {
            for (JCheckBox cb : colFilterBoxes) cb.setSelected(false);
        });
        btnOk.addActionListener(e -> applyColFilter(popup));

        popup.add(outer, BorderLayout.CENTER);
        return popup;
    }

    /** 把当前 filterCols 状态同步到已有的 checkbox（popup 未重建时调用）*/
    private void syncCheckboxesToFilterCols() {
        if (colFilterBoxes == null) return;
        for (int i = 0; i < colFilterBoxes.length; i++) {
            colFilterBoxes[i].setSelected(filterCols == null || filterCols.contains(i));
        }
    }

    /** 确定按钮：读取 checkbox 状态 → 保存 filterCols → 关闭 popup → 重新过滤 */
    private void applyColFilter(JPopupMenu popup) {
        if (colFilterBoxes == null) return;
        Set<Integer> sel = new HashSet<>();
        boolean allChecked = true;
        for (int i = 0; i < colFilterBoxes.length; i++) {
            if (colFilterBoxes[i].isSelected()) sel.add(i);
            else allChecked = false;
        }
        // null  = 全选（搜索所有列）
        // 非空 Set = 搜索指定列
        // 空 Set   = 全不选（不搜索任何列，过滤时永远不匹配）
        filterCols = allChecked ? null : sel;   // 注意：sel 为空时也保存空 Set，不转 null
        updateColFilterBtnLabel();
        currentPage = 0;
        applyFilterAndSort();
        popup.setVisible(false);
    }

    private static JButton makeSmallBtn(String text) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.PLAIN, 11f));
        b.setFocusPainted(false);
        return b;
    }

    /** 更新列筛选按钮文字 + 搜索框 placeholder，让用户直观感知两者联动 */
    private void updateColFilterBtnLabel() {
        if (filterCols == null) {
            colFilterBtn.setText("列筛选 ▾");
            searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "搜索（全列）...");
        } else if (filterCols.isEmpty()) {
            colFilterBtn.setText("列筛选(0) ▾");
            searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "搜索（无列，全不选）...");
        } else {
            colFilterBtn.setText("列筛选(" + filterCols.size() + ") ▾");
            // 拼出列名列表显示在 placeholder
            StringBuilder sb = new StringBuilder("搜索（");
            int count = 0;
            for (int ci : filterCols) {
                if (ci < currentColumns.length) {
                    if (count > 0) sb.append(", ");
                    sb.append(currentColumns[ci]);
                    count++;
                    if (count >= 3) { sb.append("..."); break; }
                }
            }
            sb.append("）...");
            searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, sb.toString());
        }
        searchField.repaint();
    }

    // ── 表格 ─────────────────────────────────────────────────────────────
    private JScrollPane buildTableArea() {
        tableModel = new DefaultTableModel(currentColumns, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        table = new JTable(tableModel) {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return getPreferredSize().width < getParent().getWidth();
            }
        };

        table.setFillsViewportHeight(true);
        table.setRowHeight(26);
        table.setFont(FontsUtil.getDefaultFontWithOffset(Font.PLAIN, -1));
        table.getTableHeader().setFont(FontsUtil.getDefaultFontWithOffset(Font.BOLD, -1));
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, new HoverRenderer());

        // 列标题排序
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int col = table.columnAtPoint(e.getPoint());
                if (col < 0) return;
                if (sortCol == col) sortAsc = !sortAsc;
                else { sortCol = col; sortAsc = true; }
                applyFilterAndSort();
                updateSortHeaders();
            }
        });

        // 悬停 + 右键
        table.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row != hoveredRow) { hoveredRow = row; table.repaint(); }
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) {
                if (hoveredRow != -1) { hoveredRow = -1; table.repaint(); }
            }
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) table.setRowSelectionInterval(row, row);
                    showContextMenu(e);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    // ── 分页栏 ────────────────────────────────────────────────────────────
    private JPanel buildPagination() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 3));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));

        pageSizeCombo = new JComboBox<>(SIZE_LABELS);
        pageSizeCombo.setPreferredSize(new Dimension(65, 24));
        pageSizeCombo.setFont(pageSizeCombo.getFont().deriveFont(Font.PLAIN, 11f));
        pageSizeCombo.addActionListener(e -> {
            pageSize    = PAGE_SIZES[pageSizeCombo.getSelectedIndex()];
            currentPage = 0;
            refreshView();
        });

        btnPrev = navBtn("‹");
        btnNext = navBtn("›");
        btnPrev.addActionListener(e -> { if (currentPage > 0)             { currentPage--; refreshView(); } });
        btnNext.addActionListener(e -> { if (currentPage < totalPages()-1) { currentPage++; refreshView(); } });

        pageInfoLabel = new JLabel("0 / 0");
        pageInfoLabel.setFont(pageInfoLabel.getFont().deriveFont(Font.PLAIN, 11f));
        pageInfoLabel.setPreferredSize(new Dimension(100, 20));
        pageInfoLabel.setHorizontalAlignment(SwingConstants.CENTER);

        pageJumpField = new JTextField(3);
        pageJumpField.setFont(pageJumpField.getFont().deriveFont(Font.PLAIN, 11f));
        pageJumpField.setHorizontalAlignment(SwingConstants.CENTER);
        pageJumpField.setPreferredSize(new Dimension(40, 24));
        pageJumpField.addActionListener(e -> {
            try {
                int pg = Integer.parseInt(pageJumpField.getText().trim()) - 1;
                if (pg >= 0 && pg < totalPages()) { currentPage = pg; refreshView(); }
            } catch (NumberFormatException ex) {
                log.debug("invalid page jump input");
            }
            pageJumpField.setText("");
        });

        bar.add(tiny("每页:")); bar.add(pageSizeCombo);
        bar.add(btnPrev); bar.add(pageInfoLabel); bar.add(btnNext);
        bar.add(tiny("跳转:")); bar.add(pageJumpField);
        return bar;
    }

    // ═══════════════════ 核心逻辑 ════════════════════════════════════════

    private void onSearch() {
        filterText  = searchField.getText().trim().toLowerCase();
        currentPage = 0;
        applyFilterAndSort();
    }

    private void applyFilterAndSort() {
        // 过滤
        filteredRows.clear();
        for (Object[] row : allRows) {
            if (matches(row)) filteredRows.add(row);
        }
        searchField.setNoResult(!filterText.isEmpty() && filteredRows.isEmpty());

        // 排序
        if (sortCol >= 0 && sortCol < tableModel.getColumnCount()) {
            final int  col = sortCol;
            final boolean asc = sortAsc;
            filteredRows.sort((x, y) -> cmp(
                    col < x.length ? x[col] : null,
                    col < y.length ? y[col] : null, asc));
        }

        refreshView();
    }

    private void refreshView() {
        tableModel.setRowCount(0);
        int total = filteredRows.size();
        int pages = totalPages();
        if (currentPage >= pages) currentPage = Math.max(0, pages - 1);

        int from = getPageOffset();
        int to   = (pageSize == 0) ? total : Math.min(from + pageSize, total);
        for (int i = from; i < to; i++) {
            tableModel.addRow(filteredRows.get(i));
        }

        updatePaginationControls();
        SwingUtilities.invokeLater(this::autoResizeColumns);
    }

    private void updatePaginationControls() {
        int total = filteredRows.size();
        int pages = totalPages();
        // 格式：当前页 / 总页数 (共N条)
        String info;
        if (total == 0) {
            info = "0 / 0";
        } else {
            info = (currentPage + 1) + " / " + pages + "  (" + total + " 条)";
        }
        pageInfoLabel.setText(info);
        btnPrev.setEnabled(currentPage > 0);
        btnNext.setEnabled(currentPage < pages - 1);
    }

    private void autoResizeColumns() {
        TableColumnModel cm = table.getColumnModel();
        if (cm.getColumnCount() == 0) return;
        for (int col = 0; col < cm.getColumnCount(); col++) {
            TableCellRenderer hr = table.getTableHeader().getDefaultRenderer();
            Component hc = hr.getTableCellRendererComponent(
                    table, table.getColumnName(col), false, false, -1, col);
            int w = hc.getPreferredSize().width + 20;
            for (int row = 0; row < Math.min(table.getRowCount(), 100); row++) {
                Component cc = table.prepareRenderer(table.getCellRenderer(row, col), row, col);
                w = Math.max(w, cc.getPreferredSize().width + 16);
            }
            cm.getColumn(col).setPreferredWidth(Math.min(w, 320));
        }
    }

    private void updateSortHeaders() {
        TableColumnModel cm = table.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            String base = (i < currentColumns.length) ? currentColumns[i] : table.getColumnName(i);
            String header = (i == sortCol) ? base + (sortAsc ? " ▲" : " ▼") : base;
            cm.getColumn(i).setHeaderValue(header);
        }
        table.getTableHeader().repaint();
    }

    private void showContextMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyCell = new JMenuItem("复制单元格");
        copyCell.addActionListener(ev -> {
            int r = table.getSelectedRow();
            int c = table.getSelectedColumn();
            if (r >= 0 && c >= 0) {
                Object v = table.getValueAt(r, c);
                clip(v == null ? "" : v.toString());
            }
        });
        JMenuItem copyRow = new JMenuItem("复制整行");
        copyRow.addActionListener(ev -> {
            int r = table.getSelectedRow();
            if (r < 0) return;
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < table.getColumnCount(); c++) {
                if (c > 0) sb.append('\t');
                Object v = table.getValueAt(r, c);
                if (v != null) sb.append(v);
            }
            clip(sb.toString());
        });
        menu.add(copyCell);
        menu.add(copyRow);
        menu.show(table, e.getX(), e.getY());
    }

    // ═══════════════════ 工具方法 ════════════════════════════════════════

    private int totalPages() {
        if (pageSize == 0) return 1;
        int n = filteredRows.size();
        return n == 0 ? 1 : (int) Math.ceil((double) n / pageSize);
    }

    private int getPageOffset() {
        return pageSize == 0 ? 0 : currentPage * pageSize;
    }

    /**
     * 行是否匹配过滤条件。
     * filterCols==null 时搜索全列，否则只搜索指定列。
     */
    private boolean matches(Object[] row) {
        if (filterText.isEmpty()) return true;
        if (filterCols == null) {
            // 全列匹配
            for (Object cell : row) {
                if (cell != null && cell.toString().toLowerCase().contains(filterText)) return true;
            }
        } else {
            // 指定列匹配
            for (int ci : filterCols) {
                if (ci < row.length) {
                    Object cell = row[ci];
                    if (cell != null && cell.toString().toLowerCase().contains(filterText)) return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int cmp(Object a, Object b, boolean asc) {
        if (a == null && b == null) return 0;
        if (a == null) return asc ? -1 : 1;
        if (b == null) return asc ? 1 : -1;
        int r;
        if (a instanceof Number na && b instanceof Number nb) {
            r = Double.compare(na.doubleValue(), nb.doubleValue());
        } else if (a instanceof Comparable ca) {
            try { r = ca.compareTo(b); }
            catch (ClassCastException ex) { r = a.toString().compareToIgnoreCase(b.toString()); }
        } else {
            r = a.toString().compareToIgnoreCase(b.toString());
        }
        return asc ? r : -r;
    }

    private static void clip(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(s), null);
    }

    private static JButton navBtn(String t) {
        JButton b = new JButton(t);
        b.setFont(b.getFont().deriveFont(Font.PLAIN, 14f));
        b.setPreferredSize(new Dimension(28, 24));
        b.setFocusPainted(false);
        b.setMargin(new Insets(0, 2, 0, 2));
        return b;
    }

    private static JLabel tiny(String t) {
        JLabel l = new JLabel(t);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
        return l;
    }

    // ═══════════════════ Renderer ═════════════════════════════════════════

    private class HoverRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            if (!sel) {
                if (row == hoveredRow) {
                    Color hv = UIManager.getColor("Table.hoverBackground");
                    setBackground(hv != null ? hv
                            : UIManager.getColor("Table.selectionBackground").brighter());
                } else {
                    Color alt = UIManager.getColor("Table.alternateRowColor");
                    setBackground(row % 2 == 0 || alt == null
                            ? UIManager.getColor("Table.background") : alt);
                }
            }
            return this;
        }
    }
}

