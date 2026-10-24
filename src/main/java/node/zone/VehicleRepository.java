package node.zone;

import domain.common.Position;
import domain.emergency.EmergencyId;
import domain.vehicle.VehicleId;
import domain.vehicle.VehicleStatus;
import domain.zone.ZoneId;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

// salva lo stato di un veicolo
public class VehicleRepository {

    private final SqliteDatabase database;

    public VehicleRepository(SqliteDatabase database) {
        this.database = database;
        createTableIfNotExists();
    }

    // crea la tabella
    private void createTableIfNotExists() {
        // query sql
        String sql = "CREATE TABLE IF NOT EXISTS vehicle_checkpoints" +
                "(vehicle_id TEXT PRIMARY KEY," + // colonna
                "status TEXT NOT NULL," +
                "lat REAL NOT NULL, " +
                "lon REAL NOT NULL," +
                "dest_lat REAL, " +
                "dest_lon REAL," +
                "battery_level REAL NOT NULL," +
                "checkpoint_at INTEGER NOT NULL," +
                "emergency_id TEXT, " +
                "requester_zone_id TEXT," +
                "on_scene_started_at INTEGER," +
                "on_scene_deadline INTEGER)";
        try (Connection conn = database.open(); // apre la connessione al database
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql); // crea la tabella
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize checkpoint persistence: " + e.getMessage(), e);
        }
    }

    // inserisce o aggiorna lo stato di un veicolo non AVAILABLE
    public void save(VehicleId vehicleId, VehicleStatus status, Position position, Position destination, double batteryLevel, ActiveLoan loan, Long onSceneStartedAtMillis, Long onSceneDeadlineMillis) {
        try (Connection conn = database.open()) {
            save(conn, vehicleId, status, position, destination, batteryLevel, loan, onSceneStartedAtMillis, onSceneDeadlineMillis);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist vehicle checkpoint: " + e.getMessage(), e);
        }
    }

    public void save(Connection conn, VehicleId vehicleId, VehicleStatus status, Position position, Position destination, double batteryLevel, ActiveLoan loan, Long onSceneStartedAtMillis, Long onSceneDeadlineMillis) throws SQLException {
        // query sql
        String sql = "INSERT OR REPLACE INTO vehicle_checkpoints" +
                "(vehicle_id, status, lat, lon, dest_lat, dest_lon, battery_level, checkpoint_at, emergency_id, requester_zone_id, on_scene_started_at, on_scene_deadline)" + // colonne
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"; // valori ancora da specificare (segnaposti)
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vehicleId.value()); // riempie il primo segnaposto
            ps.setString(2, status.name());
            ps.setDouble(3, position.latitude());
            ps.setDouble(4, position.longitude());
            if (destination != null) {
                ps.setDouble(5, destination.latitude());
                ps.setDouble(6, destination.longitude());
            } else {
                ps.setNull(5, Types.DOUBLE);
                ps.setNull(6, Types.DOUBLE);
            }
            ps.setDouble(7, batteryLevel);
            ps.setLong(8, System.currentTimeMillis());
            if (loan != null) {
                ps.setString(9, loan.emergencyId().value());
                ps.setString(10, loan.requesterZoneId().value());
            } else {
                ps.setNull(9, Types.VARCHAR);
                ps.setNull(10, Types.VARCHAR);
            }
            if (onSceneStartedAtMillis != null) {
                ps.setLong(11, onSceneStartedAtMillis);
            } else {
                ps.setNull(11, Types.BIGINT);
            }
            if (onSceneDeadlineMillis != null) {
                ps.setLong(12, onSceneDeadlineMillis);
            } else {
                ps.setNull(12, Types.BIGINT);
            }
            ps.executeUpdate();
        }
    }

    // rimuove il checkpoint quando il veicolo torna AVAILABLE
    public void delete(VehicleId vehicleId) {
        try (Connection conn = database.open()) {
            delete(conn, vehicleId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete vehicle checkpoint: " + e.getMessage(), e);
        }
    }

    public void delete(Connection conn, VehicleId vehicleId) throws SQLException {
        String sql = "DELETE FROM vehicle_checkpoints WHERE vehicle_id = ?"; // query sql
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vehicleId.value());  // riempie il primo segnaposto
            ps.executeUpdate(); // rimuove il checkpoint del veicolo
        }
    }

    // ricarica tutti i checkpoint al riavvio del nodo dopo il crash
    public List<VehicleCheckpoint> loadAll() {
        List<VehicleCheckpoint> checkpoints = new ArrayList<>();
        String sql = "SELECT vehicle_id, status, lat, lon, dest_lat, dest_lon, battery_level, checkpoint_at, emergency_id, requester_zone_id, on_scene_started_at, on_sscene_deadline FROM vehicle_checkpoints"; // query sql
        try (Connection conn = database.open();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) { // esegue la query e restituisce i risultati delle colonne (righe)
            while (rs.next()) { // per ogni riga (risultato) restituita
                Position destination = null;
                double destLat = rs.getDouble("dest_lat");
                if (!rs.wasNull()) { // se destLat non è null (ma può essere latitudine 0 - ResultSet imposta 0 a una valore null)
                    destination = new Position(destLat, rs.getDouble("dest_lon"));
                }
                String emergencyId = rs.getString("emergency_id");
                Long onSceneStartedAt = rs.getLong("on_scene_started_at");
                if (rs.wasNull()) { // se onSceneStartedAt è null (e non 0), allora imposta onSceneStartedAt a null (ResultSet imposta 0 a un valore null)
                    onSceneStartedAt = null;
                }
                Long onSceneDeadline = rs.getLong("on_scene_deadline");
                if (rs.wasNull()) {
                    onSceneDeadline = null;
                }
                checkpoints.add(new VehicleCheckpoint(
                        new VehicleId(rs.getString("vehicle_id")),
                        VehicleStatus.valueOf(rs.getString("status")),
                        new Position(rs.getDouble("lat"), rs.getDouble("lon")),
                        destination,
                        rs.getDouble("battery_level"),
                        rs.getLong("checkpoint_at"),
                        emergencyId != null ? new EmergencyId(emergencyId) : null,
                        emergencyId != null ? new ZoneId(rs.getString("requester_zone_id")) : null,
                        onSceneStartedAt,
                        onSceneDeadline));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load vehicle checkpoints: " + e.getMessage(), e);
        }
        return checkpoints; // restituisce la lista di record
    }
}