package com.example.invoice.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Loads the {@code Map<feeId, feeType>} referential the
 * {@link com.example.invoice.mapper.einvoice.FeeTypeMatcher} matches against.
 *
 * <p>Reads from {@code t_fee_type} — a table this project does NOT create. It belongs to the
 * host invoice-service schema; adjust {@link #SQL} to match your column names. The only shape
 * requirement is two string columns: the id (referential key) and the type (human-facing fee
 * type name that the endpoint marker is matched against).
 *
 * <p>Wrapped by {@link CachingFeeTypeProvider} so the matcher sees a stable map instance for
 * the TTL window.
 */
public final class JdbcFeeTypeRepository {

  /** Adjust to your schema. Must yield (feeId, feeType) pairs. */
  private static final String SQL = "SELECT fee_id, fee_type FROM t_fee_type WHERE active = 'Y'";

  private final DataSource dataSource;

  public JdbcFeeTypeRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public Map<String, String> findAllFeeTypes() {
    Map<String, String> out = new LinkedHashMap<>();
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(SQL);
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.put(rs.getString(1), rs.getString(2));
      }
      return out;
    } catch (SQLException e) {
      throw new IllegalStateException("failed to load fee-type referential", e);
    }
  }
}
