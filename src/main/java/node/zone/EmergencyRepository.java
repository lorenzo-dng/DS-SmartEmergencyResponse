package node.zone;

import domain.common.Position;
import domain.emergency.Emergency;
import domain.emergency.EmergencyId;
import domain.emergency.EmergencyStatus;
import domain.emergency.EmergencyType;
import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.zone.ZoneId;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// salva lo stato di ogni emergenza aperta
public class EmergencyRepository {

    private final SqliteDatabase database;

    public EmergencyRepository(SqliteDatabase database) {
        this.database = database;
        createTableIfNotExists();
    }

    // crea la tabella nel database SQLite, se non esiste
    private void createTableIfNotExists() {
        // query sql
        String sql = "CREATE TABLE IF NOT EXISTS emergencies" +
                "(id TEXT PRIMARY KEY," + // colonna
                "type TEXT NOT NULL," +
                "lat REAL NOT NULL," +
                "lon REAL NOT NULL," +
                "zone_id TEXT NOT NULL," +
                "local_timestamp TEXT NOT NULL," +
                "severity TEXT," +
                "status TEXT NOT NULL," +
                "completed_vehicle_count INTEGER NOT NULL," +
                "aging_millis INTEGER NOT NULL," + // aging totale al momento del salvataggio
                "saved_at INTEGER NOT NULL," + // istante del salvataggio dell'aging (serve a recuperare il tempo trascorso durante un crash)
                "required_vehicles TEXT NOT NULL," +
                "assigned_vehicles TEXT NOT NULL," +
                "borrowed_vehicles TEXT NOT NULL," +
                "arrivals TEXT NOT NULL)";
        try (Connection conn = database.open(); // apre la connessione al database
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql); // crea la tabella
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize emergency persistence: " + e.getMessage(), e);
        }
    }

    // inserisce o aggiorna un'emergenza
    public void save(Connection conn, Emergency emergency, Map<VehicleId, BorrowedVehicleInfo> borrowedVehicles, Map<VehicleCategory, List<VehicleId>> arrivals) throws SQLException {
        // query sql
        String sql = "INSERT OR REPLACE INTO emergencies" +
                "(id, type, lat, lon, zone_id, local_timestamp, severity, status, completed_vehicle_count, aging_millis, saved_at, required_vehicles, assigned_vehicles, borrowed_vehicles, arrivals)" + // colonne
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"; // valori ancora da specificare (segnaposti)
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, emergency.getId().value()); // riempie il primo segnaposto
            ps.setString(2, emergency.getType().name());
            ps.setDouble(3, emergency.getPosition().latitude());
            ps.setDouble(4, emergency.getPosition().longitude());
            ps.setString(5, emergency.getZone().value());
            ps.setString(6, emergency.getLocalTimestamp().toString());
            if (emergency.getSeverity() != null) {
                ps.setString(7, emergency.getSeverity().name());
            } else {
                ps.setNull(7, Types.VARCHAR);
            }
            ps.setString(8, emergency.getStatus().name());
            ps.setInt(9, emergency.getCompletedVehicleCount());
            ps.setLong(10, emergency.getAgingTime()); // aging totale adesso
            ps.setLong(11, System.currentTimeMillis()); // istante del salvataggio
            ps.setString(12, requirementsToJson(emergency.getRequiredVehicles()));
            ps.setString(13, vehicleIdsToJson(emergency.getAssignedVehicles()));
            ps.setString(14, borrowedToJson(borrowedVehicles));
            ps.setString(15, arrivalsToJson(arrivals));
            ps.executeUpdate();
        }
    }

    // rimuove l'emergenza quando si chiude
    public void delete(Connection conn, EmergencyId emergencyId) throws SQLException {
        String sql = "DELETE FROM emergencies WHERE id = ?"; // query sql
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, emergencyId.value()); // riempie il primo segnaposto
            ps.executeUpdate(); // rimuove l'emergenza
        }
    }

    // ricarica tutte le emergenze aperte al riavvio del nodo dopo il crash
    public List<EmergencySnapshot> loadAll() {
        List<EmergencySnapshot> snapshots = new ArrayList<>();
        String sql = "SELECT id, type, lat, lon, zone_id, local_timestamp, severity, status, completed_vehicle_count, aging_millis, saved_at, required_vehicles, assigned_vehicles, borrowed_vehicles, arrivals FROM emergencies"; // query sql
        try (Connection conn = database.open();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) { // esegue la query e restituisce i risultati delle colonne (righe)
            while (rs.next()) { // per ogni riga (risultato) restituita
                String severity = rs.getString("severity"); // null se la colonna è vuota
                Emergency emergency = Emergency.restore(
                        new EmergencyId(rs.getString("id")),
                        EmergencyType.valueOf(rs.getString("type")),
                        new Position(rs.getDouble("lat"), rs.getDouble("lon")),
                        new ZoneId(rs.getString("zone_id")),
                        Instant.parse(rs.getString("local_timestamp")),
                        severity != null ? Severity.valueOf(severity) : null,
                        requirementsFromJson(rs.getString("required_vehicles")),
                        vehicleIdsFromJson(new JsonArray(rs.getString("assigned_vehicles"))),
                        rs.getLong("aging_millis"),
                        rs.getLong("saved_at"),
                        EmergencyStatus.valueOf(rs.getString("status")),
                        rs.getInt("completed_vehicle_count"));
                snapshots.add(new EmergencySnapshot(emergency, borrowedFromJson(rs.getString("borrowed_vehicles")), arrivalsFromJson(rs.getString("arrivals"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load emergencies: " + e.getMessage(), e);
        }
        return snapshots; // restituisce la lista di record
    }

    // conversione da oggetti a JSON (scrittura) - in SQL i valori dentro le colonne non possono essere liste o mappe, vanno convertiti in un valore semplice

    private static String requirementsToJson(List<VehicleRequirement> requirements) {
        JsonArray array = new JsonArray();
        for (VehicleRequirement requirement : requirements) {
            array.add(new JsonObject().put("category", requirement.category().name()).put("quantity", requirement.quantity()));
        }
        return array.encode();
    }

    private static String vehicleIdsToJson(List<VehicleId> vehicleIds) {
        return vehicleIdsToArray(vehicleIds).encode();
    }

    private static JsonArray vehicleIdsToArray(List<VehicleId> vehicleIds) {
        JsonArray array = new JsonArray();
        for (VehicleId vehicleId : vehicleIds) {
            array.add(vehicleId.value());
        }
        return array;
    }

    private static String arrivalsToJson(Map<VehicleCategory, List<VehicleId>> arrivals) {
        JsonArray array = new JsonArray();
        for (Map.Entry<VehicleCategory, List<VehicleId>> entry : arrivals.entrySet()) {
            array.add(new JsonObject().put("category", entry.getKey().name()).put("vehicles", vehicleIdsToArray(entry.getValue())));
        }
        return array.encode();
    }

    private static String borrowedToJson(Map<VehicleId, BorrowedVehicleInfo> borrowedVehicles) {
        JsonArray array = new JsonArray();
        for (Map.Entry<VehicleId, BorrowedVehicleInfo> entry : borrowedVehicles.entrySet()) {
            array.add(new JsonObject()
                    .put("vehicleId", entry.getKey().value())
                    .put("category", entry.getValue().category().name())
                    .put("contractorZoneId", entry.getValue().contractorZoneId().value()));
        }
        return array.encode();
    }

    // conversione da JSON a oggetti (lettura)

    private static List<VehicleRequirement> requirementsFromJson(String json) {
        JsonArray array = new JsonArray(json);
        List<VehicleRequirement> requirements = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject object = array.getJsonObject(i);
            requirements.add(new VehicleRequirement(VehicleCategory.valueOf(object.getString("category")), object.getInteger("quantity")));
        }
        return requirements;
    }

    private static List<VehicleId> vehicleIdsFromJson(JsonArray array) {
        List<VehicleId> vehicleIds = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            vehicleIds.add(new VehicleId(array.getString(i)));
        }
        return vehicleIds;
    }

    private static Map<VehicleId, BorrowedVehicleInfo> borrowedFromJson(String json) {
        JsonArray array = new JsonArray(json);
        Map<VehicleId, BorrowedVehicleInfo> borrowedVehicles = new HashMap<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject object = array.getJsonObject(i);
            borrowedVehicles.put(new VehicleId(object.getString("vehicleId")),
                    new BorrowedVehicleInfo(VehicleCategory.valueOf(object.getString("category")), new ZoneId(object.getString("contractorZoneId"))));
        }
        return borrowedVehicles;
    }

    private static Map<VehicleCategory, List<VehicleId>> arrivalsFromJson(String json) {
        JsonArray array = new JsonArray(json);
        Map<VehicleCategory, List<VehicleId>> arrivals = new HashMap<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject object = array.getJsonObject(i);
            arrivals.put(VehicleCategory.valueOf(object.getString("category")), vehicleIdsFromJson(object.getJsonArray("vehicles")));
        }
        return arrivals;
    }
}