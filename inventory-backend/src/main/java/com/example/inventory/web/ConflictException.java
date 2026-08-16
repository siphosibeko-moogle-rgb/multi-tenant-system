package com.example.inventory.web;

/**
 * The request conflicts with existing state. Rendered as 409.
 *
 * @param problemType stable slug for the {@code type} URI, so the client can
 *                    branch on the cause without parsing English
 */
public class ConflictException extends RuntimeException {

    private final String problemType;

    public ConflictException(String message, String problemType) {
        super(message);
        this.problemType = problemType;
    }

    public String problemType() {
        return problemType;
    }
}
