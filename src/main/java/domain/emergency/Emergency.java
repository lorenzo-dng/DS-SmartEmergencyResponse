package domain.emergency;

import domain.common.Position;
import domain.vehicle.VehicleCategory;
import domain.zone.ZoneId;
import domain.vehicle.VehicleId;

import java.time.Instant;
import java.util.*;

public class Emergency {
    private final EmergencyId id;
    private final EmergencyType type;
    private final Position position;
    private final ZoneId zone;
    private final Instant localTimestamp;
    private Severity severity;
    private List<VehicleRequirement> requiredVehicles;
    private final List<VehicleId> assignedVehicles;
    private final Map<VehicleCategory, Integer> completedVehicles; // veicoli che hanno completato l'intervento sull'emergenza, per categoria
    private long agingTime; // aging accumulato fino all'ultimo cambio di stato
    private Long agingSince; // istante (in ms) in cui è iniziato l'accumulo dell'aging (può essere null)
    private EmergencyStatus status;

    public Emergency(EmergencyId id, EmergencyType type, Position position, ZoneId zone, Instant localTimestamp) {
        this.id = id;
        this.type = type;
        this.position = position;
        this.zone = zone;
        this.localTimestamp = localTimestamp;
        this.requiredVehicles = new ArrayList<>();
        this.assignedVehicles = new ArrayList<>();
        this.completedVehicles = new HashMap<>();
        this.agingTime = 0;
        this.agingSince = System.currentTimeMillis(); // appena creata l'emergenza è in attesa di veicoli, quindi l'aging parte subito
        this.status = EmergencyStatus.QUEUED;
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

    // gravità effettiva dell'emergenza: quella dichiarata dal triage, aumentata in base all'aging accumulato
    public Severity getEffectiveSeverity() {
        return severity.effectiveSeverity(getAgingTime());
    }

    public void setSeverity(Severity severity) {
        if (this.status == EmergencyStatus.CLOSED) {
            throw new IllegalStateException("Cannot modify a closed emergency: " + id);
        }
        this.severity = severity;
    }

    // restituisce una lista di sola lettura dei veicoli richiesti
    // questo perche ogni modifica richiede "updateStatus", e potrebbe essere dimenticato quando la lista viene direttamente utilizzata per scrittura
    public List<VehicleRequirement> getRequiredVehicles() {
        return Collections.unmodifiableList(requiredVehicles);
    }

    public void setRequiredVehicles(List<VehicleRequirement> requiredVehicles) {
        if (this.status == EmergencyStatus.CLOSED) {
            throw new IllegalStateException("Cannot modify a closed emergency: " + id);
        }
        this.requiredVehicles = new ArrayList<>(requiredVehicles);
    }

    // restituisce una lista di sola lettura dei veicoli assegnati
    public List<VehicleId> getAssignedVehicles() {
        return Collections.unmodifiableList(assignedVehicles);
    }

    // restituisce una mappa di sola lettura dei veicoli che hanno completato l'intervento, per categoria
    public Map<VehicleCategory, Integer> getCompletedVehicles() {
        return Collections.unmodifiableMap(completedVehicles);
    }

    // restituisce il numero di veicoli di una categoria che hanno già completato l'intervento sull'emergenza
    public int getCompletedCount(VehicleCategory category) {
        return completedVehicles.getOrDefault(category, 0);
    }

    // restituisce il tempo totale di aging accumulato (calcolato sulle fasi in cui non era coperta dai veicoli)
    public long getAgingTime() {
        if (agingSince == null) {
            return agingTime;
        }
        return agingTime + Math.max(0L, System.currentTimeMillis() - agingSince);
    }

    public EmergencyStatus getStatus() {
        return status;
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

    // rimuove il veicolo che ha completato l'intervento, lo conta tra i completati della sua categoria e chiude l'emergenza se tutti i veicoli richiesti hanno terminato l'intervento
    public void markVehicleCompleted(VehicleId vehicleId, VehicleCategory category) {
        if (this.status == EmergencyStatus.CLOSED) {
            throw new IllegalStateException("Cannot complete vehicle for a closed emergency: " + id);
        }
        if (assignedVehicles.remove(vehicleId)) {
            completedVehicles.merge(category, 1, Integer::sum); // aggiunge 1 veicolo completato di quella categoria
        }
        updateStatus();
    }

    // aggiorna lo stato dell'emergenza
    private void updateStatus() {
        int totalRequired = requiredVehicles.stream().mapToInt(VehicleRequirement::quantity).sum();
        int covered = assignedVehicles.size() + totalCompleted(); // slot coperti: veicoli ancora assegnati più quelli che hanno già completato l'intervento
        EmergencyStatus newStatus;
        if (isFullyServed()) { // se tutti i veicoli richiesti hanno completato l'intervento
            newStatus = EmergencyStatus.CLOSED;
        } else if (covered == totalRequired) { // se tutti gli slot sono stati coperti
            newStatus = EmergencyStatus.ASSIGNED;
        } else if (assignedVehicles.isEmpty()) { // se non sono stati assegnati veicoli all'emergenza
            newStatus = EmergencyStatus.QUEUED;
        } else {
            newStatus = EmergencyStatus.PARTIALLY_ASSIGNED;
        }
        updateAging(newStatus); // va chiamato prima di assegnare il nuovo stato: confronta il vecchio stato con il nuovo
        this.status = newStatus;
    }

    // conta i veicoli che hanno completato l'intervento, di tutte le categorie
    private int totalCompleted() {
        return completedVehicles.values().stream().mapToInt(Integer::intValue).sum();
    }

    // aggiorna l'aging
    private void updateAging(EmergencyStatus newStatus) {
        boolean wasWaiting = isWaiting(this.status);
        boolean nowWaiting = isWaiting(newStatus);
        long now = System.currentTimeMillis();
        if (wasWaiting && !nowWaiting) { // l'emergenza smette di aspettare: salva l'aging accumulato fin qui
            if (agingSince != null) {
                agingTime = agingTime + (now - agingSince);
            }
            agingSince = null;
        } else if (!wasWaiting && nowWaiting) { // veicolo dirottato: l'emergenza torna ad aspettare
            agingSince = now;
        }
    }

    private static boolean isWaiting(EmergencyStatus status) {
        return status == EmergencyStatus.QUEUED || status == EmergencyStatus.PARTIALLY_ASSIGNED;
    }

    // verifica se per ogni categoria richiesta i veicoli necessari hanno completato il proprio intervento
    private boolean isFullyServed() {
        return !requiredVehicles.isEmpty() && requiredVehicles.stream().allMatch(requirement -> getCompletedCount(requirement.category()) >= requirement.quantity());
    }

    // ricostruisce un'emergenza salvata nel db prima del crash, con lo stato e l'aging che aveva
    public static Emergency restore(EmergencyId id, EmergencyType type, Position position, ZoneId zone, Instant localTimestamp, Severity severity, List<VehicleRequirement> requiredVehicles, List<VehicleId> assignedVehicles, long agingAtSave, long savedAtMillis, EmergencyStatus status, Map<VehicleCategory, Integer> completedVehicles) {
        Emergency emergency = new Emergency(id, type, position, zone, localTimestamp);
        emergency.severity = severity;
        emergency.requiredVehicles = new ArrayList<>(requiredVehicles);
        emergency.assignedVehicles.addAll(assignedVehicles);
        emergency.completedVehicles.putAll(completedVehicles);
        emergency.status = status;
        long now = System.currentTimeMillis();
        if (isWaiting(status)) { // l'emergenza stava aspettando: il tempo trascorso dal salvataggio (anche durante il crash) conta come aging
            emergency.agingTime = agingAtSave + (now - savedAtMillis); // aggiorna l'aging: aging = aging gia accumulato preso dal db + tempo trascorso dal salvataggio dell'aging precedente
            emergency.agingSince = now;
        } else { // l'emergenza non stava aspettando: l'aging resta fermo
            emergency.agingTime = agingAtSave;
            emergency.agingSince = null;
        }
        return emergency;
    }
}