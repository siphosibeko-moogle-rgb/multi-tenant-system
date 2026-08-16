package com.example.inventory.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.catalog.CatalogDtos.Category;
import com.example.inventory.catalog.CatalogDtos.CategoryWriteRequest;
import com.example.inventory.catalog.CatalogDtos.Location;
import com.example.inventory.catalog.CatalogDtos.LocationWriteRequest;
import com.example.inventory.catalog.CatalogDtos.Product;
import com.example.inventory.catalog.CatalogDtos.ProductPage;
import com.example.inventory.catalog.CatalogDtos.ProductWriteRequest;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

/**
 * The catalogue: products, categories and locations.
 *
 * <h2>Role gates</h2>
 *
 * <p>Writes are {@code owner} and {@code manager} — the {@code UserRole} table
 * gives manager "catalog" explicitly. Reads are open to every role, including
 * {@code viewer} ("read only") and {@code clerk}, who cannot sell what they
 * cannot look up. Asserted both ways in {@code CatalogRoleTest}.
 *
 * <h2>ETag and If-Match</h2>
 *
 * <p>{@code GET /products/{id}} returns an {@code ETag}. {@code PATCH} accepts
 * it back as {@code If-Match} and returns 412 if the row has moved on.
 *
 * <p>{@code If-Match} is optional, matching the contract, which does not mark
 * the parameter required. Omitting it is last-write-wins — appropriate for a
 * single-user shop and for a client that has just created the row, and a
 * deliberate choice rather than an oversight. The Android client sends it.
 */
@RestController
public class CatalogController {

    private final ProductCatalog catalog;

    public CatalogController(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------
    // Products
    // ------------------------------------------------------------------

    @GetMapping("/products")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<ProductPage> list(@RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) UUID categoryId,
                                     @RequestParam(required = false) Boolean active) {
        return ResponseEntity.ok(catalog.list(cursor, limit, q, categoryId, active));
    }

    /**
     * Barcode lookup.
     *
     * <p>Mapped before {@code /products/{productId}} would match, since
     * "lookup" is not a UUID and Spring resolves the more specific literal path
     * first — but the ordering is worth knowing about if a non-UUID id type is
     * ever introduced.
     */
    @GetMapping("/products/lookup")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<Product> lookup(@RequestParam String barcode) {
        return catalog.findByBarcode(barcode)
                .map(CatalogController::withETag)
                .orElseThrow(() -> new NotFoundException("No product with that barcode"));
    }

    @GetMapping("/products/{productId}")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<Product> get(@PathVariable UUID productId) {
        // Another tenant's product id is simply not found — RLS filtered the row
        // out, so this is a 404 rather than a 403 (T8) without special handling.
        return catalog.find(productId)
                .map(CatalogController::withETag)
                .orElseThrow(() -> new NotFoundException("No such product"));
    }

    @PostMapping("/products")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<Product> create(@Valid @RequestBody ProductWriteRequest request) {
        Versioned<Product> created = catalog.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(created.etag())
                .body(created.value());
    }

    @PatchMapping("/products/{productId}")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<Product> update(@PathVariable UUID productId,
                                   @Valid @RequestBody ProductWriteRequest request,
                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                   String ifMatch) {
        return withETag(catalog.update(productId, request, ifMatch));
    }

    @DeleteMapping("/products/{productId}")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<Void> deactivate(@PathVariable UUID productId) {
        if (!catalog.deactivate(productId)) {
            throw new NotFoundException("No such product");
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Categories and locations
    // ------------------------------------------------------------------

    @GetMapping("/categories")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<List<Category>> categories() {
        return ResponseEntity.ok(catalog.categories());
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<Category> createCategory(@Valid @RequestBody CategoryWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createCategory(request));
    }

    @GetMapping("/locations")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<List<Location>> locations() {
        return ResponseEntity.ok(catalog.locations());
    }

    @PostMapping("/locations")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<Location> createLocation(@Valid @RequestBody LocationWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createLocation(request));
    }

    /**
     * Emits the row version as an {@code ETag} header rather than a body field.
     * The contract's Product schema has no version property, and HTTP already
     * has the right place for one.
     */
    private static ResponseEntity<Product> withETag(Versioned<Product> product) {
        return ResponseEntity.ok().eTag(product.etag()).body(product.value());
    }
}
