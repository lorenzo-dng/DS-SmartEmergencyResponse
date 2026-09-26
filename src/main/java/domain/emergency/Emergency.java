package domain.emergency;

import domain.common.Position;
import domain.zone.ZoneId;
import domain.vehicle.VehicleId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Emergency {
    private final EmergencyId id;
    private final EmergencyType type;
    private final Position position;
    private final ZoneId zone;
    private final Instant localTimestamp;
    private Severity severity;
    private List<VehicleRequirement> requiredVehicles;
    private final List<VehicleId> assignedVehicles;
    private long agingTimeMillis;
    private EmergencyStatus status;
    private int completedVehicleCount; // veicoli che hanno completato l'intervento sull'emergenza

    public Emergency(EmergencyId id, EmergencyType type, Position position, ZoneId zone, Instant localTimestamp) {
        this.id = id;
        this.type = type;
        this.position = position;
        this.zone = zone;
        this.localTimestamp = localTimestamp;
        this.requiredVehicles = new ArrayList<>();
        this.assignedVehicles = new ArrayList<>();
        this.agingTimeMillis = 0;
        this.status = EmergencyStatus.QUEUED;
        this.completedVehicleCount = 0;
    }

    public EmergencyId getId() {
        return id;
    }

    public EmergencyType getType() {
        return type;
    }

    public Position getPosition() {
        return position;
    }

    public ZoneId getZone() {
        return zone;
    }

    public Instant getLocalTimestamp() {
        return localTimestamp;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        if (this.status == EmergencyStatus.CLOSED) {
            throw new IllegalStateException("Cannot modify a closed emergency: " + id);
        }
        this.severity = severity;
    }

    // solo di lettura, perche ogni modifica richiede "updateStatus", e potrebbe essere dimenticato quando la lista viene direttamente utilizzata per scrittura
    public List<VehicleRequirement> getRequiredVehicles() {
        return Collections.unmodifiableList(requiredVehicles);
    }

    public void setRequiredVehicles(List<VehicleRequirement> requiredVehicles) {
        if (this.status == EmergencyStatus.CLOSED) {
            throw new IllegalStateException("Cannot modify a closed emergency: " + id);
        }
        this.requiredVehicles = new ArrayList<>(requiredVehicles);
    }

    // restituisce una vista di sola lettura dei veicoli assegnati
    // questo perche ogni modifica richiede "updateStatus", e potrebbe essere dimenticato se la lista verrebbe direttamente utilizzata per scrittura
    public List<VehicleId> getAssignedVehicles() {
        return Collections.unmodifiableList(assignedVehicles);
    }

    public void addAssignedVehicle(VehicleId vehicleId) {
        if (this.status == EmergencyStatus.CLOSED) {
            throw new IllegalStateException("Cannot assign vehicle to a closed emergency: " + id);
        }
        assignedVehicles.add(vehicleId);
        updateStatus();
    }

    public void removeAssignedVehicle(VehicleId vehicleId) {
        if (this.status == EmergencyStatus.CLOSED) {
            throw new IllegalStateException("Cannot modify a closed emergency: " + id);
        }
        assignedVehicles.remove(vehicleId);
        updateStatus();
    }

    // rimuove il veicolo che ha completato l'emergenza e chiude l'emergenza se tutti i veicoli richiesti hanno terminato l'intervento
    public void markVehicleCompleted(VehicleId vehicleId) {
        if (this.status == EmergencyStatus.CLOSED) {
            throw new IllegalStateException("Cannot complete vehicle for a closed emergency: " + id);
        }
        if (assignedVehicles.remove(vehicleId)) {
            completedVehicleCount++;
        }
        if (isFullyServed()) {
            this.status = EmergencyStatus.CLOSED;
        } else {
            updateStatus();
        }
    }

    public long getAgingTimeMillis() {
        return agingTimeMillis;
    }

    public void incrementAging(long deltaMillis) {
        this.agingTimeMillis += deltaMillis;
    }

    public EmergencyStatus getStatus() {
        return status;
    }

    public void close() {
        this.status = EmergencyStatus.CLOSED;
    }

    // aggiorna lo stato dell'emergenza
    private void updateStatus() {
        int totalRequired = requiredVehicles.stream().mapToInt(VehicleRequirement::quantity).sum(); // recupera il numero totale di veicoli richiesti per l'emergenza

        if (assignedVehicles.isEmpty()) { // se non sono stati assegnati veicoli all'emergenza
            this.status = EmergencyStatus.QUEUED;
        } else if (assignedVehicles.size() < totalRequired) { // se sono stati assegnati solo alcuni veicoli all'emergenza
            this.status = EmergencyStatus.PARTIALLY_ASSIGNED;
        } else {
            this.status = EmergencyStatus.ASSIGNED;
        }
    }

    // verifica se tutti i veicoli richiesti hanno completato il proprio intervento
    private boolean isFullyServed() {
        int totalRequired = requiredVehicles.stream().mapToInt(VehicleRequirement::quantity).sum(); // recupera il numero totale di veicoli richiesti per l'emergenza
        return completedVehicleCount >= totalRequired;
    }
}