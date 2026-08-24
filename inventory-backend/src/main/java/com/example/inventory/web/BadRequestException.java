package com.example.inventory.web;

/**
 * A request the server understood and refuses because it is malformed — 400.
 *
 * <p>Distinct from the 422 that {@code MethodArgumentNotValidException} produces.
 * That one means "the body's shape is right and a field's value is not", and it
 * carries a per-field error list. This one is for a parameter that cannot be
 * interpreted at all, where there is no field list to give and the useful
 * response is a sentence naming the accepted values.
 *
 * <p>Added in the Android pass, for a concrete reason: {@code GET
 * /reorder-recommendations?status=…} passed its value straight into
 * {@code CAST(? AS recommendation_status)}, so an unknown status raised SQLSTATE
 * 22P02 and surfaced as a 500. A bad query parameter is the caller's problem to
 * fix and the server should say so — a 500 tells them to wait and retry, which
 * would never work.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
