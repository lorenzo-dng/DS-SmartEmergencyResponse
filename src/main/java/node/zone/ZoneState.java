package node.zone;

import domain.emergency.*;
import domain.zone.Zone;
import domain.zone.ZoneId;
import domain.vehicle.Vehicle;
import domain.vehicle.VehicleId;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleStatus;
import domain.common.VectorClock;
import domain.utils.GeoUtils;
import domain.utils.BipartiteMatching;
import java.util.*;

// stato della zona
public class ZoneState {

    private static final double MILLIS_PER_MINUTE = 60_000.0;
    private static final double AGING_SCALE_FACTOR = 1.0 / 3.0; // k, calibrato empiricamente (vedi documento)
    private static final double BATTERY_EPSILON = 1e-9;
    private static final double ID_EPSILON = 1e-15;
    private static final double REASSIGNMENT_MARGIN_MINUTES = 3.0;
    private static final double LOW_TO_MEDIUM_THRESHOLD_MIN = 15.0;
    private static final double LOW_TO_HIGH_THRESHOLD_MIN = 20.0;
    private static final double MEDIUM_TO_HIGH_THRESHOLD_MIN = 5.0;
    private final Zone zone;
    private final Map<VehicleId, Vehicle> vehicles;
    private final Map<EmergencyId, Emergency> emergencies;
    private final Map<PendingReservationKey, List<VehicleId>> pendingReservations; // tiene traccia di quali veicoli il contractor ha riservato per quale emergenza cross-zona (ma non ancora assegnati)
    private VectorClock localClock;

    public ZoneState(Zone zone) {
        this.zone = zone;
        this.vehicles = new HashMap<>();
        this.emergencies = new HashMap<>();
        this.pendingReservations = new HashMap<>();
        this.localClock = new VectorClock();
    }

    public ZoneId getZoneId() {
        return zone.getId();
    }

    public Zone getZone() {
        return zone;
    }

    // veicoli

    public void addVehicle(Vehicle vehicle) {
        vehicles.put(vehicle.getId(), vehicle);
    }

    public Vehicle getVehicle(VehicleId id) {
        Vehicle vehicle = vehicles.get(id);
        if (vehicle == null) {
            throw new IllegalArgumentException("Vehicle not found in this zone: " + id);
        }
        return vehicle;
    }

    // vista di sola lettura, per evitare che i veicoli possano essere rimossi o aggiunti dalla zona dall'esterno
    public Collection<Vehicle> getVehicles() {
        return Collections.unmodifiableCollection(vehicles.values());
    }

    // recupera i veicoli liberi di una specifica categoria
    public List<Vehicle> getAvailableVehicles(VehicleCategory category) {
        List<Vehicle> available = new ArrayList<>();
        for (Vehicle vehicle : vehicles.values()) {
            if (vehicle.getCategory() == category && vehicle.getStatus() == VehicleStatus.AVAILABLE) {
                available.add(vehicle);
            }
        }
        return available;
    }

    // emergenze

    public void addEmergency(Emergency emergency) {
        emergencies.put(emergency.getId(), emergency);
    }

    public Emergency getEmergency(EmergencyId id) {
        Emergency emergency = emergencies.get(id);
        if (emergency == null) {
            throw new IllegalArgumentException("Emergency not found in this zone: " + id);
        }
        return emergency;
    }

    // recupera le emergenze ancora aperte
    public Collection<Emergency> getOpenEmergencies() {
        List<Emergency> open = new ArrayList<>();
        for (Emergency emergency : emergencies.values()) {
            if (emergency.getStatus() != EmergencyStatus.CLOSED) {
                open.add(emergency);
            }
        }
        return Collections.unmodifiableList(open);
    }

    // assegnazione locale

    // Fase 1: minimizzazione pesata per gravità sui veicoli liberi e sugli eventi ancora scoperti

