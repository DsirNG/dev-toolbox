package com.uzoo.i18n;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.Processor;

import java.nio.file.Path;
import java.util.List;

/** Routes locale JSON keys through WebStorm's native Find Usages popup. */
public final class LocaleKeyFindUsagesHandlerFactory extends FindUsagesHandlerFactory {
    @Override
    public boolean canFindUsages(PsiElement element) {
        return findDefinition(element) != null;
    }

    @Override
    public FindUsagesHandler createFindUsagesHandler(PsiElement element, boolean forHighlightUsages) {
        I18nCatalog.Definition definition = findDefinition(element);
        if (definition == null) return null;

        return new FindUsagesHandler(element) {
            @Override
            public boolean processElementUsages(PsiElement target, Processor<? super UsageInfo> processor,
                                                FindUsagesOptions options) {
                String basePath = target.getProject().getBasePath();
                if (basePath == null) return true;

                List<I18nCatalog.Usage> usages = I18nCatalog.forProject(basePath).findUsages(definition.key());
                for (I18nCatalog.Usage usage : usages) {
                    boolean keepSearching = ReadAction.compute(() -> {
                        VirtualFile virtualFile = LocalFileSystem.getInstance()
                                .findFileByNioFile(usage.file().toAbsolutePath().normalize());
                        // The native usage searcher may run off the read thread. This scan already
                        // stays under this project's src tree; don't query its PSI-backed scope here.
                        if (virtualFile == null) return true;
                        PsiFile usageFile = PsiManager.getInstance(target.getProject()).findFile(virtualFile);
                        if (usageFile == null || usageFile.getTextLength() == 0) return true;
                        int offset = Math.max(0, Math.min(usage.offset(), usageFile.getTextLength() - 1));
                        PsiElement usageElement = usageFile.findElementAt(offset);
                        return usageElement == null || processor.process(new UsageInfo(usageElement));
                    });
                    if (!keepSearching) return false;
                }
                return true;
            }

            @Override
            public boolean processUsagesInText(PsiElement target, Processor<? super UsageInfo> processor,
                                               com.intellij.psi.search.GlobalSearchScope scope) {
                return true;
            }
        };
    }

    private static I18nCatalog.Definition findDefinition(PsiElement element) {
        if (element == null || !element.isValid()) return null;
        PsiFile psiFile = element.getContainingFile();
        VirtualFile file = psiFile == null ? null : psiFile.getVirtualFile();
        if (file == null || !"json".equalsIgnoreCase(file.getExtension())) return null;
        String normalizedPath = file.getPath().replace('\\', '/');
        if (!normalizedPath.contains("/locales/")) return null;

        var range = element.getTextRange();
        if (range == null || range.getLength() > 256) return null;
        String basePath = element.getProject().getBasePath();
        if (basePath == null) return null;
        I18nCatalog catalog = I18nCatalog.forProject(basePath);
        Path path = Path.of(file.getPath());
        for (int offset = range.getStartOffset(); offset < range.getEndOffset(); offset++) {
            I18nCatalog.Definition definition = catalog.findDefinitionAt(path, offset);
            if (definition != null) return definition;
        }
        return null;
    }
}
