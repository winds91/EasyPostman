package com.laker.postman.panel.toolbox;

import com.formdev.flatlaf.FlatClientProperties;
import com.laker.postman.common.component.button.*;
import com.laker.postman.util.EditorThemeUtil;
import com.laker.postman.util.I18nUtil;
import com.laker.postman.util.JsonUtil;
import com.laker.postman.util.MessageKeys;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import tools.jackson.databind.JsonNode;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Elasticsearch 可视化 CRUD + DSL 工具面板
 */
@Slf4j
public class ElasticsearchPanel extends JPanel {

    // ===== 连接 =====
    private JTextField hostField;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JButton connectBtn;
    private JLabel connectionStatusLabel;

    // ===== 索引管理 =====
    private DefaultListModel<String> indexListModel;
    private DefaultListModel<String> indexFilteredModel;
    private JList<String> indexList;
    private JTextField newIndexField;
    private JSpinner shardSpinner;
    private JSpinner replicaSpinner;

    // ===== DSL 编辑器 =====
    private JComboBox<String> methodCombo;
    private JTextField pathField;
    private RSyntaxTextArea dslEditor;
    private RSyntaxTextArea resultArea;
    private JLabel statusLabel;

    // ===== 结果表格 =====
    private DefaultTableModel tableModel;
    private JTable resultTable;
    private JTabbedPane resultTabs;

    // ===== HTTP 客户端 =====
    private OkHttpClient httpClient;
    private String baseUrl = "http://localhost:9200";
    private String authHeader = null;
    private boolean connected = false;

    // ===== 内置 DSL 模板（名称走 i18n，DSL body 保持英文原样）=====
    private static final String[][] DSL_TEMPLATES = {
            {"toolbox.es.tpl.match_all", "GET", "/{index}/_search",
                    "{\n  \"query\": {\n    \"match_all\": {}\n  },\n  \"size\": 20\n}"},
            {"toolbox.es.tpl.match", "GET", "/{index}/_search",
                    "{\n  \"query\": {\n    \"match\": {\n      \"field\": \"value\"\n    }\n  }\n}"},
            {"toolbox.es.tpl.term", "GET", "/{index}/_search",
                    "{\n  \"query\": {\n    \"term\": {\n      \"field.keyword\": \"exact_value\"\n    }\n  }\n}"},
            {"toolbox.es.tpl.range", "GET", "/{index}/_search",
                    "{\n  \"query\": {\n    \"range\": {\n      \"timestamp\": {\n        \"gte\": \"2024-01-01\",\n        \"lte\": \"2024-12-31\"\n      }\n    }\n  }\n}"},
            {"toolbox.es.tpl.bool", "GET", "/{index}/_search",
                    "{\n  \"query\": {\n    \"bool\": {\n      \"must\": [\n        { \"match\": { \"title\": \"elasticsearch\" } }\n      ],\n      \"filter\": [\n        { \"term\": { \"status\": \"active\" } }\n      ],\n      \"must_not\": [],\n      \"should\": []\n    }\n  }\n}"},
            {"toolbox.es.tpl.agg", "GET", "/{index}/_search",
                    "{\n  \"size\": 0,\n  \"aggs\": {\n    \"group_by_status\": {\n      \"terms\": {\n        \"field\": \"status.keyword\",\n        \"size\": 10\n      }\n    }\n  }\n}"},
            {"toolbox.es.tpl.index_doc", "POST", "/{index}/_doc",
                    "{\n  \"field1\": \"value1\",\n  \"field2\": \"value2\",\n  \"timestamp\": \"2024-01-01T00:00:00Z\"\n}"},
            {"toolbox.es.tpl.update_doc", "POST", "/{index}/_update/{id}",
                    "{\n  \"doc\": {\n    \"field\": \"new_value\"\n  }\n}"},
            {"toolbox.es.tpl.delete_doc", "DELETE", "/{index}/_doc/{id}", ""},
            {"toolbox.es.tpl.get_doc", "GET", "/{index}/_doc/{id}", ""},
            {"toolbox.es.tpl.mapping", "GET", "/{index}/_mapping", ""},
            {"toolbox.es.tpl.settings", "GET", "/{index}/_settings", ""},
            {"toolbox.es.tpl.health", "GET", "/_cluster/health", ""},
            {"toolbox.es.tpl.cluster_stats", "GET", "/_cluster/stats", ""},
            {"toolbox.es.tpl.list_indices", "GET", "/_cat/indices?v&format=json", ""},
            {"toolbox.es.tpl.create_index", "PUT", "/{index}",
                    "{\n  \"settings\": {\n    \"number_of_shards\": 1,\n    \"number_of_replicas\": 1\n  },\n  \"mappings\": {\n    \"properties\": {\n      \"title\": { \"type\": \"text\" },\n      \"status\": { \"type\": \"keyword\" },\n      \"timestamp\": { \"type\": \"date\" }\n    }\n  }\n}"},
            {"toolbox.es.tpl.reindex", "POST", "/_reindex",
                    "{\n  \"source\": {\n    \"index\": \"source_index\"\n  },\n  \"dest\": {\n    \"index\": \"dest_index\"\n  }\n}"},
            {"toolbox.es.tpl.delete_by_query", "POST", "/{index}/_delete_by_query",
                    "{\n  \"query\": {\n    \"match\": {\n      \"field\": \"value\"\n    }\n  }\n}"},
            {"toolbox.es.tpl.update_by_query", "POST", "/{index}/_update_by_query",
                    "{\n  \"script\": {\n    \"source\": \"ctx._source.status = 'updated'\"\n  },\n  \"query\": {\n    \"match_all\": {}\n  }\n}"},
    };

