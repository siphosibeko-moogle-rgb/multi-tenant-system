package com.example.inventory.web;

/**
 * Authentication failed. Rendered as 401.
 *
 * <p>Every construction site for this in the auth package passes the same
 * message for several different underlying causes — unknown email, wrong
 * password, disabled account, replayed refresh token. That is deliberate: the
 * distinctions are exactly what an attacker enumerating accounts wants, and the
 * legitimate client has nothing useful to do with them either.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
