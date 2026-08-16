package com.example.inventory.users;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.users.UserDtos.User;
import com.example.inventory.users.UserDtos.UserInviteRequest;
import com.example.inventory.users.UserDtos.UserPage;
import com.example.inventory.users.UserDtos.UserUpdateRequest;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

/**
 * User administration within the caller's tenant.
 *
 * <h2>The role split, and a contradiction in the contract</h2>
 *
 * <p>{@code docs/openapi.yaml} says two different things about who may manage
 * users:
 *
 * <ul>
 *   <li>the section header above {@code /users} reads
 *       <em>"Users (owner/manager only)"</em>;</li>
 *   <li>the {@code UserRole} description gives {@code owner} "billing, users,
 *       everything", and lists {@code manager} as "catalog, purchasing, reports,
 *       adjustments" — no users.</li>
 * </ul>
 *
 * <p>Rather than silently picking one (CLAUDE.md §1 says to say so), this splits
 * along the line the two statements agree on and takes the safer reading where
 * they do not:
 *
 * <table border="1">
 * <caption>Gates</caption>
 * <tr><th>Endpoint</th><th>Allowed</th><th>Why</th></tr>
 * <tr><td>{@code GET /users}</td><td>owner, manager</td>
 *     <td>Reading the team is what the section header plainly permits, and it
 *         grants nothing.</td></tr>
 * <tr><td>{@code POST /users}</td><td>owner</td>
 *     <td>Invite takes a role. A manager who could invite could invite an owner
 *         — privilege escalation in one call.</td></tr>
 * <tr><td>{@code PATCH /users/{id}}</td><td>owner</td>
 *     <td>Changes a role. A manager who could PATCH could promote themselves.</td></tr>
 * <tr><td>{@code DELETE /users/{id}}</td><td>owner</td>
 *     <td>A manager who could deactivate could disable every owner and take the
 *         business.</td></tr>
 * </table>
 *
 * <p>Each mutation is an escalation path, which is why they are owner-only until
 * somebody confirms otherwise. Widening later is a one-line change; discovering
 * a manager promoted themselves is not.
 *
 * <p>{@code clerk} and {@code viewer} reach none of this, which
 * {@code RoleEnforcementTest} asserts explicitly rather than leaving to the
 * absence of a happy-path test.
 */
@RestController
@RequestMapping("/users")
public class UsersController {

    private final UserDirectory users;

    public UsersController(UserDirectory users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<UserPage> list(@RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String status) {
        return ResponseEntity.ok(users.list(cursor, limit, status));
    }

    @PostMapping
    @PreAuthorize("hasRole('owner')")
    ResponseEntity<User> invite(@Valid @RequestBody UserInviteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(users.invite(request));
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasRole('owner')")
    ResponseEntity<User> update(@PathVariable UUID userId,
                                @Valid @RequestBody UserUpdateRequest request) {
        // A user in another tenant is invisible to the query, so this is a 404
        // rather than a 403 — T8, and here it falls out of RLS rather than
        // having to be remembered.
        return users.update(userId, request)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("No such user"));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('owner')")
    ResponseEntity<Void> deactivate(@PathVariable UUID userId) {
        if (!users.deactivate(userId)) {
            throw new NotFoundException("No such user");
        }
        return ResponseEntity.noContent().build();
    }
}
