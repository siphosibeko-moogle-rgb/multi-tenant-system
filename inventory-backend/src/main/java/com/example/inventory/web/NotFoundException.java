package com.example.inventory.web;

/**
 * The resource does not exist <em>in this tenant</em>. Rendered as 404.
 *
 * <p>Also what another tenant's resource returns — never 403 (CLAUDE.md T8). A
 * 403 would confirm that the id exists somewhere, which is enough to enumerate
 * ids across tenants. In practice this rarely has to be remembered: RLS filters
 * the row out, so the lookup genuinely finds nothing.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
