package com.example.inventory.suppliers;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.suppliers.SupplierDtos.ObservedLeadTime;
import com.example.inventory.suppliers.SupplierDtos.SupplierDetail;

/**
 * Only enough of the suppliers resource to make M5's lead-time criterion
 * testable over HTTP (CLAUDE.md §5): {@code GET /suppliers/{supplierId}},
 * returning {@code SupplierDetail.observedLeadTime}. Full supplier CRUD
 * (create, update, list) is declared in the contract but out of M5's scope
 * and stays unbuilt — a caller needing one today seeds a row directly, the
 * same way every fixture in this codebase seeds tenants and products.
 */
@Service
public class SupplierService {

    private final JdbcTemplate jdbc;

    public SupplierService(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    public Optional<SupplierDetail> read(UUID supplierId) {
        var suppliers = jdbc.query("""
                SELECT id, name, contact_name, email, phone, lead_time_days,
                       min_order_value, is_active, address, notes
                FROM suppliers WHERE id = ? AND deleted_at IS NULL
                """, (rs, i) -> new Object[] {
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("contact_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getInt("lead_time_days"),
                        rs.getBigDecimal("min_order_value"),
                        rs.getBoolean("is_active"),
                        rs.getString("address"),
                        rs.getString("notes"),
                }, supplierId);

        if (suppliers.isEmpty()) {
            return Optional.empty();
        }
        Object[] s = suppliers.get(0);

        ObservedLeadTime observed = jdbc.queryForObject("""
                SELECT count(*) AS n,
                       avg(lead_time_days) AS avg_days,
                       stddev_samp(lead_time_days) AS stddev_days
                FROM supplier_lead_time_observations
                WHERE supplier_id = ?
                """, (rs, i) -> new ObservedLeadTime(
                        rs.getBigDecimal("avg_days"),
                        rs.getBigDecimal("stddev_days"),
                        rs.getInt("n"),
                        null), supplierId);

        int productCount = jdbc.queryForObject(
                "SELECT count(*) FROM product_suppliers WHERE supplier_id = ?",
                Integer.class, supplierId);

        return Optional.of(new SupplierDetail(
                (UUID) s[0], (String) s[1], (String) s[2], (String) s[3], (String) s[4],
                (Integer) s[5], (BigDecimal) s[6], (Boolean) s[7], (String) s[8], (String) s[9],
                observed, productCount));
    }
}