    public ElasticsearchPanel() {
        initHttpClient();
        initUI();
    }

    private void initHttpClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private void initUI() {
        setLayout(new BorderLayout(0, 0));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // 顶部连接栏
        add(buildConnectionPanel(), BorderLayout.NORTH);

        // 主内容区：左侧索引树 + 右侧 DSL 编辑器
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildIndexPanel(), buildDslPanel());
        mainSplit.setDividerLocation(230);
        mainSplit.setDividerSize(5);
        mainSplit.setContinuousLayout(true);
        add(mainSplit, BorderLayout.CENTER);

        // 底部状态栏
        statusLabel = new JLabel(" " + I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_READY));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        add(statusLabel, BorderLayout.SOUTH);
    }

    // ===== 连接面板 =====
    private JPanel buildConnectionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        // 所有组件放同一行 FlowLayout，连接按钮紧跟密码框
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        hostField = new JTextField("http://localhost:9200", 26);
        hostField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_HOST_PLACEHOLDER));

        usernameField = new JTextField("", 10);
        usernameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_USER_PLACEHOLDER));

        passwordField = new JPasswordField("", 10);
        passwordField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_PASS_PLACEHOLDER));

        connectionStatusLabel = new JLabel("●");
        connectionStatusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        connectionStatusLabel.setFont(connectionStatusLabel.getFont().deriveFont(Font.BOLD, 14f));
        connectionStatusLabel.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_NOT_CONNECTED));

        connectBtn = new PrimaryButton(
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_CONNECT), "icons/connect.svg");
        connectBtn.addActionListener(e -> doConnect());

        row.add(new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_HOST)));
        row.add(hostField);
        row.add(new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_USER)));
        row.add(usernameField);
        row.add(new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_PASS)));
        row.add(passwordField);
        row.add(connectBtn);
        row.add(connectionStatusLabel);

        panel.add(row, BorderLayout.CENTER);
        return panel;
    }

    // ===== 索引管理面板 =====
    private JPanel buildIndexPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBorder(BorderFactory.createEmptyBorder());
        panel.setPreferredSize(new Dimension(220, 0));

        // 顶部标题栏 + 刷新按钮
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 8, 4, 4)));
        JLabel titleLbl = new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_MANAGEMENT));
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD, 12f));
        RefreshButton refreshBtn = new RefreshButton();
        refreshBtn.addActionListener(e -> loadIndices());
        titleBar.add(titleLbl, BorderLayout.CENTER);
        titleBar.add(refreshBtn, BorderLayout.EAST);

        // 搜索框
        com.laker.postman.common.component.SearchTextField indexSearchField =
                new com.laker.postman.common.component.SearchTextField();
        indexSearchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        JPanel searchBox = new JPanel(new BorderLayout());
        searchBox.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
        searchBox.add(indexSearchField, BorderLayout.CENTER);

        // 索引列表（使用过滤 model）
        indexListModel = new DefaultListModel<>();
        indexFilteredModel = new DefaultListModel<>();
        indexList = new JList<>(indexFilteredModel);
        indexList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        indexList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = indexList.getSelectedValue();
                if (selected != null) pathField.setText("/" + selected + "/_search");
            }
        });
        indexList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    String sel = indexList.getSelectedValue();
                    if (sel != null) {
                        pathField.setText("/" + sel + "/_mapping");
                        methodCombo.setSelectedItem("GET");
                        dslEditor.setText("");
                        executeRequest();
                    }
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(indexList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());

        // 搜索监听：实时过滤
        indexSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filterIndices(indexSearchField.getText());
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filterIndices(indexSearchField.getText());
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filterIndices(indexSearchField.getText());
            }
        });

        // 操作按钮行（仅保留删除）
        JPanel actionBar = new JPanel(new GridLayout(1, 1, 4, 0));
        actionBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        SecondaryButton deleteBtn = new SecondaryButton(
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_DELETE), "icons/delete.svg");
        deleteBtn.addActionListener(e -> deleteSelectedIndex());
        actionBar.add(deleteBtn);

        // 创建索引面板
        JPanel createPanel = new JPanel(new GridBagLayout());
        createPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        newIndexField = new JTextField();
        newIndexField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_NAME_PLACEHOLDER));
        shardSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
        replicaSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
        PrimaryButton createBtn = new PrimaryButton(
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_CREATE), "icons/plus.svg");
        createBtn.addActionListener(e -> createIndex());

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        createPanel.add(new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_NAME)), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        createPanel.add(newIndexField, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        createPanel.add(new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_SHARDS)), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        createPanel.add(shardSpinner, gbc);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        createPanel.add(new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_REPLICAS)), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        createPanel.add(replicaSpinner, gbc);
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        createPanel.add(createBtn, gbc);

        JPanel topArea = new JPanel(new BorderLayout());
        topArea.add(titleBar, BorderLayout.NORTH);
        topArea.add(searchBox, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(actionBar, BorderLayout.NORTH);
        bottom.add(createPanel, BorderLayout.CENTER);

        panel.add(topArea, BorderLayout.NORTH);
        panel.add(listScroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * 根据关键词过滤索引列表
     */
    private void filterIndices(String kw) {
        String lower = kw == null ? "" : kw.trim().toLowerCase();
        indexFilteredModel.clear();
        for (int i = 0; i < indexListModel.size(); i++) {
            String name = indexListModel.get(i);
            if (lower.isEmpty() || name.toLowerCase().contains(lower)) {
                indexFilteredModel.addElement(name);
            }
        }
    }

    // ===== DSL 编辑 + 结果面板 =====
    private JPanel buildDslPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

        // ---- 工具栏：所有按钮一行 ----
        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                UIManager.getColor("Separator.foreground")));

        JComboBox<String> templateCombo = new JComboBox<>();
        for (String[] t : DSL_TEMPLATES) templateCombo.addItem(I18nUtil.getMessage(t[0]));
        templateCombo.setPreferredSize(new Dimension(180, 28));
        SecondaryButton loadTplBtn = new SecondaryButton(
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_LOAD_TEMPLATE), "icons/load.svg");
        loadTplBtn.addActionListener(e -> {
            int idx = templateCombo.getSelectedIndex();
            if (idx >= 0 && idx < DSL_TEMPLATES.length) {
                methodCombo.setSelectedItem(DSL_TEMPLATES[idx][1]);
                pathField.setText(DSL_TEMPLATES[idx][2]);
                dslEditor.setText(DSL_TEMPLATES[idx][3]);
                dslEditor.setCaretPosition(0);
            }
        });
        FormatButton formatBtn = new FormatButton();
        formatBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_FORMAT_JSON));
        formatBtn.addActionListener(e -> formatDsl());
        CopyButton copyBtn = new CopyButton();
        copyBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_COPY_RESULT));
        copyBtn.addActionListener(e -> copyResult());
        ClearButton clearBtn = new ClearButton();
        clearBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_CLEAR));
        clearBtn.addActionListener(e -> {
            dslEditor.setText("");
            resultArea.setText("");
            clearTable();
            setStatus(" " + I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_CLEARED));
        });

        toolBar.add(templateCombo);
        toolBar.add(loadTplBtn);
        toolBar.add(new JSeparator(SwingConstants.VERTICAL));
        toolBar.add(formatBtn);
        toolBar.add(copyBtn);
        toolBar.add(clearBtn);

        // ---- 请求行：Method + Path + Execute ----
        JPanel requestRow = new JPanel(new BorderLayout(4, 0));
        requestRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        methodCombo = new JComboBox<>(new String[]{"GET", "POST", "PUT", "DELETE", "HEAD"});
        methodCombo.setPreferredSize(new Dimension(90, 28));
        pathField = new JTextField("/_cluster/health");
        pathField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_PATH_PLACEHOLDER));
        PrimaryButton executeBtn = new PrimaryButton(
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_EXECUTE), "icons/send.svg");
        executeBtn.addActionListener(e -> executeRequest());
        registerCtrlEnterShortcut(executeBtn);
        requestRow.add(methodCombo, BorderLayout.WEST);
        requestRow.add(pathField, BorderLayout.CENTER);
        requestRow.add(executeBtn, BorderLayout.EAST);

        JPanel topArea = new JPanel(new BorderLayout(0, 4));
        topArea.add(toolBar, BorderLayout.NORTH);
        topArea.add(requestRow, BorderLayout.CENTER);
        panel.add(topArea, BorderLayout.NORTH);

        // ---- 主分割：DSL 编辑器(上) + 结果(下) ----
        JSplitPane editorSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        editorSplit.setDividerSize(5);
        editorSplit.setContinuousLayout(true);
        editorSplit.setResizeWeight(0.4);
        editorSplit.setBorder(BorderFactory.createEmptyBorder());

        // DSL 编辑器
        JPanel editorPanel = new JPanel(new BorderLayout());
        JLabel dslLabel = new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_DSL_TITLE));
        dslLabel.setFont(dslLabel.getFont().deriveFont(Font.BOLD, 11f));
        dslLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        dslEditor = createJsonEditor(true);
        dslEditor.setText("{\n  \"query\": {\n    \"match_all\": {}\n  },\n  \"size\": 20\n}");
        RTextScrollPane editorScroll = new RTextScrollPane(dslEditor);
        editorScroll.setLineNumbersEnabled(true);
        editorScroll.setBorder(BorderFactory.createEmptyBorder());
        editorPanel.add(dslLabel, BorderLayout.NORTH);
        editorPanel.add(editorScroll, BorderLayout.CENTER);

        // 结果区（Tab: 表格 + 原始 JSON）
        JPanel resultPanel = new JPanel(new BorderLayout());
        JLabel respLabel = new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_RESPONSE_TITLE));
        respLabel.setFont(respLabel.getFont().deriveFont(Font.BOLD, 11f));
        respLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));

        resultTabs = new JTabbedPane(SwingConstants.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        tableModel = new DefaultTableModel() {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        resultTable = new JTable(tableModel);
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultTable.setFillsViewportHeight(true);
        resultTable.setRowHeight(22);
        JPopupMenu tablePopup = new JPopupMenu();
        JMenuItem copyCell = new JMenuItem(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_COPY_CELL));
        copyCell.addActionListener(e -> {
            int row = resultTable.getSelectedRow();
            int col = resultTable.getSelectedColumn();
            if (row >= 0 && col >= 0) {
                Object val = resultTable.getValueAt(row, col);
                if (val != null)
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(val.toString()), null);
            }
        });
        tablePopup.add(copyCell);
        resultTable.setComponentPopupMenu(tablePopup);

        resultArea = createJsonEditor(false);
        RTextScrollPane resultScroll = new RTextScrollPane(resultArea);
        resultScroll.setLineNumbersEnabled(true);
        resultScroll.setBorder(BorderFactory.createEmptyBorder());

        resultTabs.addTab(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_TAB_TABLE), new JScrollPane(resultTable));
        resultTabs.addTab(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_TAB_RAW), resultScroll);

        resultPanel.add(respLabel, BorderLayout.NORTH);
        resultPanel.add(resultTabs, BorderLayout.CENTER);

        editorSplit.setTopComponent(editorPanel);
        editorSplit.setBottomComponent(resultPanel);

        panel.add(editorSplit, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 注册 Ctrl+Enter 快捷键
     */
    private void registerCtrlEnterShortcut(JButton executeBtn) {
        SwingUtilities.invokeLater(() -> {
            if (dslEditor != null) {
                dslEditor.getInputMap().put(
                        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER,
                                java.awt.event.InputEvent.CTRL_DOWN_MASK),
                        "executeRequest");
                dslEditor.getActionMap().put("executeRequest", new AbstractAction() {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        executeBtn.doClick();
                    }
                });
            }
        });
    }

    // ===== 核心功能方法 =====

    private void doConnect() {
        String url = hostField.getText().trim();
        if (url.isEmpty()) {
            showError(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_ERR_HOST_REQUIRED));
            return;
        }
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        baseUrl = url;
        String user = usernameField.getText().trim();
        String pass = new String(passwordField.getPassword()).trim();
        if (!user.isEmpty()) {
            String cred = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
            authHeader = "Basic " + cred;
        } else {
            authHeader = null;
        }
        connectBtn.setEnabled(false);
        setStatus(" " + MessageFormat.format(
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_CONNECTING), baseUrl));
        final String finalUrl = baseUrl;
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return doGet("/_cluster/health");
            }

            @Override
            protected void done() {
                connectBtn.setEnabled(true);
                try {
                    String resp = get();
                    connected = true;
                    connectionStatusLabel.setForeground(new Color(0, 180, 0));
                    connectionStatusLabel.setToolTipText(MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_CONNECTED), finalUrl));
                    setStatus(" ✅ " + MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_CONNECTED), finalUrl));
                    resultArea.setText(JsonUtil.toJsonPrettyStr(resp));
                    resultArea.setCaretPosition(0);
                    loadIndices();
                } catch (Exception ex) {
                    connected = false;
                    connectionStatusLabel.setForeground(Color.RED);
                    connectionStatusLabel.setToolTipText(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_NOT_CONNECTED));
                    setStatus(" ❌ " + MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_CONNECT_FAILED),
                            ex.getMessage()));
                    showError(MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_ERR_CONNECT_FAILED),
                            ex.getMessage()));
                }
            }
        };
        worker.execute();
    }

    private void loadIndices() {
        if (!connected) {
            setStatus(" " + I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_NOT_CONNECTED));
            return;
        }
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return doGet("/_cat/indices?v&format=json&s=index");
            }

            @Override
            protected void done() {
                try {
                    String json = get();
                    indexListModel.clear();
                    JsonNode arr = JsonUtil.readTree(json);
                    if (arr.isArray()) {
                        for (JsonNode node : arr) {
                            JsonNode idx = node.get("index");
                            if (idx != null && !idx.isNull())
                                indexListModel.addElement(idx.toString().replace("\"", ""));
                        }
                    }
                    filterIndices(""); // 刷新后重置过滤，显示全部
                    setStatus(" ✅ " + MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_LOADED),
                            indexListModel.size()));
                } catch (Exception ex) {
                    setStatus(" ❌ " + MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_LOAD_FAILED),
                            ex.getMessage()));
                }
            }
        };
        worker.execute();
    }

    private void createIndex() {
        if (!connected) {
            showError(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_ERR_NOT_CONNECTED));
            return;
        }
        String name = newIndexField.getText().trim();
        if (name.isEmpty()) {
            showError(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_NAME_REQUIRED));
            return;
        }
        int shards = (int) shardSpinner.getValue();
        int replicas = (int) replicaSpinner.getValue();
        String body = "{\n  \"settings\": {\n    \"number_of_shards\": " + shards
                + ",\n    \"number_of_replicas\": " + replicas + "\n  }\n}";
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return doPut("/" + name, body);
            }

            @Override
            protected void done() {
                try {
                    String resp = get();
                    resultArea.setText(JsonUtil.toJsonPrettyStr(resp));
                    resultArea.setCaretPosition(0);
                    setStatus(" ✅ " + MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_CREATED), name));
                    newIndexField.setText("");
                    loadIndices();
                } catch (Exception ex) {
                    setStatus(" ❌ " + MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_LOAD_FAILED),
                            ex.getMessage()));
                    showError(MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_CREATE_FAILED),
                            ex.getMessage()));
                }
            }
        };
        worker.execute();
    }

    private void deleteSelectedIndex() {
        if (!connected) {
            showError(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_ERR_NOT_CONNECTED));
            return;
        }
        String sel = indexList.getSelectedValue();
        if (sel == null) {
            showError(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_SELECT_TO_DELETE));
            return;
        }
        int opt = JOptionPane.showConfirmDialog(this,
                MessageFormat.format(
                        I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_DELETE_CONFIRM), sel),
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_DELETE_CONFIRM_TITLE),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (opt != JOptionPane.YES_OPTION) return;
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return doDelete("/" + sel, "");
            }

            @Override
            protected void done() {
                try {
                    String resp = get();
                    resultArea.setText(JsonUtil.toJsonPrettyStr(resp));
                    resultArea.setCaretPosition(0);
                    setStatus(" ✅ " + MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_DELETED), sel));
                    loadIndices();
                } catch (Exception ex) {
                    setStatus(" ❌ " + MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_DELETE_FAILED),
                            ex.getMessage()));
                    showError(MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_INDEX_DELETE_FAILED),
                            ex.getMessage()));
                }
            }
        };
        worker.execute();
    }


    private void executeRequest() {
        if (!connected) {
            showError(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_ERR_NOT_CONNECTED));
            return;
        }
        String selectedMethod = (String) methodCombo.getSelectedItem();
        final String method = (selectedMethod == null) ? "GET" : selectedMethod;
        String path = pathField.getText().trim();
        if (path.isEmpty()) {
            showError(I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_ERR_PATH_REQUIRED));
            return;
        }
        String body = dslEditor.getText().trim();
        setStatus(" ⏳ " + MessageFormat.format(
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_EXECUTING), method, path));
        clearTable();
        long start = System.currentTimeMillis();
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return switch (method) {
                    case "POST" -> doPost(path, body);
                    case "PUT" -> doPut(path, body);
                    case "DELETE" -> doDelete(path, body);
                    case "HEAD" -> doHead(path);
                    default -> doGet(path);
                };
            }

            @Override
            protected void done() {
                long elapsed = System.currentTimeMillis() - start;
                try {
                    String resp = get();
                    String formatted = JsonUtil.isTypeJSON(resp) ? JsonUtil.toJsonPrettyStr(resp) : resp;
                    resultArea.setText(formatted);
                    resultArea.setCaretPosition(0);
                    if (JsonUtil.isTypeJSON(resp)) {
                        populateTable(resp);
                    }
                    // 根据实际情况切换 tab：有表格数据切 Table，否则切 Raw JSON
                    if (tableModel.getRowCount() > 0) {
                        resultTabs.setSelectedIndex(0); // 表格视图
                    } else {
                        resultTabs.setSelectedIndex(1); // 原始 JSON
                    }
                    setStatus(" ✅ " + MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_SUCCESS),
                            method, path, elapsed, resp.length()));
                } catch (Exception ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    resultArea.setText("Error: " + msg);
                    setStatus(" ❌ " + MessageFormat.format(
                            I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_REQUEST_FAILED), msg));
                }
            }
        };
        worker.execute();
    }

    private void populateTable(String json) {
        tableModel.setRowCount(0);
        tableModel.setColumnCount(0);
        try {
            JsonNode root = JsonUtil.readTree(json);
            if (root.has("hits")) {
                populateHitsTable(root);
            } else if (root.has("indices") || root.has("_all")) {
                tableModel.addColumn("Key");
                tableModel.addColumn("Value");
                flattenJson("", root);
            } else if (json.trim().startsWith("[")) {
                populateArrayTable(json);
            }
        } catch (Exception e) {
            log.debug("populateTable error (non-fatal): {}", e.getMessage());
        }
    }

    private void populateHitsTable(JsonNode root) {
        JsonNode hits = root.get("hits");
        JsonNode hitsArr = hits.get("hits");
        if (hitsArr == null || !hitsArr.isArray() || hitsArr.isEmpty()) return;
        JsonNode first = hitsArr.get(0);
        tableModel.addColumn("_index");
        tableModel.addColumn("_id");
        tableModel.addColumn("_score");
        JsonNode source0 = first.get("_source");
        if (source0 != null) {
            for (java.util.Map.Entry<String, JsonNode> e : source0.properties()) {
                tableModel.addColumn(e.getKey());
            }
        }
        for (JsonNode hit : hitsArr) {
            JsonNode src = hit.get("_source");
            java.util.List<Object> row = new java.util.ArrayList<>();
            row.add(nodeText(hit.get("_index")));
            row.add(nodeText(hit.get("_id")));
            row.add(nodeText(hit.get("_score")));
            if (src != null) {
                for (int c = 3; c < tableModel.getColumnCount(); c++) {
                    row.add(nodeText(src.get(tableModel.getColumnName(c))));
                }
            }
            tableModel.addRow(row.toArray());
        }
        autoResizeColumns();
    }

    private void populateArrayTable(String json) {
        try {
            JsonNode arr = JsonUtil.readTree(json);
            if (!arr.isArray() || arr.isEmpty()) return;
            JsonNode first = arr.get(0);
            for (java.util.Map.Entry<String, JsonNode> e : first.properties()) {
                tableModel.addColumn(e.getKey());
            }
            for (JsonNode obj : arr) {
                java.util.List<Object> row = new java.util.ArrayList<>();
                for (int c = 0; c < tableModel.getColumnCount(); c++) {
                    row.add(nodeText(obj.get(tableModel.getColumnName(c))));
                }
                tableModel.addRow(row.toArray());
            }
            autoResizeColumns();
        } catch (Exception e) {
            log.debug("populateArrayTable error: {}", e.getMessage());
        }
    }

    private void flattenJson(String prefix, JsonNode obj) {
        for (java.util.Map.Entry<String, JsonNode> entry : obj.properties()) {
            String fullKey = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode v = entry.getValue();
            if (v.isObject()) {
                flattenJson(fullKey, v);
            } else {
                tableModel.addRow(new Object[]{fullKey, nodeText(v)});
            }
        }
    }

    /**
     * 安全地将 JsonNode 转为字符串，null 返回空串
     */
    private static String nodeText(JsonNode node) {
        if (node == null || node.isNull()) return "";
        return node.isValueNode() ? node.toString().replace("\"", "") : node.toString();
    }

    private void autoResizeColumns() {
        for (int col = 0; col < resultTable.getColumnCount(); col++) {
            int width = 80;
            for (int row = 0; row < Math.min(resultTable.getRowCount(), 50); row++) {
                Object val = resultTable.getValueAt(row, col);
                if (val != null) {
                    int w = resultTable.getFontMetrics(resultTable.getFont())
                            .stringWidth(val.toString()) + 16;
                    width = Math.max(width, w);
                }
            }
            resultTable.getColumnModel().getColumn(col)
                    .setPreferredWidth(Math.min(width, 300));
        }
    }

    private void clearTable() {
        tableModel.setRowCount(0);
        tableModel.setColumnCount(0);
    }

    private void formatDsl() {
        String txt = dslEditor.getText().trim();
        if (txt.isEmpty()) return;
        if (JsonUtil.isTypeJSON(txt)) {
            dslEditor.setText(JsonUtil.toJsonPrettyStr(txt));
            dslEditor.setCaretPosition(0);
            setStatus(" ✅ " + I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_FORMATTED));
        } else {
            setStatus(" ⚠️ " + I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_NOT_JSON));
        }
    }

    private void copyResult() {
        String txt = resultArea.getText();
        if (txt != null && !txt.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(txt), null);
            setStatus(" ✅ " + I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_STATUS_COPIED));
        }
    }

    // ===== HTTP 方法封装 =====

    private String doGet(String path) throws IOException {
        return executeHttp(buildRequest("GET", path, null));
    }

    private String doPost(String path, String body) throws IOException {
        RequestBody rb = body.isEmpty()
                ? RequestBody.create(new byte[0], null)
                : RequestBody.create(body, MediaType.get("application/json; charset=utf-8"));
        return executeHttp(buildRequest("POST", path, rb));
    }

    private String doPut(String path, String body) throws IOException {
        RequestBody rb = body.isEmpty()
                ? RequestBody.create(new byte[0], MediaType.get("application/json"))
                : RequestBody.create(body, MediaType.get("application/json; charset=utf-8"));
        return executeHttp(buildRequest("PUT", path, rb));
    }

    private String doDelete(String path, String body) throws IOException {
        RequestBody rb = (body != null && !body.isEmpty())
                ? RequestBody.create(body, MediaType.get("application/json; charset=utf-8"))
                : null;
        Request.Builder builder = new Request.Builder().url(baseUrl + path);
        if (authHeader != null) builder.header("Authorization", authHeader);
        builder.header("Content-Type", "application/json");
        if (rb != null) builder.delete(rb);
        else builder.delete();
        return executeHttp(builder.build());
    }

    private String doHead(String path) throws IOException {
        Request.Builder builder = new Request.Builder().url(baseUrl + path).head();
        if (authHeader != null) builder.header("Authorization", authHeader);
        return executeHttp(builder.build());
    }

    private Request buildRequest(String method, String path, RequestBody body) {
        Request.Builder builder = new Request.Builder().url(baseUrl + path);
        if (authHeader != null) builder.header("Authorization", authHeader);
        builder.header("Content-Type", "application/json");
        builder.method(method, body);
        return builder.build();
    }

    private String executeHttp(Request req) throws IOException {
        try (Response resp = httpClient.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful() && body.isEmpty()) {
                throw new IOException("HTTP " + resp.code() + " " + resp.message());
            }
            return body;
        }
    }

    // ===== 工具方法 =====

    private RSyntaxTextArea createJsonEditor(boolean editable) {
        RSyntaxTextArea area = new RSyntaxTextArea(10, 60);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setEditable(editable);
        EditorThemeUtil.loadTheme(area);
        return area;
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(msg));
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg,
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_TITLE),
                JOptionPane.ERROR_MESSAGE);
    }
}

