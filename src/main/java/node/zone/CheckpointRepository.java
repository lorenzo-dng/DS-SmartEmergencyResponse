package node.zone;

import domain.common.Position;
import domain.emergency.EmergencyId;
import domain.vehicle.VehicleId;
import domain.vehicle.VehicleStatus;
import domain.zone.ZoneId;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

// salva l'ultimo stato noto di ogni veicolo
public class CheckpointRepository {

    private final String jdbcUrl;

    public CheckpointRepository(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        createTableIfNotExists();
    }

    // crea la tabella nel database SQLite, se non esiste
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
                "scene_started_at INTEGER," +
                "scene_deadline INTEGER)";
        try (Connection conn = DriverManager.getConnection(jdbcUrl); // apre la connessione al database
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql); // crea la tabella
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize checkpoint persistence: " + e.getMessage(), e);
        }
    }

    // inserisce o aggiorna le informazioni di un veicolo non AVAILABLE
    public void save(VehicleId vehicleId, VehicleStatus status, Position position, Position destination, double batteryLevel, ActiveLoan loan, Long sceneStartedAtMillis, Long sceneDeadlineMillis) {
        // query sql
        String sql = "INSERT OR REPLACE INTO vehicle_checkpoints" +
                "(vehicle_id, status, lat, lon, dest_lat, dest_lon, battery_level, checkpoint_at, emergency_id, requester_zone_id, scene_started_at, scene_deadline)" + // colonne
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"; // valori ancora da specificare (segnaposti)
        try (Connection conn = DriverManager.getConnection(jdbcUrl); // apre la connessione al database
             PreparedStatement ps = conn.prepareStatement(sql)) {
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
            if (sceneStartedAtMillis != null) {
                ps.setLong(11, sceneStartedAtMillis);
            } else {
                ps.setNull(11, Types.BIGINT);
            }
            if (sceneDeadlineMillis != null) {
                ps.setLong(12, sceneDeadlineMillis);
            } else {
                ps.setNull(12, Types.BIGINT);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist vehicle checkpoint: " + e.getMessage(), e);
        }
    }

    // rimuove il checkpoint quando il veicolo torna AVAILABLE
    public void delete(VehicleId vehicleId) {
        String sql = "DELETE FROM vehicle_checkpoints WHERE vehicle_id = ?"; // query sql
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vehicleId.value());  // riempie il primo segnaposto
            ps.executeUpdate(); // rimuove il veicolo prestato
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete vehicle checkpoint: " + e.getMessage(), e);
        }
    }

    // ricarica tutti i checkpoint al riavvio del nodo dopo il crash
    public List<VehicleCheckpoint> loadAll() {
        List<VehicleCheckpoint> checkpoints = new ArrayList<>();
        String sql = "SELECT vehicle_id, status, lat, lon, dest_lat, dest_lon, battery_level, checkpoint_at, emergency_id, requester_zone_id, scene_started_at, scene_deadline FROM vehicle_checkpoints"; // query sql
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) { // esegue la query e restituisce i risultati delle colonne (righe)
            while (rs.next()) { // per ogni riga (risultato) restituita
                Position destination = null;
                double destLat = rs.getDouble("dest_lat");
                if (!rs.wasNull()) { // se destLat non è null (ma può essere latitudine 0 - ResultSet imposta 0 a una valore null)
                    destination = new Position(destLat, rs.getDouble("dest_lon"));
                }
                String emergencyId = rs.getString("emergency_id");
                Long sceneStartedAt = rs.getLong("scene_started_at");
                if (rs.wasNull()) { // se sceneStartedAt è null (e non 0), allora imposta sceneStartedAt a null (ResultSet imposta 0 a un valore null)
                    sceneStartedAt = null;
                }
                Long sceneDeadline = rs.getLong("scene_deadline");
                if (rs.wasNull()) {
                    sceneDeadline = null;
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
                        sceneStartedAt,
                        sceneDeadline));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load vehicle checkpoints: " + e.getMessage(), e);
        }
        return checkpoints; // restituisce la lista di record
    }
}