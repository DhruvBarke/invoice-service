package com.sg.jpa.adapter;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.QuarantineRecord;
import com.sg.domaininterface.port.out.QuarantineStatus;
import com.sg.domaininterface.port.out.QuarantineStore;
import com.sg.domaininterface.port.out.RecordCodec;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.Servability;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Plain JDBC {@link QuarantineStore}. No ORM, so it drops into any stack.
 *
 * <p>DDL lives in {@code invoice-service-app/src/main/resources/db/migration}.
 */
public final class JdbcQuarantineStore implements QuarantineStore {

    private static final String COLUMNS = """
            id, key_space, lookup_key, anomaly_fingerprint, anomaly_types, servability,
            raw_payload, corrected_payload, status, detected_at, updated_at, notified_at,
            corrected_by, notes""";

    private final DataSource dataSource;
    private final RecordCodec codec;

    public JdbcQuarantineStore(DataSource dataSource, RecordCodec codec) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public Optional<QuarantineRecord> findActive(String keySpace, String lookupKey) {
        String sql = "SELECT " + COLUMNS + " FROM party_registration_quarantine "
                + "WHERE key_space = ? AND lookup_key = ? AND active_flag = 'Y'";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, keySpace);
            ps.setString(2, lookupKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new QuarantineStoreException("findActive failed", e);
        }
    }

    @Override
    public UpsertResult upsert(QuarantineRecord record) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Optional<QuarantineRecord> existing = findActiveForUpdate(c, record);

                if (existing.isEmpty()) {
                    QuarantineRecord inserted = insert(c, record);
                    c.commit();
                    return new UpsertResult(inserted, true);   // new defect: always notify
                }

                QuarantineRecord prior = existing.get();
                boolean sameDefect = prior.fingerprint().equals(record.fingerprint());
                QuarantineRecord updated = update(c, prior.id(), record, sameDefect);
                c.commit();
                return new UpsertResult(updated, !sameDefect || !prior.alreadyNotified());

            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new QuarantineStoreException("upsert failed", e);
        }
    }

    /** {@code FOR UPDATE}: two instances detecting the same defect concurrently must not both insert. */
    private Optional<QuarantineRecord> findActiveForUpdate(Connection c, QuarantineRecord r)
            throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM party_registration_quarantine "
                + "WHERE key_space = ? AND lookup_key = ? AND active_flag = 'Y' FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, r.keySpace());
            ps.setString(2, r.lookupKey());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private QuarantineRecord insert(Connection c, QuarantineRecord r) throws SQLException {
        String sql = """
                INSERT INTO party_registration_quarantine
                  (key_space, lookup_key, active_flag, anomaly_fingerprint, anomaly_types,
                   servability, raw_payload, corrected_payload, status, detected_at, updated_at,
                   notified_at, corrected_by, notes)
                VALUES (?, ?, 'Y', ?, ?, ?, ?, NULL, ?, ?, ?, NULL, NULL, NULL)""";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            Instant now = Instant.now();
            ps.setString(1, r.keySpace());
            ps.setString(2, r.lookupKey());
            ps.setString(3, r.fingerprint());
            ps.setString(4, joinTypes(r.anomalyTypes()));
            ps.setString(5, r.servability().name());
            ps.setString(6, codec.serialize(r.rawPayload()));
            ps.setString(7, QuarantineStatus.PENDING.name());
            ps.setTimestamp(8, Timestamp.from(now));
            ps.setTimestamp(9, Timestamp.from(now));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                Long id = keys.next() ? keys.getLong(1) : null;
                return new QuarantineRecord(id, r.keySpace(), r.lookupKey(), r.fingerprint(),
                        r.anomalyTypes(), r.servability(), r.rawPayload(), null,
                        QuarantineStatus.PENDING, now, now, null, null, null);
            }
        }
    }

    /**
     * Same fingerprint: preserve {@code notified_at} and any correction, so a recurring defect stays
     * silent and a correction keeps applying. Different fingerprint: the value itself changed, so
     * clear {@code notified_at} to re-notify and drop the correction, which was written against a
     * value that no longer exists.
     */
    private QuarantineRecord update(Connection c, Long id, QuarantineRecord r, boolean sameDefect)
            throws SQLException {
        String sql = """
                UPDATE party_registration_quarantine
                   SET anomaly_fingerprint = ?, anomaly_types = ?, servability = ?, raw_payload = ?,
                       updated_at = ?,
                       notified_at = CASE WHEN ? = 'Y' THEN notified_at ELSE NULL END,
                       corrected_payload = CASE WHEN ? = 'Y' THEN corrected_payload ELSE NULL END,
                       status = CASE WHEN ? = 'Y' THEN status ELSE ? END
                 WHERE id = ?""";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Instant now = Instant.now();
            String same = sameDefect ? "Y" : "N";
            ps.setString(1, r.fingerprint());
            ps.setString(2, joinTypes(r.anomalyTypes()));
            ps.setString(3, r.servability().name());
            ps.setString(4, codec.serialize(r.rawPayload()));
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setString(6, same);
            ps.setString(7, same);
            ps.setString(8, same);
            ps.setString(9, QuarantineStatus.PENDING.name());
            ps.setLong(10, id);
            ps.executeUpdate();
        }
        return findById(c, id).orElseThrow();
    }

    @Override
    public void markNotified(long id, Instant notifiedAt) {
        execute("UPDATE party_registration_quarantine SET notified_at = ?, updated_at = ? WHERE id = ?",
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(notifiedAt));
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setLong(3, id);
                }, "markNotified");
    }

    @Override
    public QuarantineRecord applyCorrection(long id, List<PartyRegistrationDetails> corrected,
                                            String correctedBy, String notes) {
        String sql = """
                UPDATE party_registration_quarantine
                   SET corrected_payload = ?, status = ?, corrected_by = ?, notes = ?, updated_at = ?
                 WHERE id = ? AND active_flag = 'Y'""";
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, codec.serialize(corrected));
                ps.setString(2, QuarantineStatus.CORRECTED.name());
                ps.setString(3, correctedBy);
                ps.setString(4, notes);
                ps.setTimestamp(5, Timestamp.from(Instant.now()));
                ps.setLong(6, id);
                if (ps.executeUpdate() == 0) {
                    throw new QuarantineStoreException("no active quarantine row with id " + id, null);
                }
            }
            return findById(c, id).orElseThrow();
        } catch (SQLException e) {
            throw new QuarantineStoreException("applyCorrection failed", e);
        }
    }

    @Override
    public void softDelete(long id, String deletedBy) {
        // active_flag becomes NULL, releasing the unique index so a future defect on the same key can
        // open a fresh row while this one survives as history.
        execute("""
                UPDATE party_registration_quarantine
                   SET status = ?, active_flag = NULL, corrected_by = ?, updated_at = ?
                 WHERE id = ?""",
                ps -> {
                    ps.setString(1, QuarantineStatus.SOFT_DELETED.name());
                    ps.setString(2, deletedBy);
                    ps.setTimestamp(3, Timestamp.from(Instant.now()));
                    ps.setLong(4, id);
                }, "softDelete");
    }

    @Override
    public List<QuarantineRecord> findChangedSince(Instant since, int limit) {
        return query("SELECT " + COLUMNS + " FROM party_registration_quarantine "
                        + "WHERE updated_at >= ? ORDER BY updated_at ASC FETCH FIRST ? ROWS ONLY",
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(since));
                    ps.setInt(2, limit);
                });
    }

    @Override
    public List<QuarantineRecord> findByStatus(QuarantineStatus status, int limit) {
        return query("SELECT " + COLUMNS + " FROM party_registration_quarantine "
                        + "WHERE status = ? ORDER BY detected_at DESC FETCH FIRST ? ROWS ONLY",
                ps -> {
                    ps.setString(1, status.name());
                    ps.setInt(2, limit);
                });
    }

    // ------------------------------------------------------------------ plumbing

    private Optional<QuarantineRecord> findById(Connection c, Long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM party_registration_quarantine WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private List<QuarantineRecord> query(String sql, StatementBinder binder) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<QuarantineRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new QuarantineStoreException("query failed", e);
        }
    }

    private void execute(String sql, StatementBinder binder, String op) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new QuarantineStoreException(op + " failed", e);
        }
    }

    private QuarantineRecord map(ResultSet rs) throws SQLException {
        return new QuarantineRecord(
                rs.getLong("id"), rs.getString("key_space"), rs.getString("lookup_key"),
                rs.getString("anomaly_fingerprint"), parseTypes(rs.getString("anomaly_types")),
                Servability.valueOf(rs.getString("servability")),
                codec.deserialize(rs.getString("raw_payload")),
                codec.deserialize(rs.getString("corrected_payload")),
                QuarantineStatus.valueOf(rs.getString("status")),
                toInstant(rs.getTimestamp("detected_at")),
                toInstant(rs.getTimestamp("updated_at")),
                toInstant(rs.getTimestamp("notified_at")),
                rs.getString("corrected_by"), rs.getString("notes"));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String joinTypes(Set<AnomalyType> types) {
        StringBuilder sb = new StringBuilder();
        for (AnomalyType t : types) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(t.name());
        }
        return sb.toString();
    }

    private static Set<AnomalyType> parseTypes(String csv) {
        Set<AnomalyType> out = EnumSet.noneOf(AnomalyType.class);
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                String trimmed = part.strip();
                if (!trimmed.isEmpty()) {
                    try {
                        out.add(AnomalyType.valueOf(trimmed));
                    } catch (IllegalArgumentException ignored) {
                        // A type removed in a later release must not make old rows unreadable.
                    }
                }
            }
        }
        return out;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    public static final class QuarantineStoreException extends RuntimeException {
        public QuarantineStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
