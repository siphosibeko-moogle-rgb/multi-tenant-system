package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which lead time a reorder point should use — {@code docs/adr/forecasting.md}
 * §2.
 *
 * <h2>Measured beats promised, once there is enough of it</h2>
 *
 * <p>The observed average from {@code supplier_lead_time_observations} wins when
 * the supplier has at least {@link #MIN_OBSERVATIONS} of them. Below that, the
 * promised figure.
 *
 * <p>The direction matters and is easy to get backwards. Promised lead times are
 * optimistic; measured ones are not. Preferring the promised number whenever it
 * happens to be present would mean the system mostly repeats what the supplier
 * said instead of what the supplier did — which is the opposite of what
 * {@code supplier_lead_time_observations} exists for. The promised figure is a
 * fallback for a relationship too new to have evidence, not a default.
 *
 * <p><strong>Why 5 and not M5's three.</strong> M5's three-receipt criterion
 * proved the observation pipeline populates at all. Trusting an average enough
 * to set a reorder point from it is a higher bar, because being wrong here costs
 * a real stockout or real excess stock rather than a wrong number on a details
 * screen. At n=3 one unusually slow delivery is a third of the sample; at n=5 it
 * is a fifth.
 *
 * <h2>A third source the ADR does not mention — flagged, not silently resolved</h2>
 *
 * <p>ADR §2 describes two sources: observed, and {@code suppliers.lead_time_days}.
 * The schema has a third — {@code product_suppliers.lead_time_days}, commented in
 * {@code V1} as "overrides supplier default". Per CLAUDE.md §1 that disagreement
 * is raised rather than quietly picked over.
 *
 * <p>Resolved as a precedence that honours both, because they are answering
 * different questions:
 *
 * <ol>
 *   <li><strong>Observed</strong> average, at n ≥ 5. Necessarily per-supplier —
 *       {@code supplier_lead_time_observations} is keyed on
 *       {@code (tenant_id, supplier_id)} with no product, so there is no
 *       per-product measured figure to prefer even in principle.</li>
 *   <li><strong>{@code product_suppliers.lead_time_days}</strong>, if set. A
 *       promised figure, but a more specific one — a supplier that ships most
 *       lines in a week and one particular line in a month.</li>
 *   <li><strong>{@code suppliers.lead_time_days}</strong>. The general promise.</li>
 * </ol>
 *
 * <p>So the ADR's rule is unchanged — measured first, promised as fallback — and
 * the override refines the promised tier rather than competing with the measured
 * one. Reading it as outranking observations would let a stale hand-typed number
 * silently beat evidence, which is precisely the inversion §2 exists to prevent.
 */
@Component
public class LeadTimeResolver {

    /** ADR §2: below this many observations the measured average is not trusted. */
    static final int MIN_OBSERVATIONS = 5;

    private final JdbcTemplate jdbc;

    public LeadTimeResolver(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    /** Where a lead time came from — carried so the explanation can be truthful. */
    public enum Source {
        /** Measured from real ordered→received intervals. */
        OBSERVED,
        /** The supplier's promise, refined for this product. */
        PROMISED_FOR_PRODUCT,
        /** The supplier's general promise. */
        PROMISED_BY_SUPPLIER
    }

    /**
     * @param sampleSize observations behind an {@code OBSERVED} figure; 0 otherwise
     */
    public record LeadTime(UUID supplierId, String supplierName, BigDecimal days,
                          Source source, int sampleSize) {

        public boolean isObserved() {
            return source == Source.OBSERVED;
        }
    }

    /**
     * The lead time for a product, or empty when the product has no supplier on
     * file.
     *
     * <p>Empty is a real answer, not a failure. A product nobody has recorded a
     * supplier for has no lead time, and therefore no honest reorder point —
     * {@code ReorderPointCalculator} withholds the number rather than
     * substituting a default, because a made-up lead time produces a made-up
     * reorder point that looks exactly like a real one.
     */
    public Optional<LeadTime> forProduct(UUID productId) {
        List<PreferredSupplier> candidates = jdbc.query("""
                SELECT ps.supplier_id,
                       s.name                AS supplier_name,
                       ps.lead_time_days     AS product_lead_time,
                       s.lead_time_days      AS supplier_lead_time
                FROM product_suppliers ps
                JOIN suppliers s
                  ON s.tenant_id = ps.tenant_id AND s.id = ps.supplier_id
                WHERE ps.product_id = ?
                  AND s.deleted_at IS NULL
                  AND s.is_active
                ORDER BY ps.is_preferred DESC, ps.created_at
                """,
                (rs, rowNum) -> new PreferredSupplier(
                        rs.getObject("supplier_id", UUID.class),
                        rs.getString("supplier_name"),
                        rs.getObject("product_lead_time", Integer.class),
                        rs.getInt("supplier_lead_time")),
                productId);

        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // Preferred first, then oldest link. A product with several suppliers
        // and none marked preferred still needs one deterministic answer, or the
        // reorder point would change between runs for no visible reason.
        PreferredSupplier chosen = candidates.get(0);

        Observations observed = observationsFor(chosen.supplierId());
        if (observed.count() >= MIN_OBSERVATIONS) {
            return Optional.of(new LeadTime(chosen.supplierId(), chosen.supplierName(),
                    observed.averageDays(), Source.OBSERVED, observed.count()));
        }
        if (chosen.productLeadTime() != null) {
            return Optional.of(new LeadTime(chosen.supplierId(), chosen.supplierName(),
                    BigDecimal.valueOf(chosen.productLeadTime()),
                    Source.PROMISED_FOR_PRODUCT, observed.count()));
        }
        return Optional.of(new LeadTime(chosen.supplierId(), chosen.supplierName(),
                BigDecimal.valueOf(chosen.supplierLeadTime()),
                Source.PROMISED_BY_SUPPLIER, observed.count()));
    }

    /** Observation count and mean for one supplier. */
    public Observations observationsFor(UUID supplierId) {
        return jdbc.queryForObject("""
                SELECT count(*)                          AS n,
                       COALESCE(avg(lead_time_days), 0)  AS average_days
                FROM supplier_lead_time_observations
                WHERE supplier_id = ?
                """,
                (rs, rowNum) -> new Observations(rs.getInt("n"), rs.getBigDecimal("average_days")),
                supplierId);
    }

    public record Observations(int count, BigDecimal averageDays) {
    }

    private record PreferredSupplier(UUID supplierId, String supplierName,
                                     Integer productLeadTime, int supplierLeadTime) {
    }
}
