package com.uzoo.i18n;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;

import javax.swing.JComboBox;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

public final class TranslationSettingsConfigurable implements Configurable {
    private final Project project;
    private JPanel panel;
    private JComboBox<String> localeSelector;

    public TranslationSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public String getDisplayName() {
        return "i18n Translation Helper";
    }

    @Override
    public JComponent createComponent() {
        if (panel != null) return panel;
        panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        String basePath = project.getBasePath();
        List<String> locales = basePath == null
                ? new ArrayList<>()
                : new ArrayList<>(I18nCatalog.forProject(basePath).availableLocales());
        if (!locales.contains(InlineTranslationPlugin.DEFAULT_DISPLAY_LOCALE)) {
            locales.add(InlineTranslationPlugin.DEFAULT_DISPLAY_LOCALE);
        }
        String savedLocale = savedLocale();
        if (!locales.contains(savedLocale)) locales.add(savedLocale);
        locales.sort(String::compareToIgnoreCase);

        localeSelector = new JComboBox<>(locales.toArray(String[]::new));
        localeSelector.setSelectedItem(savedLocale);
        JLabel label = new JLabel("行内译文语言:");
        label.setLabelFor(localeSelector);
        label.setToolTipText("缺少所选语言的译文时，回退显示 zh-CN。");
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        row.add(label);
        row.add(localeSelector);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        panel.add(row);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    @Override
    public boolean isModified() {
        if (localeSelector == null) return false;
        Object selection = localeSelector.getSelectedItem();
        return selection != null && !savedLocale().equals(selection.toString());
    }

    @Override
    public void apply() throws ConfigurationException {
        if (localeSelector == null || localeSelector.getSelectedItem() == null) {
            throw new ConfigurationException("请选择行内译文语言。");
        }
        String selectedLocale = localeSelector.getSelectedItem().toString();
        PropertiesComponent.getInstance(project).setValue(
                InlineTranslationPlugin.DISPLAY_LOCALE_KEY,
                selectedLocale,
                InlineTranslationPlugin.DEFAULT_DISPLAY_LOCALE);
        InlineTranslationPlugin.refreshOpenEditors(project);
    }

    @Override
    public void reset() {
        if (localeSelector != null) localeSelector.setSelectedItem(savedLocale());
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        localeSelector = null;
    }

    private String savedLocale() {
        return PropertiesComponent.getInstance(project).getValue(
                InlineTranslationPlugin.DISPLAY_LOCALE_KEY,
                InlineTranslationPlugin.DEFAULT_DISPLAY_LOCALE);
    }
}
