package com.uzoo.i18n;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;

public final class ToggleInlineTranslationsAction extends AnAction {
    @Override
    public void update(AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabled(project != null);
        if (project == null) return;
        boolean enabled = PropertiesComponent.getInstance(project)
                .getBoolean(InlineTranslationPlugin.ENABLED_KEY, true);
        event.getPresentation().setText(enabled ? "Hide inline translations" : "Show inline translations");
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) return;
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        boolean enabled = properties.getBoolean(InlineTranslationPlugin.ENABLED_KEY, true);
        properties.setValue(InlineTranslationPlugin.ENABLED_KEY, !enabled, true);
        InlineTranslationPlugin.refreshOpenEditors(project);
    }
}
