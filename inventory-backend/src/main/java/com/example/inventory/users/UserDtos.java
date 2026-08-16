package com.example.inventory.users;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.inventory.auth.AuthDtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Shapes for {@code /users}, mirroring {@code docs/openapi.yaml}.
 *
 * <p>As with {@link AuthDtos}, note the absence: no request or response here
 * carries a tenant id. The tenant is the caller's, taken from the token (T1).
 */
public final class UserDtos {

    private UserDtos() {
    }

    /** The four roles from the contract's {@code UserRole} enum. */
    public static final List<String> ROLES = List.of("owner", "manager", "clerk", "viewer");

    public record User(
            UUID id,
            String email,
            String fullName,
            String role,
            String status,
            OffsetDateTime lastLoginAt) {
    }

    public record UserInviteRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Pattern(regexp = "owner|manager|clerk|viewer",
                    message = "must be one of owner, manager, clerk, viewer")
            String role) {
    }

    public record UserUpdateRequest(
            @Pattern(regexp = "owner|manager|clerk|viewer",
                    message = "must be one of owner, manager, clerk, viewer")
            String role,
            @Pattern(regexp = "active|disabled", message = "must be active or disabled")
            String status,
            @Size(max = 200) String fullName) {
    }

    /**
     * A page of users.
     *
     * @param nextCursor null on the last page. Cursor, never offset — offsets
     *                   skip and duplicate rows under concurrent writes
     *                   (CLAUDE.md §4).
     */
    public record UserPage(List<User> items, String nextCursor) {
    }
}