    // assegna i veicoli liberi alle emergenze ancora scoperte della zona
    public void assignFreeVehicles(VehicleCategory category) {
        List<Vehicle> availableVehicles = getAvailableVehicles(category); // recupera la lista dei veicoli disponibili della categoria indicata

        // ogni "slot" rappresenta una singola unità ancora mancante: un'emergenza con quantity=2
        // per questa categoria, di cui una sola già assegnata, compare qui una sola volta (manca 1)
        List<Emergency> slots = new ArrayList<>(); // lista di "slot" dell'emergenza, ovvero "posti" aperti per assegnare un veicolo
        for (Emergency emergency : getOpenEmergencies()) { // per ogni emergenza aperta
            for (VehicleRequirement requirement : emergency.getRequiredVehicles()) { // recupera i veicoli richiesti
                if (requirement.category() == category) { // se la categoria coincide
                    int alreadyAssigned = countAssignedVehiclesOfCategory(emergency, category); // calcola il numero di veicoli della categoria indicata che sono già stati assegnati all'emergenza
                    int missing = requirement.quantity() - alreadyAssigned; // calcola il numero di veicoli della categoria indicata che devono essere ancora assegnati
                    for (int i = 0; i < missing; i++) { // per ogni veicolo ancora da assegnare
                        slots.add(emergency); // aggiunge un nuovo slot
                    }
                }
            }
        }

        // se non ci sono veicoli disponibili oppure se non ci sono veicoli ancora da assegnare
        if (availableVehicles.isEmpty() || slots.isEmpty()) {
            return;
        }

        double[][] costMatrix = buildCostMatrix(availableVehicles, slots, category); // costruisce una matrice dei costi associando ogni veicolo disponibile a ogni slot dell'emergenza
        int[] assignment = BipartiteMatching.solve(costMatrix); // applica l'algoritmo ungherese di minimizzazione pesata per gravita - assignment[i] indica a quale slot è stato assegnato il veicolo i (-1 se non è stato assegnato)

        for (int i = 0; i < assignment.length; i++) { // scorre tutto l'array
            if (assignment[i] != -1) { // se il veicolo i è stato assegnato a uno slot
                Vehicle vehicle = availableVehicles.get(i); // recupera il veicolo corrispondente alla riga i dalla matrice dei costi
                Emergency emergency = slots.get(assignment[i]); // recupera lo slot (sarebbe il riferimento all'emergenza stessa) corrispondente al veicolo assegnato
                vehicle.setStatus(VehicleStatus.EN_ROUTE); // aggiorna lo stato del veicolo assegnato
                emergency.addAssignedVehicle(vehicle.getId()); // aggiunge il veicolo alla lista dei veicoli assegnati
            }
        }
    }

    // conta quanti veicoli di una categoria sono stati assegnati a un'emergenza
    private int countAssignedVehiclesOfCategory(Emergency emergency, VehicleCategory category) {
        int count = 0;
        for (VehicleId id : emergency.getAssignedVehicles()) { // scorre la lista dei veicoli assegnati
            if (getVehicle(id).getCategory() == category) { // se la categoria corrisponde
                count++; // incrementa il contatore
            }
        }
        return count; // restituisce il contatore
    }

    // costruisce la matrice dei costi che contiene il costo di ogni possibile assegnazione tra un veicolo disponibile e uno slot
    private double[][] buildCostMatrix(List<Vehicle> vehicles, List<Emergency> slots, VehicleCategory category) {
        double[][] matrix = new double[vehicles.size()][slots.size()]; // crea una matrice con una riga per ogni veicolo e una colonna per ogni slot
        for (int i = 0; i < vehicles.size(); i++) { // per ogni riga della matrice
            for (int j = 0; j < slots.size(); j++) { // per ogni colonna
                matrix[i][j] = computeCost(vehicles.get(i), slots.get(j), category); // calcola quanto costa assegnare il veicolo i allo slot j e salva questo costo nella matrice
            }
        }
        return matrix; // restituisce la matrice calcolata
    }

    // calcola il costo di assegnare un veicolo a un'emergenza
    private double computeCost(Vehicle vehicle, Emergency emergency, VehicleCategory category) {
        double distanceKm = GeoUtils.haversine(vehicle.getPosition(), emergency.getPosition()); // calcola la distanza tra la posizione del veicolo e la posizione dell'emergenza
        double arrivalMinutes = GeoUtils.estimatedArrivalMinutes(distanceKm, category); // calcola il tempo stimato di arrivo in minuti
        double agingMinutes = emergency.getAgingTimeMillis() / MILLIS_PER_MINUTE; // recupera i ms di aging accumulati dall'emergenza e li converte in min
        double baseCost = arrivalMinutes / emergency.getSeverity().getWeight() - AGING_SCALE_FACTOR * agingMinutes; // calcola il costo dell'assegnazione

        // tie-breaker
        double batteryTerm = vehicle.getBatteryLevel() * BATTERY_EPSILON; // recupera il valore di batteria trasformandolo in una quantità piccolissima (necessario per evitare un numero troppo grande, che avrebbe molta influenza nel calcolo finale)
        double idTerm = vehicle.getId().value().hashCode() * ID_EPSILON; // recupera il valore (numerico) dell'id trasformandolo in una quantità piccolissima (necessario per evitare un numero troppo grande, che avrebbe molta influenza nel calcolo finale)
        return baseCost - batteryTerm - idTerm; // calcola il costo finale
    }

