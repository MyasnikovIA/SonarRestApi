package ru.miacomsoft.sonar_gui.common;

/**
 * Интерфейс для панелей вкладок, которые умеют перезагружать данные.
 * Части 3 и 4 будут реализовывать его для Per-File и Report.
 */
public interface Reloadable {
    void reload();
}