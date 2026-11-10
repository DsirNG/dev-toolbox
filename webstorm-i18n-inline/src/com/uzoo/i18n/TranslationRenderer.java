package com.uzoo.i18n;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

final class TranslationRenderer implements EditorCustomElementRenderer {
    private final String text;

    TranslationRenderer(String text) {
        this.text = "  " + text;
    }

    @Override
    public int calcWidthInPixels(Inlay inlay) {
        Editor editor = inlay.getEditor();
        return editor.getContentComponent().getFontMetrics(translationFont(editor)).stringWidth(text);
    }

    @Override
    public void paint(Inlay inlay, Graphics2D graphics, Rectangle2D target, TextAttributes attributes) {
        Editor editor = inlay.getEditor();
        var scheme = editor.getColorsScheme();
        int x = (int) target.getX();
        int y = (int) target.getY();
        int height = (int) target.getHeight();
        graphics.setFont(translationFont(editor));
        graphics.setColor(new Color(135, 145, 155));
        graphics.drawString(text, x, y + editor.getAscent());
    }

    private static Font translationFont(Editor editor) {
        Font editorFont = editor.getColorsScheme().getFont(EditorFontType.PLAIN);
        return new Font("Microsoft YaHei", editorFont.getStyle(), editorFont.getSize())
                .deriveFont(editorFont.getSize2D());
    }
}
