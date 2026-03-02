package com.laker.postman.panel.performance.component;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.laker.postman.panel.performance.model.JMeterTreeNode;
import com.laker.postman.util.FontsUtil;
import com.laker.postman.util.IconUtil;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

public class JMeterTreeCellRenderer extends DefaultTreeCellRenderer {

    public static final int SIZE = 18;

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        JLabel label = (JLabel) super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
        Object userObj = node.getUserObject();
        if (userObj instanceof JMeterTreeNode jtNode) {
            // 设置图标
            switch (jtNode.type) {
                case THREAD_GROUP -> label.setIcon(IconUtil.createThemed("icons/user-group.svg", SIZE, SIZE));
                case REQUEST -> label.setIcon(new FlatSVGIcon("icons/http.svg", SIZE, SIZE));
                case ASSERTION -> label.setIcon(IconUtil.createThemed("icons/warning.svg", SIZE, SIZE));
                case TIMER -> label.setIcon(IconUtil.createThemed("icons/time.svg", SIZE, SIZE));
                case ROOT -> label.setIcon(new FlatSVGIcon("icons/computer.svg", SIZE, SIZE));
            }

            String text = jtNode.name;
            if (!jtNode.enabled) {
                Color disabledColor = new Color(150, 150, 150);
                if (!sel) {
                    label.setForeground(disabledColor);
                }
                label.setFont(FontsUtil.getDefaultFont(Font.ITALIC));
                label.setText("<html><strike>" + text + "</strike></html>");
            } else {
                // 启用状态：恢复正常样式
                label.setFont(FontsUtil.getDefaultFont(Font.PLAIN));
                label.setText(text);
            }
        }
        return label;
    }
}