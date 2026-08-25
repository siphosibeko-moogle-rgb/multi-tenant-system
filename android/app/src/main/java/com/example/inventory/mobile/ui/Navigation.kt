package com.example.inventory.mobile.ui

/**
 * The bottom-navigation destinations, and who sees which.
 *
 * <h2>Client-side role checks are for navigation ONLY</h2>
 *
 * Hiding a tab is a courtesy, not a control. The backend enforces every
 * permission with `@PreAuthorize`, and `ForecastRoleTest` asserts each gate from
 * both sides — so a clerk who reached a reorder action by any means still gets a
 * 403. Nothing here is load-bearing for security, and nothing here should ever
 * become the only thing standing between a role and an action.
 *
 * What it does buy is not showing somebody a tab that leads to a wall. A clerk
 * tapping "Reorders" and getting "Your account does not have permission to do
 * that" learns nothing they can act on; the honest interface simply does not
 * offer it.
 *
 * <h2>An unknown role sees the least, not the most</h2>
 *
 * [tabsFor] treats a null role — the state on a cold start, before `GET /me`
 * returns — as the narrowest set. The alternative is showing the full set and
 * removing tabs a moment later, which reads as the app taking something away,
 * and briefly offers a clerk a destination that will refuse them.
 */
enum class Tab(val label: String) {
    HOME("Home"),
    SELL("Sell"),
    STOCK("Stock"),
    REORDERS("Reorders"),
    MORE("More"),
}

/**
 * Which tabs a role sees.
 *
 * Owner and manager get Reorders because reordering is a purchasing decision
 * and the backend gates both the recompute and the dismiss to those two roles.
 * Clerk and viewer do not — a clerk's listed duties are recording sales,
 * receiving stock and counting, and a viewer is read-only.
 *
 * Everyone gets Home, Sell, Stock and More. Sell for a viewer is deliberate:
 * the screen is the product list, which a viewer may read, and the sale itself
 * is refused by the server if they attempt it. Hiding the catalogue from a
 * read-only role would withhold the thing they are explicitly allowed to see.
 */
fun tabsFor(role: String?): List<Tab> = when (role?.lowercase()) {
    "owner", "manager" -> listOf(Tab.HOME, Tab.SELL, Tab.STOCK, Tab.REORDERS, Tab.MORE)
    else -> listOf(Tab.HOME, Tab.SELL, Tab.STOCK, Tab.MORE)
}

/**
 * Whether this role may manage users.
 *
 * Owner only, matching the backend: the contract's own section header says
 * "Users (owner/manager only)" while its `UserRole` table gives manager no
 * users permission, and CLAUDE.md §13 records that the narrower reading won —
 * `POST`, `PATCH` and `DELETE` on `/users` are all owner-gated because every one
 * of them is a privilege-escalation path.
 *
 * `GET /users` alone does allow manager. The More menu still shows the entry to
 * owners only, because the screen it opens is where inviting and removing live;
 * offering a manager a read-only view of it would be a different screen, not
 * this one.
 */
fun canManageUsers(role: String?): Boolean = role?.lowercase() == "owner"
