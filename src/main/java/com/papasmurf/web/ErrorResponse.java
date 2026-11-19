package com.papasmurf.web;

/**
 * Унифицированный ответ об ошибке. Клиент всегда может проверить поле
 * {@code ok} и не разбирать текст сообщения.
 */
public class ErrorResponse {

    public boolean ok = false;

    /** Машиночитаемый код: INVALID_STEAM_ID, INVALID_DEPTH, NO_DATA, INTERNAL. */
    public String code;

    /** Человекочитаемое объяснение (по-русски, для показа в Discord). */
    public String error;

    public ErrorResponse(String code, String error) {
        this.code = code;
        this.error = error;
    }
}
