package ru.mephi.shortener.infra;

import ru.mephi.shortener.core.Shortener.Browser;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

public final class DesktopBrowser implements Browser {
    @Override
    public void browse(URI uri) throws IOException {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw new IOException("Система не поддерживает открытие браузера из Java Desktop");
            }
            Desktop.getDesktop().browse(uri);
        } catch (UnsupportedOperationException | SecurityException ex) {
            throw new IOException("Не удалось открыть браузер", ex);
        }
    }
}