    // Fase 2: se il nuovo evento resta scoperto per questa categoria dopo Fase 1, tenta di dirottare veicoli EN_ROUTE diretti verso eventi di gravità inferiore
    public void reassignInTransitVehicles(Emergency newEmergency, VehicleCategory category) {
        int missing = requiredQuantityFor(newEmergency, category) - countAssignedVehiclesOfCategory(newEmergency, category); // calcola quanti veicoli della categoria sono ancora necessari per il nuovo evento
        for (int i = 0; i < missing; i++) { // per ogni veicolo necessario
            Optional<Vehicle> candidate = findBestReassignmentCandidate(newEmergency, category); // cerca il miglior veicolo EN_ROUTE riassegnabile
            if (candidate.isEmpty()) { // se non trova candidati, interrompe la riassegnazione
                break;
            }
            Vehicle vehicle = candidate.get(); // recupera il veicolo trovato
            Emergency previousEmergency = findAssignedEmergency(vehicle.getId()); // trova l'emergenza a cui il veicolo era precedentemente assegnato
            previousEmergency.removeAssignedVehicle(vehicle.getId()); // rimuove il veicolo dalla precedente emergenza (che torna nel pool)
            vehicle.setDestination(newEmergency.getPosition()); // imposta la posizione del nuovo evento come nuova destinazione
            newEmergency.addAssignedVehicle(vehicle.getId()); // assegna il veicolo al nuovo evento
        }
    }

    private int requiredQuantityFor(Emergency emergency, VehicleCategory category) {
        for (VehicleRequirement requirement : emergency.getRequiredVehicles()) {
            if (requirement.category() == category) {
                return requirement.quantity();
            }
        }
        return 0;
    }

    // sceglie, tra tutti i veicoli EN_ROUTE, quello con il costo più basso verso il nuovo evento
    private Optional<Vehicle> findBestReassignmentCandidate(Emergency newEmergency, VehicleCategory category) {
        Vehicle best = null;
        double bestCost = Double.MAX_VALUE;

        for (Vehicle vehicle : vehicles.values()) {
            if (vehicle.getCategory() != category || vehicle.getStatus() != VehicleStatus.EN_ROUTE) {
                continue;
            }

            double remainingKm = GeoUtils.haversine(vehicle.getPosition(), vehicle.getDestination());
            double remainingMinutes = GeoUtils.estimatedArrivalMinutes(remainingKm, category);
            if (remainingMinutes <= REASSIGNMENT_MARGIN_MINUTES) {
                continue; // troppo vicino alla propria destinazione, non eleggibile
            }

            Emergency currentEmergency = findAssignedEmergency(vehicle.getId());
            if (currentEmergency == null || effectiveSeverity(currentEmergency).getWeight() >= effectiveSeverity(newEmergency).getWeight()) {
                continue; // gravità effettiva non strettamente inferiore, non eleggibile
            }

            double cost = computeCost(vehicle, newEmergency, category);
            if (cost < bestCost) {
                bestCost = cost;
                best = vehicle;
            }
        }
        return Optional.ofNullable(best);
    }

    private Emergency findAssignedEmergency(VehicleId vehicleId) {
        for (Emergency emergency : emergencies.values()) {
            if (emergency.getAssignedVehicles().contains(vehicleId)) {
                return emergency;
            }
        }
        return null;
    }

    // gravità effettiva a gradini: severity grezza + aging, usando le stesse soglie calibrate per k
    private Severity effectiveSeverity(Emergency emergency) {
        Severity base = emergency.getSeverity();
        double agingMinutes = emergency.getAgingTimeMillis() / MILLIS_PER_MINUTE;

        if (base == Severity.LOW) {
            if (agingMinutes >= LOW_TO_HIGH_THRESHOLD_MIN) return Severity.HIGH;
            if (agingMinutes >= LOW_TO_MEDIUM_THRESHOLD_MIN) return Severity.MEDIUM;
            return Severity.LOW;
        }
        if (base == Severity.MEDIUM) {
            return agingMinutes >= MEDIUM_TO_HIGH_THRESHOLD_MIN ? Severity.HIGH : Severity.MEDIUM;
        }
        return base; // HIGH e UNCLASSIFIABLE restano invariati
    }

    // prenotazioni cross-zona

    // registra quale veicolo è stato riservato per quale emergenza cross-zona e per quale categoria
    public void addPendingReservation(EmergencyId emergencyId, VehicleCategory category, List<VehicleId> vehicleIds) {
        pendingReservations.put(new PendingReservationKey(emergencyId, category), vehicleIds);
    }

    // rimuove il veicolo registrato e lo restituisce come risultato
    public Optional<List<VehicleId>> removePendingReservation(EmergencyId emergencyId, VehicleCategory category) {
        List<VehicleId> vehicleIds = pendingReservations.remove(new PendingReservationKey(emergencyId, category));
        return Optional.ofNullable(vehicleIds);
    }

    // vector clock

    // restituisce il proprio vector clock
    public VectorClock getLocalClock() {
        return localClock;
    }

    // incrementa di 1 il proprio vector clock
    public void recordLocalEvent() {
        this.localClock = localClock.increment(getZoneId());
    }

    // unisce il proprio vector clock con un altro prendendo il massimo di ciascun vector clock
    public void mergeClock(VectorClock other) {
        this.localClock = localClock.merge(other);
    }

    // ripristina il vector clock dopo il crash del nodo
    public void restoreClock(VectorClock recovered) {
        this.localClock = recovered;
    }
}