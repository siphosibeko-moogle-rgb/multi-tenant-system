package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.inventory.forecasting.DemandSeries.Day;

/**
 * Reads {@code demand_daily} back out for one product/location, or for every
 * product/location the bound tenant has.
 *
 * <p>Hand-written SQL per CLAUDE.md §4, and read through the RLS-bound
 * application pool — there is no {@code WHERE tenant_id = ?} here because the
 * policy already applies one, and adding a second would be the convenience
 * clause T2 warns about being mistaken for the defence.
 *
 * <p>Ordered by day, ascending, always. {@link DemandSeries} treats the first
 * and last elements as the ends of the calendar span and walks the list in order
 * to fit a trend; an unordered list would produce a plausible-looking wrong
 * answer rather than an error.
 */
@Repository
public class DemandSeriesRepository {

    private final JdbcTemplate jdbc;

    public DemandSeriesRepository(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    /** Empty series (no rows) if the product has never been rolled up. */
    public DemandSeries load(UUID productId, UUID locationId) {
        List<Day> days = jdbc.query("""
                SELECT day, units_sold, had_stockout
                FROM demand_daily
                WHERE product_id = ? AND location_id = ?
                ORDER BY day
                """,
                (rs, rowNum) -> new Day(
                        rs.getObject("day", java.time.LocalDate.class),
                        rs.getBigDecimal("units_sold"),
                        rs.getBoolean("had_stockout")),
                productId, locationId);
        return new DemandSeries(productId, locationId, days);
    }

    /**
     * Every product/location pair with any demand history, as one series each.
     *
     * <p>One query rather than one per product: a tenant with a few thousand
     * products would otherwise open a few thousand round trips per recompute.
     * The rows arrive grouped and ordered, and are cut into series in memory.
     */
    public List<DemandSeries> loadAll() {
        record Row(UUID productId, UUID locationId, Day day) {
        }
        List<Row> rows = jdbc.query("""
                SELECT product_id, location_id, day, units_sold, had_stockout
                FROM demand_daily
                ORDER BY product_id, location_id, day
                """,
                (rs, rowNum) -> new Row(
                        rs.getObject("product_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        new Day(rs.getObject("day", java.time.LocalDate.class),
                                rs.getBigDecimal("units_sold"),
                                rs.getBoolean("had_stockout"))));

        List<DemandSeries> series = new ArrayList<>();
        UUID currentProduct = null;
        UUID currentLocation = null;
        List<Day> current = new ArrayList<>();
        for (Row row : rows) {
            boolean sameSeries = row.productId().equals(currentProduct)
                    && row.locationId().equals(currentLocation);
            if (!sameSeries) {
                if (currentProduct != null) {
                    series.add(new DemandSeries(currentProduct, currentLocation, List.copyOf(current)));
                }
                currentProduct = row.productId();
                currentLocation = row.locationId();
                current = new ArrayList<>();
            }
            current.add(row.day());
        }
        if (currentProduct != null) {
            series.add(new DemandSeries(currentProduct, currentLocation, List.copyOf(current)));
        }
        return series;
    }

    /** Current on-hand balance, for days-of-cover and the reorder comparison. */
    public BigDecimal quantityOnHand(UUID productId, UUID locationId) {
        BigDecimal onHand = jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity_on_hand), 0) FROM product_stock
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, productId, locationId);
        return onHand == null ? BigDecimal.ZERO : onHand;
    }
}
