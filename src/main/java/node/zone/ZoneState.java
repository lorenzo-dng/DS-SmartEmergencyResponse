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
    private static final double BATTERY_EPSILON = 1e-9;
    private static final double ID_EPSILON = 1e-15;
    private static final double REASSIGNMENT_MARGIN_MINUTES = 3.0;
    private static final double LOW_TO_MEDIUM_THRESHOLD_MIN = 15.0;
    private static final double LOW_TO_HIGH_THRESHOLD_MIN = 20.0;
    private static final double MEDIUM_TO_HIGH_THRESHOLD_MIN = 5.0;
    private static final double INFEASIBLE_COST = 1_000_000.0; // (Double.MAX_VALUE è rischioso con l'algoritmo ungherese)
    private final Zone zone;
    private final Map<VehicleId, Vehicle> vehicles;
    private final Map<EmergencyId, Emergency> emergencies;
    private final Map<PendingReservationKey, List<VehicleId>> pendingReservations; // tiene traccia di quali veicoli il contractor ha riservato per quale emergenza cross-zona (ma non ancora assegnati)
    private final Map<VehicleId, VehicleCategory> borrowedVehicleCategories; // veicoli presi in prestito da altre zone: (solo id e categoria noti)
    private VectorClock localClock;

    public ZoneState(Zone zone) {
        this.zone = zone;
        this.vehicles = new HashMap<>();
        this.emergencies = new HashMap<>();
        this.pendingReservations = new HashMap<>();
        this.borrowedVehicleCategories = new HashMap<>();
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

    // registra un veicolo preso in prestito da un'altra zona
    public void addBorrowedVehicle(VehicleId vehicleId, VehicleCategory category) {
        borrowedVehicleCategories.put(vehicleId, category);
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

    // (orchestrazione) gestisce le emergenze in arrivo
    public List<VehicleCategory> handleNewEmergency(Emergency emergency) {
        List<VehicleCategory> stillUncovered = new ArrayList<>(); // veicoli mancanti
        for (VehicleRequirement requirement : emergency.getRequiredVehicles()) { // per ogni veicolo richiesto dall'emergenza (categoria + quantita)
            VehicleCategory category = requirement.category();
            assignAvailableVehicles(category); // fase 1
            reassignEnRouteVehicles(category); // fase 2
            if (countAssignedVehiclesOfCategory(emergency, category) < requirement.quantity()) { // se i veicoli di una categoriaassegnati all'emergenza sono minori di quelli richiesti
                stillUncovered.add(category);
            }
        }
        return stillUncovered;
    }

    // fase 1

    // assegna i veicoli liberi di una categoria alle emergenze ancora scoperte della zona
    public void assignAvailableVehicles(VehicleCategory category) {
        List<Vehicle> availableVehicles = getAvailableVehicles(category); // recupera la lista dei veicoli disponibili della categoria indicata
        List<Emergency> slots = new ArrayList<>(); // lista di slot
        for (Emergency emergency : getOpenEmergencies()) { // per ogni emergenza aperta
            int missing = missingCount(emergency, category); // recupera il numero di veicoli della categoria indicata che devono essere ancora assegnati all'emergenza
            for (int i = 0; i < missing; i++) { // per ogni veicolo ancora da assegnare
                slots.add(emergency); // aggiunge un nuovo slot
            }
        }

        // se non ci sono veicoli disponibili oppure se non ci sono veicoli ancora da assegnare
        if (availableVehicles.isEmpty() || slots.isEmpty()) {
            return;
        }

        double[][] costMatrix = buildCostMatrix(availableVehicles, slots, category); // costruisce una matrice dei costi associando ogni veicolo disponibile a ogni slot dell'emergenza
        int[] assignment = BipartiteMatching.solve(costMatrix); // applica l'algoritmo ungherese di minimizzazione pesata per gravita - assignment[i] indica a quale slot è stato assegnato il veicolo i (-1 se non è stato assegnato)
        for (int i = 0; i < assignment.length; i++) { // scorre tutto l'array
            if (assignment[i] != -1 && costMatrix[i][assignment[i]] < INFEASIBLE_COST) { // se il veicolo i è stato assegnato a uno slot, e quello slot ha un costo accettabile
                Vehicle vehicle = availableVehicles.get(i); // recupera il veicolo corrispondente alla riga i dalla matrice dei costi
                Emergency emergency = slots.get(assignment[i]); // recupera lo slot (sarebbe il riferimento all'emergenza stessa) corrispondente al veicolo assegnato
                vehicle.setStatus(VehicleStatus.EN_ROUTE); // aggiorna lo stato del veicolo assegnato
                emergency.addAssignedVehicle(vehicle.getId()); // aggiunge il veicolo alla lista dei veicoli assegnati
            }
        }
    }

    // calcola quanti veicoli di una categoria mancano ancora all'emergenza
    public int missingCount(Emergency emergency, VehicleCategory category) {
        return requiredQuantityFor(emergency, category) - countAssignedVehiclesOfCategory(emergency, category);
    }

    // calcola quanti veicoli della categoria sono ancora necessari per la nuova emergenza
    private int requiredQuantityFor(Emergency emergency, VehicleCategory category) {
        for (VehicleRequirement requirement : emergency.getRequiredVehicles()) { // scorre i veicoli richiesti
            if (requirement.category() == category) { // se la categoria coincide con quella richiesta
                return requirement.quantity(); // restituisce la quantità
            }
        }
        return 0; // altrimenti ritorna 0
    }

    // conta quanti veicoli di una categoria sono stati assegnati a un'emergenza
    private int countAssignedVehiclesOfCategory(Emergency emergency, VehicleCategory category) {
        int count = 0;
        for (VehicleId id : emergency.getAssignedVehicles()) {
            VehicleCategory vehicleCategory = vehicles.containsKey(id) ? getVehicle(id).getCategory() : borrowedVehicleCategories.get(id);
            if (vehicleCategory == category) {
                count++;
            }
        }
        return count;
    }

    // costruisce la matrice dei costi (viene costruita una matrice per categoria di veicoli richiesta)
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
        if (vehicle.getBatteryLevel() < GeoUtils.estimatedBatteryConsumptionPercent(distanceKm, category)) {
            return INFEASIBLE_COST; // batteria insufficiente per raggiungere l'emergenza (sola andata)
        }
        double arrivalMinutes = GeoUtils.estimatedArrivalMinutes(distanceKm, category); // calcola il tempo stimato di arrivo in minuti
        double baseCost = arrivalMinutes / effectiveSeverity(emergency).getWeight(); // calcola il costo dell'assegnazione
        // tie-breaker
        double batteryTerm = vehicle.getBatteryLevel() * BATTERY_EPSILON; // recupera il valore di batteria trasformandolo in una quantità piccolissima (necessario per evitare un numero troppo grande, che avrebbe molta influenza nel calcolo finale)
        double idTerm = vehicle.getId().value().hashCode() * ID_EPSILON; // recupera il valore (numerico) dell'id trasformandolo in una quantità piccolissima (necessario per evitare un numero troppo grande, che avrebbe molta influenza nel calcolo finale)
        return baseCost - batteryTerm - idTerm; // calcola il costo finale
    }

    // calcola la gravità effettiva dell'emergenza
    private Severity effectiveSeverity(Emergency emergency) {
        Severity base = emergency.getSeverity(); // recupera la gravità iniziale
        double agingMinutes = emergency.getAgingTimeMillis() / MILLIS_PER_MINUTE; // converte i ms di aging in min

        if (base == Severity.LOW) { // se la gravità iniziale è LOW
            if (agingMinutes >= LOW_TO_HIGH_THRESHOLD_MIN) return Severity.HIGH;
            if (agingMinutes >= LOW_TO_MEDIUM_THRESHOLD_MIN) return Severity.MEDIUM;
            return Severity.LOW;
        }
        if (base == Severity.MEDIUM) { // se la gravità iniziale è MEDIUM
            return agingMinutes >= MEDIUM_TO_HIGH_THRESHOLD_MIN ? Severity.HIGH : Severity.MEDIUM;
        }
        return base; // le gravità HIGH e UNCLASSIFIABLE restano invariati
    }

    // fase 2

    // riassegna i veicoli in transito
    public void reassignEnRouteVehicles(VehicleCategory category) {
        List<Emergency> slots = new ArrayList<>(); // lista di slot
        for (Emergency emergency : getOpenEmergencies()) { // per ogni emergenza aperta
            int missing = missingCount(emergency, category); // recupera il numero di veicoli della categoria indicata che devono essere ancora assegnati
            for (int i = 0; i < missing; i++) { // per ogni veicolo ancora da assegnare
                slots.add(emergency); // aggiunge uno slot
            }
        }

        // se non ci sono veicoli ancora da assegnare
        if (slots.isEmpty()) {
            return;
        }

        List<Vehicle> candidates = new ArrayList<>(); // lista di veicoli EN_ROUTE candidati alla riassegnazione
        List<Emergency> origins = new ArrayList<>(); // lista di emergenze dei veicoli candidati
        for (Vehicle vehicle : vehicles.values()) { // scorre tutti i veicoli della zona
            if (vehicle.getCategory() != category || vehicle.getStatus() != VehicleStatus.EN_ROUTE) { // filtra per categoria e stato
                continue;
            }
            double remainingKm = GeoUtils.haversine(vehicle.getPosition(), vehicle.getDestination());
            double remainingMinutes = GeoUtils.estimatedArrivalMinutes(remainingKm, category); // recupera il tempo rimanente verso la destinazione attuale
            if (remainingMinutes <= REASSIGNMENT_MARGIN_MINUTES) { // troppo vicino per essere dirottato
                continue;
            }
            Emergency currentEmergency = findAssignedEmergency(vehicle.getId()).orElseThrow(() -> new IllegalStateException("No emergency found assigned to EN_ROUTE vehicle: " + vehicle.getId().value())); // recupera l'emergenza del veicolo
            candidates.add(vehicle); // lo aggiunge ai candidati
            origins.add(currentEmergency); // aggiunge l'emergenza nella lista
        }
        if (candidates.isEmpty()) { // nessun candidato disponibile per la riassegnazione
            return;
        }

        double[][] costMatrix = buildReassignmentCostMatrix(candidates, origins, slots, category); // costruisce la matrice dei costi di riassegnazione
        int[] assignment = BipartiteMatching.solve(costMatrix); // applica l'algoritmo ungherese
        for (int i = 0; i < assignment.length; i++) { // per ogni slot della matrice
            if (assignment[i] != -1 && costMatrix[i][assignment[i]] < INFEASIBLE_COST) { // se l'assegnazione è valida (il veicolo è stato assegnato e il costo non è fuori scala)
                Vehicle vehicle = candidates.get(i); // recupera il veicolo dalla lista dei candidati
                Emergency previousEmergency = origins.get(i); // trova l'emergenza a cui il veicolo era precedentemente assegnato
                Emergency newEmergency = slots.get(assignment[i]); // recupera l'emergenza da coprire
                previousEmergency.removeAssignedVehicle(vehicle.getId()); // rimuove il veicolo dalla precedente emergenza (che torna nel pool)
                vehicle.setDestination(newEmergency.getPosition()); // imposta la posizione della nuova emergenza come nuova destinazione
                newEmergency.addAssignedVehicle(vehicle.getId()); // assegna il veicolo alla nuova emergenza
            }
        }
    }

    // trova l'emergenza attuale del veicolo
    private Optional<Emergency> findAssignedEmergency(VehicleId vehicleId) {
        for (Emergency emergency : emergencies.values()) { // scorre tutte le emergenze
            if (emergency.getAssignedVehicles().contains(vehicleId)) { // se il veicolo è assegnato all'emergenza, restituisce l'emergenza
                return Optional.of(emergency);
            }
        }
        return Optional.empty();
    }

    // costruisce la matrice dei costi di riassegnazione tra candidati EN_ROUTE e slot delle emergenze scoperte (viene costruita una matrice per categoria di veicoli richiesta)
    private double[][] buildReassignmentCostMatrix(List<Vehicle> candidates, List<Emergency> origins, List<Emergency> slots, VehicleCategory category) {
        double[][] matrix = new double[candidates.size()][slots.size()];
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = 0; j < slots.size(); j++) {
                matrix[i][j] = computeReassignmentCost(candidates.get(i), origins.get(i), slots.get(j), category);
            }
        }
        return matrix;
    }

    // calcola il costo di riassegnare un veicolo EN_ROUTE da un'emergenza di origine a una scoperta
    private double computeReassignmentCost(Vehicle vehicle, Emergency currentEmergency, Emergency newEmergency, VehicleCategory category) {
        if (effectiveSeverity(currentEmergency).getWeight() >= effectiveSeverity(newEmergency).getWeight()) { // se l'emergenza attuale del veicolo non ha gravità inferiore
            return INFEASIBLE_COST;
        }
        double distanceKm = GeoUtils.haversine(vehicle.getPosition(), newEmergency.getPosition()); // recupera la distanza dalla posizione attuale del veicolo alla nuova emergenza
        if (vehicle.getBatteryLevel() < GeoUtils.estimatedBatteryConsumptionPercent(distanceKm, category)) { // se la batteria del veicolo è insufficiente per raggiungere la nuova emergenza
            return INFEASIBLE_COST;
        }
        double arrivalMinutes = GeoUtils.estimatedArrivalMinutes(distanceKm, category); // recupera il tempo stimato di arrivo in minuti
        double baseCost = arrivalMinutes / effectiveSeverity(newEmergency).getWeight(); // recupera il costo dell'assegnazione
        // tie-breaker
        double batteryTerm = vehicle.getBatteryLevel() * BATTERY_EPSILON; // recupera il valore di batteria trasformandolo in una quantità piccolissima (necessario per evitare un numero troppo grande, che avrebbe molta influenza nel calcolo finale)
        double idTerm = vehicle.getId().value().hashCode() * ID_EPSILON; // recupera il valore (numerico) dell'id trasformandolo in una quantità piccolissima (necessario per evitare un numero troppo grande, che avrebbe molta influenza nel calcolo finale)
        return baseCost - batteryTerm - idTerm; // calcola il costo finale
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