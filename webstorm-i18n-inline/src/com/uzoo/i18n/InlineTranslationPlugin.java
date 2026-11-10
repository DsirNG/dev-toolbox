package com.uzoo.i18n;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.ListPopupStep;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ui.JBColor;
import com.intellij.find.actions.ShowUsagesAction;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.awt.RelativePoint;

import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.IllegalComponentStateException;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.Font;
import java.awt.Color;
import java.awt.Point;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InlineTranslationPlugin implements StartupActivity.DumbAware {
    static final String ENABLED_KEY = "uzoo.i18n.inline.enabled";
    static final String DISPLAY_LOCALE_KEY = "uzoo.i18n.inline.display.locale";
    static final String DEFAULT_DISPLAY_LOCALE = "zh-CN";
    private static final Pattern TRANSLATION_CALL = Pattern.compile("(?<![\\w$])(?:\\$t|t)\\s*\\(\\s*(['\\\"`])([\\w.-]+)\\1\\s*\\)");
    private static final Map<Editor, Disposable> EDITORS = new ConcurrentHashMap<>();
    private static final Key<RangeHighlighter> NAVIGATION_HIGHLIGHTER = Key.create("uzoo.i18n.navigation.highlighter");
    private static final Key<Cursor> PREVIOUS_NAVIGATION_CURSOR = Key.create("uzoo.i18n.navigation.previous.cursor");
    private static final Key<Integer> LAST_MOUSE_OFFSET = Key.create("uzoo.i18n.navigation.last.mouse.offset");
    private static final Key<Timer> LOCALE_HOVER_TIMER = Key.create("i18n.locale.hover.timer");
    private static final Key<String> LOCALE_HOVER_KEY = Key.create("i18n.locale.hover.key");
    private static final Key<Point> LOCALE_HOVER_POINT = Key.create("i18n.locale.hover.point");
    private static final Key<Rectangle> LOCALE_ANCHOR_REGION = Key.create("i18n.locale.anchor.region");
    private static final Key<JBPopup> LOCALE_POPUP = Key.create("i18n.locale.popup");
    private static final int LOCALE_HOVER_DELAY_MS = 150;
    private static final Key<PendingNavigation> PENDING_NAVIGATION = Key.create("i18n.pending.navigation");
    private static final JBColor NAVIGATION_COLOR = new JBColor(new Color(0x3574F0), new Color(0x589DF6));
    private static final TextAttributes NAVIGATION_ATTRIBUTES = new TextAttributes(
            NAVIGATION_COLOR, null, NAVIGATION_COLOR, EffectType.LINE_UNDERSCORE, Font.PLAIN);

    @Override
    public void runActivity(Project project) {
        EditorFactory.getInstance().addEditorFactoryListener(new EditorFactoryListener() {
            @Override
            public void editorCreated(EditorFactoryEvent event) {
                attach(project, event.getEditor());
            }

            @Override
            public void editorReleased(EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                disposeLocaleHover(editor);
                clearNavigationHighlight(editor);
                Disposable disposable = EDITORS.remove(editor);
                if (disposable != null) Disposer.dispose(disposable);
            }
        }, project);
        String basePath = project.getBasePath();
        if (basePath != null) {
            I18nCatalog.forProject(basePath).refreshAsync(() -> {
                if (!project.isDisposed()) refreshOpenEditors(project);
            });
        }
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) attach(project, editor);
    }

    private static void attach(Project project, Editor editor) {
        if (editor.getProject() != project || editor.isDisposed() || EDITORS.containsKey(editor)) return;
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null || (!isVueFile(file) && !isLocaleFile(file))) return;

        Disposable disposable = Disposer.newDisposable("i18n inline editor listener");
        EDITORS.put(editor, disposable);
        AtomicBoolean pending = new AtomicBoolean();
        Runnable refresh = () -> {
            if (!pending.compareAndSet(false, true)) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                pending.set(false);
                if (!project.isDisposed() && !editor.isDisposed()) refreshEditor(project, editor);
            });
        };
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent event) {
                refresh.run();
            }
        }, disposable);
        editor.addEditorMouseListener(new EditorMouseListener() {
            @Override
            public void mousePressed(EditorMouseEvent event) {
                MouseEvent mouseEvent = event.getMouseEvent();
                if (mouseEvent.getButton() == MouseEvent.BUTTON1 && mouseEvent.isControlDown()) {
                    updateNavigationHighlight(project, editor, event);
                }
                PendingNavigation pending = pendingNavigationAt(project, editor, event);
                if (pending == null) {
                    if (mouseEvent.getButton() == MouseEvent.BUTTON1 && mouseEvent.isControlDown()) {
                        disposeLocaleHover(editor);
                    }
                    return;
                }
                editor.putUserData(PENDING_NAVIGATION, pending);
                consumeMouseEvent(event);
            }

            @Override
            public void mouseReleased(EditorMouseEvent event) {
                PendingNavigation pending = editor.getUserData(PENDING_NAVIGATION);
                if (pending == null) return;
                editor.putUserData(PENDING_NAVIGATION, null);
                consumeMouseEvent(event);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed() || editor.isDisposed()) return;
                    if (pending.localeFile()) {
                        navigateToUsage(project, editor, pending.offset(), pending.screenPoint());
                    } else {
                        disposeLocaleHover(editor);
                        navigateToTranslation(project, editor, pending.offset(), pending.screenPoint());
                    }
                });
            }

            @Override
            public void mouseExited(EditorMouseEvent event) {
                editor.putUserData(PENDING_NAVIGATION, null);
                clearNavigationHighlight(editor);
                JBPopup popup = editor.getUserData(LOCALE_POPUP);
                Point screenPoint = event.getMouseEvent().getLocationOnScreen();
                if (popup != null && popup.isVisible() && isInsideLocaleHoverRegion(editor, popup, screenPoint)) return;
                if (popup != null && popup.isVisible()) popup.cancel();
                stopLocaleHoverTimer(editor);
                editor.putUserData(LOCALE_HOVER_KEY, null);
                editor.putUserData(LOCALE_HOVER_POINT, null);
                editor.putUserData(LOCALE_ANCHOR_REGION, null);
            }
        }, disposable);
        editor.addEditorMouseMotionListener(new EditorMouseMotionListener() {
            @Override
            public void mouseMoved(EditorMouseEvent event) {
                editor.putUserData(LAST_MOUSE_OFFSET, offsetAtMouse(editor, event));
                scheduleLocalePopup(project, editor, event);
                updateNavigationHighlight(project, editor, event);
            }
        }, disposable);
        KeyAdapter controlKeyListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_CONTROL) {
                    disposeLocaleHover(editor);
                    Integer offset = editor.getUserData(LAST_MOUSE_OFFSET);
                    if (offset != null) updateNavigationHighlight(project, editor, offset, true);
                }
            }

            @Override
            public void keyReleased(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_CONTROL) clearNavigationHighlight(editor);
            }
        };
        editor.getContentComponent().addKeyListener(controlKeyListener);
        Disposer.register(disposable, () -> editor.getContentComponent().removeKeyListener(controlKeyListener));
        refresh.run();
    }

    private static PendingNavigation pendingNavigationAt(Project project, Editor editor, EditorMouseEvent event) {
        MouseEvent mouseEvent = event.getMouseEvent();
        if (!event.isOverText() || mouseEvent.getButton() != MouseEvent.BUTTON1 || !mouseEvent.isControlDown()) return null;
        int clickOffset = offsetAtMouse(editor, event);
        Point screenPoint = mouseEvent.getLocationOnScreen();
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        String basePath = project.getBasePath();
        if (file == null || basePath == null) return null;
        I18nCatalog catalog = I18nCatalog.forProject(basePath);
        if (isVueFile(file)) {
            TranslationKey key = translationAt(editor, clickOffset);
            return key != null && !catalog.findTranslations(key.key()).isEmpty()
                    ? new PendingNavigation(clickOffset, false, screenPoint) : null;
        }
        if (isLocaleFile(file)
                && catalog.findDefinitionAt(java.nio.file.Path.of(file.getPath()), clickOffset) != null) {
            return new PendingNavigation(clickOffset, true, screenPoint);
        }
        return null;
    }

    private static void consumeMouseEvent(EditorMouseEvent event) {
        event.consume();
        event.getMouseEvent().consume();
    }

    private static void navigateToTranslation(Project project, Editor editor, int offset, Point screenPoint) {
        TranslationKey key = translationAt(editor, offset);
        if (key == null) return;
        String basePath = project.getBasePath();
        if (basePath == null) return;
        List<I18nCatalog.Definition> definitions = I18nCatalog.forProject(basePath).findTranslations(key.key());
        if (definitions.isEmpty()) return;
        if (definitions.size() == 1) {
            navigateToDefinition(project, definitions.get(0));
        } else {
            showTranslationTargets(project, editor, definitions, screenPoint);
        }
    }

    private static void navigateToUsage(Project project, Editor editor, int sourceOffset, Point screenPoint) {
        String basePath = project.getBasePath();
        VirtualFile sourceFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (basePath == null || sourceFile == null || !isLocaleFile(sourceFile)) return;

        I18nCatalog catalog = I18nCatalog.forProject(basePath);
        I18nCatalog.Definition definition = catalog.findDefinitionAt(java.nio.file.Path.of(sourceFile.getPath()), sourceOffset);
        if (definition != null) {
            showNativeUsages(project, editor, sourceOffset, screenPoint);
            return;
        }

        // The first lookup may happen before the catalogue finishes loading.
        catalog.refreshAsync(() -> {
            if (project.isDisposed() || editor.isDisposed()) return;
            I18nCatalog.Definition loaded = catalog.findDefinitionAt(java.nio.file.Path.of(sourceFile.getPath()), sourceOffset);
            if (loaded != null) showNativeUsages(project, editor, sourceOffset, screenPoint);
        });
    }

    private static void showNativeUsages(Project project, Editor editor, int offset, Point screenPoint) {
        if (project.isDisposed() || editor.isDisposed()) return;
        PsiElement element = ReadAction.compute(() -> {
            PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            return psiFile == null ? null : psiFile.findElementAt(offset);
        });
        if (element == null) return;
        ShowUsagesAction.startFindUsages(element, new RelativePoint(screenPoint), editor);
    }

    private static void showTranslationTargets(Project project, Editor editor,
                                               List<I18nCatalog.Definition> definitions, Point screenPoint) {
        List<I18nCatalog.Definition> available = ReadAction.compute(() -> {
            List<I18nCatalog.Definition> result = new ArrayList<>();
            for (I18nCatalog.Definition definition : definitions) {
                VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(definition.file().toAbsolutePath().normalize());
                if (file == null || !file.isValid()) continue;
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile == null || psiFile.getTextLength() == 0) continue;
                result.add(definition);
            }
            return result;
        });

        if (available.size() == 1) {
            navigateToDefinition(project, available.get(0));
            return;
        }
        if (available.isEmpty()) {
            navigateToDefinition(project, definitions.get(0));
            return;
        }
        ListPopupStep<I18nCatalog.Definition> step = new BaseListPopupStep<>("选择译文位置", available) {
            @Override
            public String getTextFor(I18nCatalog.Definition definition) {
                return definition.locale() + "   " + definition.message();
            }

            @Override
            public PopupStep<?> onChosen(I18nCatalog.Definition definition, boolean finalChoice) {
                navigateToDefinition(project, definition);
                return PopupStep.FINAL_CHOICE;
            }
        };
        ListPopup popup = JBPopupFactory.getInstance().createListPopup(step);
        popup.showInScreenCoordinates(editor.getComponent(), screenPoint);
    }

    private static void navigateToDefinition(Project project, I18nCatalog.Definition definition) {
        navigateToFileOffset(project, definition.file(), definition.offset());
    }

    private static void navigateToFileOffset(Project project, java.nio.file.Path path, int offset) {
        VirtualFile target = LocalFileSystem.getInstance().findFileByNioFile(path.toAbsolutePath().normalize());
        if (target == null || !target.isValid()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed() || !target.isValid()) return;
            Editor targetEditor = FileEditorManager.getInstance(project)
                    .openTextEditor(new OpenFileDescriptor(project, target, 0), true);
            if (targetEditor == null || targetEditor.isDisposed()) return;
            FileEditorManager.getInstance(project).runWhenLoaded(targetEditor, () -> {
                if (targetEditor.isDisposed()) return;
                int targetOffset = Math.max(0, Math.min(offset, targetEditor.getDocument().getTextLength()));
                targetEditor.getCaretModel().moveToOffset(targetOffset);
                targetEditor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            });
        });
    }

    private static void scheduleLocalePopup(Project project, Editor editor, EditorMouseEvent event) {
        if (event.getMouseEvent().isControlDown()) {
            disposeLocaleHover(editor);
            return;
        }
        JBPopup activePopup = editor.getUserData(LOCALE_POPUP);
        TranslationKey key = translationCallAt(editor, offsetAtMouse(editor, event));
        Point screenPoint = popupLocation(event.getMouseEvent());
        if (activePopup != null && activePopup.isVisible()) {
            if (isInsideLocaleHoverRegion(editor, activePopup, screenPoint)) return;
            activePopup.cancel();
        }

        if (key == null) {
            stopLocaleHoverTimer(editor);
            editor.putUserData(LOCALE_HOVER_KEY, null);
            editor.putUserData(LOCALE_HOVER_POINT, null);
            editor.putUserData(LOCALE_ANCHOR_REGION, null);
            return;
        }

        String previousKey = editor.getUserData(LOCALE_HOVER_KEY);
        if (key.key().equals(previousKey)
                && editor.getUserData(LOCALE_HOVER_TIMER) != null
                && editor.getUserData(LOCALE_HOVER_TIMER).isRunning()) {
            editor.putUserData(LOCALE_HOVER_POINT, new Point(screenPoint));
            return;
        }

        stopLocaleHoverTimer(editor);
        scheduleLocaleHover(project, editor, key, screenPoint);
    }

    private static Point popupLocation(MouseEvent event) {
        Point point = event.getLocationOnScreen();
        return new Point(point.x + 4, point.y + 6);
    }

    private static void scheduleLocaleHover(Project project, Editor editor, TranslationKey key, Point screenPoint) {
        editor.putUserData(LOCALE_HOVER_KEY, key.key());
        editor.putUserData(LOCALE_HOVER_POINT, new Point(screenPoint));
        editor.putUserData(LOCALE_ANCHOR_REGION, localeAnchorRegion(editor, key.callStart(), key.callEnd()));
        Timer timer = new Timer(LOCALE_HOVER_DELAY_MS, ignored -> {
            Point latestPoint = editor.getUserData(LOCALE_HOVER_POINT);
            if (latestPoint != null) showLocalePopup(project, editor, key, latestPoint);
        });
        timer.setRepeats(false);
        editor.putUserData(LOCALE_HOVER_TIMER, timer);
        timer.start();
    }

    private static boolean isInsideLocaleHoverRegion(Editor editor, JBPopup popup, Point screenPoint) {
        Rectangle anchor = editor.getUserData(LOCALE_ANCHOR_REGION);
        if (anchor != null && anchor.contains(screenPoint)) return true;
        try {
            return new Rectangle(popup.getLocationOnScreen(), popup.getSize()).contains(screenPoint);
        } catch (IllegalComponentStateException ignored) {
            return false;
        }
    }

    private static Rectangle localeAnchorRegion(Editor editor, int startOffset, int endOffset) {
        Component content = editor.getContentComponent();
        if (!content.isShowing()) return null;
        try {
            Point origin = content.getLocationOnScreen();
            Point start = editor.visualPositionToXY(editor.offsetToVisualPosition(startOffset));
            Point end = editor.visualPositionToXY(editor.offsetToVisualPosition(endOffset));
            int height = editor.getLineHeight();
            if (start.y == end.y) {
                return new Rectangle(origin.x + start.x, origin.y + start.y,
                        Math.max(1, end.x - start.x), height);
            }
            return new Rectangle(origin.x, origin.y + start.y, content.getWidth(),
                    Math.max(height, end.y - start.y + height));
        } catch (IllegalComponentStateException ignored) {
            return null;
        }
    }

    private static void showLocalePopup(Project project, Editor editor, TranslationKey hoveredCall, Point screenPoint) {
        editor.putUserData(LOCALE_HOVER_TIMER, null);
        if (project.isDisposed() || editor.isDisposed() || !hoveredCall.key().equals(editor.getUserData(LOCALE_HOVER_KEY))) return;
        String basePath = project.getBasePath();
        if (basePath == null) return;
        List<I18nCatalog.Definition> translations = I18nCatalog.forProject(basePath).findTranslations(hoveredCall.key());
        if (translations.size() < 2) return;

        ListPopupStep<I18nCatalog.Definition> step = new BaseListPopupStep<>("Choose translation", translations) {
            @Override
            public String getTextFor(I18nCatalog.Definition translation) {
                return translation.locale() + "   " + translation.message();
            }

            @Override
            public PopupStep<?> onChosen(I18nCatalog.Definition translation, boolean finalChoice) {
                navigateToDefinition(project, translation);
                return PopupStep.FINAL_CHOICE;
            }
        };
        ListPopup popup = JBPopupFactory.getInstance().createListPopup(step);
        popup.setRequestFocus(false);
        editor.putUserData(LOCALE_POPUP, popup);
        editor.putUserData(LOCALE_ANCHOR_REGION,
                localeAnchorRegion(editor, hoveredCall.callStart(), hoveredCall.callEnd()));
        MouseAdapter popupHoverListener = new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent event) {
                if (editor.getUserData(LOCALE_POPUP) == popup
                        && !isInsideLocaleHoverRegion(editor, popup, event.getLocationOnScreen())) popup.cancel();
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                if (editor.getUserData(LOCALE_POPUP) == popup
                        && !isInsideLocaleHoverRegion(editor, popup, event.getLocationOnScreen())) popup.cancel();
            }
        };
        addPopupHoverListener(popup.getContent(), popupHoverListener);
        popup.addListener(new JBPopupListener() {
            @Override
            public void onClosed(com.intellij.openapi.ui.popup.LightweightWindowEvent event) {
                if (editor.getUserData(LOCALE_POPUP) == popup) {
                    editor.putUserData(LOCALE_POPUP, null);
                    editor.putUserData(LOCALE_HOVER_KEY, null);
                    editor.putUserData(LOCALE_HOVER_POINT, null);
                    editor.putUserData(LOCALE_ANCHOR_REGION, null);
                }
            }
        });
        popup.showInScreenCoordinates(editor.getComponent(), screenPoint);
    }

    private static void addPopupHoverListener(Component component, MouseAdapter listener) {
        component.addMouseListener(listener);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) addPopupHoverListener(child, listener);
        }
    }

    private static void stopLocaleHoverTimer(Editor editor) {
        Timer timer = editor.getUserData(LOCALE_HOVER_TIMER);
        if (timer != null) timer.stop();
        editor.putUserData(LOCALE_HOVER_TIMER, null);
    }

    private static void disposeLocaleHover(Editor editor) {
        stopLocaleHoverTimer(editor);
        JBPopup popup = editor.getUserData(LOCALE_POPUP);
        if (popup != null && popup.isVisible()) popup.cancel();
        editor.putUserData(LOCALE_POPUP, null);
        editor.putUserData(LOCALE_HOVER_KEY, null);
        editor.putUserData(LOCALE_HOVER_POINT, null);
        editor.putUserData(LOCALE_ANCHOR_REGION, null);
    }

    private static void updateNavigationHighlight(Project project, Editor editor, EditorMouseEvent event) {
        MouseEvent mouseEvent = event.getMouseEvent();
        updateNavigationHighlight(project, editor, offsetAtMouse(editor, event),
                !editor.isDisposed() && event.isOverText() && mouseEvent.isControlDown());
    }

    private static int offsetAtMouse(Editor editor, EditorMouseEvent event) {
        MouseEvent mouseEvent = event.getMouseEvent();
        Component source = mouseEvent.getComponent();
        Component content = editor.getContentComponent();
        if (source == null) return event.getOffset();
        Point point = SwingUtilities.convertPoint(source, mouseEvent.getPoint(), content);
        return editor.logicalPositionToOffset(editor.xyToLogicalPosition(point));
    }

    private static void updateNavigationHighlight(Project project, Editor editor, int offset, boolean controlDown) {
        if (editor.isDisposed() || !controlDown) {
            clearNavigationHighlight(editor);
            return;
        }
        String basePath = project.getBasePath();
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (basePath == null || file == null) {
            clearNavigationHighlight(editor);
            return;
        }
        I18nCatalog catalog = I18nCatalog.forProject(basePath);
        TranslationKey key;
        if (isVueFile(file)) {
            key = translationAt(editor, offset);
            if (key == null || catalog.findTranslations(key.key()).isEmpty()) {
                clearNavigationHighlight(editor);
                return;
            }
        } else if (isLocaleFile(file)) {
            I18nCatalog.Definition definition = catalog.findDefinitionAt(java.nio.file.Path.of(file.getPath()), offset);
            if (definition == null) {
                clearNavigationHighlight(editor);
                return;
            }
            key = new TranslationKey(definition.key(), definition.keyStart(), definition.keyEnd());
        } else {
            clearNavigationHighlight(editor);
            return;
        }
        RangeHighlighter existing = editor.getUserData(NAVIGATION_HIGHLIGHTER);
        if (existing != null && existing.isValid()
                && existing.getStartOffset() == key.start() && existing.getEndOffset() == key.end()) {
            setNavigationCursor(editor, true);
            return;
        }
        clearNavigationHighlight(editor);
        RangeHighlighter highlight = editor.getMarkupModel().addRangeHighlighter(
                key.start(), key.end(), HighlighterLayer.HYPERLINK, NAVIGATION_ATTRIBUTES, HighlighterTargetArea.EXACT_RANGE);
        editor.putUserData(NAVIGATION_HIGHLIGHTER, highlight);
        setNavigationCursor(editor, true);
    }

    private static void clearNavigationHighlight(Editor editor) {
        RangeHighlighter existing = editor.getUserData(NAVIGATION_HIGHLIGHTER);
        if (existing != null && existing.isValid()) editor.getMarkupModel().removeHighlighter(existing);
        editor.putUserData(NAVIGATION_HIGHLIGHTER, null);
        setNavigationCursor(editor, false);
    }

    private static void setNavigationCursor(Editor editor, boolean navigable) {
        Component content = editor.getContentComponent();
        Cursor previous = editor.getUserData(PREVIOUS_NAVIGATION_CURSOR);
        if (navigable) {
            if (previous == null) editor.putUserData(PREVIOUS_NAVIGATION_CURSOR, content.getCursor());
            content.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        } else if (previous != null) {
            content.setCursor(previous);
            editor.putUserData(PREVIOUS_NAVIGATION_CURSOR, null);
        }
    }

    private static TranslationKey translationAt(Editor editor, int offset) {
        return translationAt(editor, offset, false);
    }

    private static TranslationKey translationCallAt(Editor editor, int offset) {
        return translationAt(editor, offset, true);
    }

    private static TranslationKey translationAt(Editor editor, int offset, boolean matchWholeCall) {
        var document = editor.getDocument();
        if (offset < 0 || offset > document.getTextLength()) return null;
        int line = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        String source = document.getText(new TextRange(lineStart, lineEnd));
        int localOffset = offset - lineStart;
        Matcher matcher = TRANSLATION_CALL.matcher(source);
        while (matcher.find()) {
            int hitStart = matchWholeCall ? matcher.start() : matcher.start(2);
            int hitEnd = matchWholeCall ? matcher.end() : matcher.end(2);
            if (localOffset >= hitStart && localOffset <= hitEnd) {
                return new TranslationKey(matcher.group(2), lineStart + matcher.start(2),
                        lineStart + matcher.end(2), lineStart + matcher.start(), lineStart + matcher.end());
            }
        }
        return null;
    }

    private record TranslationKey(String key, int start, int end, int callStart, int callEnd) {
        private TranslationKey(String key, int start, int end) {
            this(key, start, end, start, end);
        }
    }
    private record PendingNavigation(int offset, boolean localeFile, Point screenPoint) { }

    private static boolean isVueEditor(Editor editor) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file != null && isVueFile(file);
    }

    private static boolean isVueFile(VirtualFile file) {
        return "vue".equalsIgnoreCase(file.getExtension());
    }

    private static boolean isLocaleFile(VirtualFile file) {
        if (!"json".equalsIgnoreCase(file.getExtension())) return false;
        String path = file.getPath().replace('\\', '/');
        return path.contains("/locales/");
    }

    static void refreshOpenEditors(Project project) {
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getProject() == project && !editor.isDisposed()) refreshEditor(project, editor);
        }
    }

    private static void refreshEditor(Project project, Editor editor) {
        if (!isVueEditor(editor)) return;
        var inlayModel = editor.getInlayModel();
        for (Inlay<?> inlay : inlayModel.getInlineElementsInRange(0, editor.getDocument().getTextLength(), TranslationRenderer.class)) {
            inlay.dispose();
        }
        if (!PropertiesComponent.getInstance(project).getBoolean(ENABLED_KEY, true)) return;

        String source = editor.getDocument().getText();
        String basePath = project.getBasePath();
        if (basePath == null) return;
        I18nCatalog catalog = I18nCatalog.forProject(basePath);
        String preferredLocale = PropertiesComponent.getInstance(project)
                .getValue(DISPLAY_LOCALE_KEY, DEFAULT_DISPLAY_LOCALE);
        Matcher matcher = TRANSLATION_CALL.matcher(source);
        while (matcher.find()) {
            String translation = catalog.find(matcher.group(2), preferredLocale);
            if (translation == null || translation.isBlank()) continue;
            int offset = matcher.end();
            inlayModel.addInlineElement(offset, true, new TranslationRenderer(translation));
        }
    }
}
