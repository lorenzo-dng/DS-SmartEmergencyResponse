package node.zone;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.zone.ZoneId;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

// usa SQLite per salvare i prestiti lasciati in sospeso dopo l'intervento del veicolo sull'emergenza, necessario a sopravvivere a un crash del contractor a metà prestito
public class LoanRepository {

    private final SqliteDatabase database;

    public LoanRepository(SqliteDatabase database) {
        this.database = database;
        createTableIfNotExists();
    }

    // crea la tabella nel database SQLite, se non esiste
    private void createTableIfNotExists() {
        // query sql
        String sql = "CREATE TABLE IF NOT EXISTS pending_loans" +
                "(vehicle_id TEXT NOT NULL," + // colonna
                "emergency_id TEXT NOT NULL," +
                "requester_zone_id TEXT NOT NULL," +
                "category TEXT NOT NULL," +
                "PRIMARY KEY (vehicle_id, emergency_id))";
        try (Connection conn = database.open(); // apre la connessione al database
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql); // crea la tabella
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize loan persistence: " + e.getMessage(), e);
        }
    }

    // inserisce o aggiorna un prestito (chiamato alla conferma EN_ROUTE del veicolo)
    public void save(VehicleId vehicleId, EmergencyId emergencyId, ZoneId requesterZoneId, VehicleCategory category) {
        // query sql
        String sql = "INSERT OR REPLACE INTO pending_loans" +
                "(vehicle_id, emergency_id, requester_zone_id, category)" + // colonne
                "VALUES (?, ?, ?, ?)"; // valori ancora da specificare (segnaposti)
        try (Connection conn = database.open(); // apre la connessione al database
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vehicleId.value()); // riempie il primo segnaposto
            ps.setString(2, emergencyId.value());
            ps.setString(3, requesterZoneId.value());
            ps.setString(4, category.name());
            ps.executeUpdate(); // inserisce o aggiorna un veicolo prestato
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist active loan: " + e.getMessage(), e);
        }
    }

    // rimuove un prestito concluso (chiamato quando arriva l'ack di InterventionCompleted)
    public void delete(VehicleId vehicleId, EmergencyId emergencyId) {
        String sql = "DELETE FROM pending_loans WHERE vehicle_id = ? AND emergency_id = ?"; // query sql
        try (Connection conn = database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vehicleId.value()); // riempie il primo segnaposto
            ps.setString(2, emergencyId.value());
            ps.executeUpdate(); // rimuove il veicolo prestato
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete active loan: " + e.getMessage(), e);
        }
    }

    // ricarica tutti i prestiti attivi al riavvio del nodo dopo il crash
    public List<PendingLoan> loadAll() {
        List<PendingLoan> loans = new ArrayList<>();
        String sql = "SELECT vehicle_id, emergency_id, requester_zone_id, category FROM pending_loans"; // query sql
        try (Connection conn = database.open();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) { // esegue la query e restituisce i risultati delle colonne (righe)
            while (rs.next()) { // per ogni riga (risultato) restituita, aggiunge un record
                loans.add(new PendingLoan(new VehicleId(rs.getString("vehicle_id")), new EmergencyId(rs.getString("emergency_id")), new ZoneId(rs.getString("requester_zone_id")), VehicleCategory.valueOf(rs.getString("category"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load active loans: " + e.getMessage(), e);
        }
        return loans; // restituisce la lista di record
    }

}