package ru.miacomsoft.sonar_gui.client;

import org.json.JSONObject;

/**
 * Слушатель Server-Sent Events (используется для /api/export-stream
 * и /api/stats/projects).
 *
 * ВАЖНО: все колбэки вызываются в потоке клиента (не в EDT).
 * Вызывающий код должен сам обернуть обновление UI в
 * SwingUtilities.invokeLater(...).
 */
public interface SseListener {

    /** Приходит на каждый блок "event: <name>\ndata: <json>\n\n". */
    void onEvent(String event, JSONObject data);

    /** Поток завершился штатно (последнее событие уже доставлено). */
    void onComplete();

    /** Ошибка сети/парсинга. После этого клиент бросает исключение наружу. */
    void onError(Throwable t);
}