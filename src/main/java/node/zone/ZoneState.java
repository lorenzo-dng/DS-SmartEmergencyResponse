package node.zone;

import domain.common.Position;
import domain.emergency.*;
import domain.zone.Zone;
import domain.zone.ZoneId;
import domain.vehicle.Vehicle;
import domain.vehicle.VehicleId;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleStatus;
import domain.utils.GeoUtils;
import domain.utils.BipartiteMatching;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

// stato della zona
public class ZoneState {

    public static final double TOO_FAR_THRESHOLD_MIN = 10.0;
    private static final double REASSIGNMENT_MARGIN_MINUTES = 3.0;
    private static final double BATTERY_EPSILON = 1e-9;
    private static final double ID_EPSILON = 1e-15;
    private static final double INFEASIBLE_COST = 1_000_000.0; // Double.MAX_VALUE è rischioso con l'algoritmo ungherese
    private final Map<VehicleId, Vehicle> vehicles;
    private final Map<EmergencyId, Emergency> emergencies;
    private final Map<PendingReservationKey, List<VehicleId>> pendingReservations;
    private final Map<PendingReservationKey, List<VehicleId>> vehicleBackupReservations;
    private final Map<EmergencyId, Map<VehicleId, BorrowedVehicleInfo>> borrowedVehicles; // veicoli ricevuti in prestito, raggruppati per emergenza
    private final Map<PendingReservationKey, Position> pendingEmergencyPositions;
    private final Map<VehicleId, ActiveLoan> activeLoans;
    private final Map<PendingReservationKey, List<VehicleId>> vehiclesOnScene; // veicoli della categoria già arrivati sull'emergenza
    private final Zone zone;
    private final SqliteDatabase database;
    private final LoanRepository loanRepository;
    private final VehicleRepository vehicleRepository;
    private final EmergencyRepository emergencyRepository;

    public ZoneState(Zone zone, SqliteDatabase database, LoanRepository loanRepository, VehicleRepository vehicleRepository, EmergencyRepository emergencyRepository) {
        this.vehicles = new HashMap<>();
        this.emergencies = new HashMap<>();
        this.pendingReservations = new HashMap<>();
        this.vehicleBackupReservations = new HashMap<>();
        this.borrowedVehicles = new HashMap<>();
        this.pendingEmergencyPositions = new HashMap<>();
        this.activeLoans = new HashMap<>();
        this.vehiclesOnScene = new HashMap<>();
        this.zone = zone;
        this.database = database;
        this.loanRepository = loanRepository;
        this.vehicleRepository = vehicleRepository;
        this.emergencyRepository = emergencyRepository;
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

    // vero se il veicolo è fisicamente posseduto da questa zona (locale, anche se magari prestato ad altri)
    public boolean hasVehicle(VehicleId id) {
        return vehicles.containsKey(id);
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
    public void addBorrowedVehicle(VehicleId vehicleId, EmergencyId emergencyId, VehicleCategory category, ZoneId contractorZoneId) {
        borrowedVehicles.computeIfAbsent(emergencyId, k -> new HashMap<>()).put(vehicleId, new BorrowedVehicleInfo(category, contractorZoneId));
    }

    // rimuove un veicolo preso in prestito da un'altra zona
    public void removeBorrowedVehicle(VehicleId vehicleId, EmergencyId emergencyId) {
        Map<VehicleId, BorrowedVehicleInfo> borrowed = borrowedVehicles.get(emergencyId);
        if (borrowed == null) {
            return;
        }
        borrowed.remove(vehicleId);
        if (borrowed.isEmpty()) {
            borrowedVehicles.remove(emergencyId);
        }
    }

    // restituisce la zona del contractor che ha prestato il veicolo
    public Optional<ZoneId> getBorrowedVehicleContractorZone(VehicleId vehicleId, EmergencyId emergencyId) {
        return Optional.ofNullable(borrowedInfo(vehicleId, emergencyId)).map(BorrowedVehicleInfo::contractorZoneId);
    }

    // recupera le informazioni di un veicolo preso in prestito, oppure null se il veicolo non è un prestito per quell'emergenza
    private BorrowedVehicleInfo borrowedInfo(VehicleId vehicleId, EmergencyId emergencyId) {
        Map<VehicleId, BorrowedVehicleInfo> borrowed = borrowedVehicles.get(emergencyId);
        return borrowed == null ? null : borrowed.get(vehicleId);
    }

    // emergenze

    // recupera un'emergenza
    public Optional<Emergency> getEmergency(EmergencyId id) {
        return Optional.ofNullable(emergencies.get(id));
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

    // registra un'emergenza aperta
    public void addEmergency(Emergency emergency) {
        emergencies.put(emergency.getId(), emergency);
        saveEmergency(emergency); // salva l'emergenza nel db, così da ripristinarla in caso di crash
    }

    // salva l'emergenza con quell'id, se esiste
    private void saveEmergencyById(EmergencyId emergencyId) {
        Emergency emergency = emergencies.get(emergencyId);
        if (emergency != null) {
            saveEmergency(emergency);
        }
    }

    // salva lo stato di un'emergenza nel db
    public void saveEmergency(Emergency emergency) {
        database.inTransaction(conn -> writeEmergency(conn, emergency));
        removeIfClosed(emergency); // solo dopo il commit: se la transazione fallisce, la memoria non viene toccata
    }

    // scrive l'emergenza sulla connessione di una transazione
    private void writeEmergency(Connection conn, Emergency emergency) throws SQLException {
        if (emergency.getStatus() == EmergencyStatus.CLOSED) {
            emergencyRepository.delete(conn, emergency.getId());
        } else {
            emergencyRepository.save(conn, emergency, borrowedOf(emergency.getId()), arrivalsOf(emergency.getId()));
        }
    }

    // recupera i veicoli presi in prestito
    private Map<VehicleId, BorrowedVehicleInfo> borrowedOf(EmergencyId emergencyId) {
        Map<VehicleId, BorrowedVehicleInfo> borrowed = borrowedVehicles.get(emergencyId); // genera un riferimento ai valori della mappa originale (modificando borrowed si modificano i valori originali)
        return borrowed == null ? new HashMap<>() : new HashMap<>(borrowed); // restituisce una copia dei valori di borrowedVehicles
    }

    // recupera i veicoli già arrivati sull'emergenza, per categoria
    private Map<VehicleCategory, List<VehicleId>> arrivalsOf(EmergencyId emergencyId) {
        Map<VehicleCategory, List<VehicleId>> arrivals = new HashMap<>();
        for (Map.Entry<PendingReservationKey, List<VehicleId>> entry : vehiclesOnScene.entrySet()) {
            if (entry.getKey().emergencyId().equals(emergencyId)) {
                arrivals.put(entry.getKey().category(), new ArrayList<>(entry.getValue()));
            }
        }
        return arrivals;
    }

    // elimina (dalla ram) un'emergenza chiusa, con i suoi prestati e veicoli arrivati
    private void removeIfClosed(Emergency emergency) {
        if (emergency.getStatus() == EmergencyStatus.CLOSED) {
            EmergencyId emergencyId = emergency.getId();
            emergencies.remove(emergencyId);
            borrowedVehicles.remove(emergencyId);
            vehiclesOnScene.keySet().removeIf(key -> key.emergencyId().equals(emergencyId));
        }
    }

    // salva il checkpoint di un veicolo e lo stato delle emergenze coinvolte quando un veicolo locale cambia assegnazione (necessario per evitare info incoerenti tra veicoli ed emergenze in caso di crash)
    public void saveVehicleAndEmergencies(VehicleId vehicleId, VehicleStatus status, Position position, Position destination, double batteryLevel, ActiveLoan loan, Long onSceneStartedAtMillis, Long onSceneDeadlineMillis, Emergency... changedEmergencies) {
        database.inTransaction(conn -> {
            vehicleRepository.save(conn, vehicleId, status, position, destination, batteryLevel, loan, onSceneStartedAtMillis, onSceneDeadlineMillis);
            for (Emergency emergency : changedEmergencies) {
                writeEmergency(conn, emergency);
            }
        });
        for (Emergency emergency : changedEmergencies) { // elimina le emergenze chiuse
            removeIfClosed(emergency);
        }
    }

    // al riavvio del nodo, ricarica dal db le info delle emergenze aperte
    public void restoreEmergencies() {
        for (EmergencySnapshot snapshot : emergencyRepository.loadAll()) {
            Emergency emergency = snapshot.emergency();
            emergencies.put(emergency.getId(), emergency); // registra l'emergenza
            if (!snapshot.borrowedVehicles().isEmpty()) { // se esistono veicoli prestati per l'emergenza
                borrowedVehicles.put(emergency.getId(), new HashMap<>(snapshot.borrowedVehicles()));
            }
            for (Map.Entry<VehicleCategory, List<VehicleId>> arrival : snapshot.arrivals().entrySet()) {
                vehiclesOnScene.put(new PendingReservationKey(emergency.getId(), arrival.getKey()), new ArrayList<>(arrival.getValue())); // registra i veicoli arrivati sull'emergenza
            }
        }
    }

    // assegnazione locale

    // (orchestrazione) gestisce le emergenze in arrivo
    public List<VehicleCategory> handleNewEmergency(Emergency emergency) {
        List<VehicleCategory> stillUncovered = new ArrayList<>(); // veicoli mancanti
        for (VehicleRequirement requirement : emergency.getRequiredVehicles()) { // per ogni veicolo richiesto dall'emergenza (categoria + quantita)
            VehicleCategory category = requirement.category();
            assignAvailableVehicles(category); // fase 1
            reassignEnRouteVehicles(category); // fase 2
            releaseBackupIfCovered(emergency, category); // libera il veicolo di backup se il dirottamento ha già coperto lo slot
            if (countAssignedVehiclesOfCategory(emergency, category) < requirement.quantity()) { // se i veicoli di una categoria assegnati all'emergenza sono minori di quelli richiesti
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
            int missing = missingCount(emergency, category); // recupera il numero di veicoli della categoria indicata che devono essere ancora assegnati
            for (int i = 0; i < missing; i++) { // per ogni veicolo ancora da assegnare
                slots.add(emergency); // aggiunge un nuovo slot
            }
        }
        // se non ci sono veicoli disponibili oppure se non ci sono veicoli ancora da assegnare
        if (availableVehicles.isEmpty() || slots.isEmpty()) {
            return;
        }
        double[][] costMatrix = buildCostMatrix(availableVehicles, slots, category); // costruisce una matrice dei costi associando ogni veicolo disponibile a ogni slot dell'emergenza
        int[] assignment = BipartiteMatching.solve(costMatrix); // applica l'algoritmo ungherese di minimizzazione pesata per gravita
        for (int i = 0; i < assignment.length; i++) { // scorre tutto l'array
            if (assignment[i] != -1 && costMatrix[i][assignment[i]] < INFEASIBLE_COST) { // se il veicolo i è stato assegnato a uno slot, e quello slot ha un costo accettabile
                Vehicle vehicle = availableVehicles.get(i); // recupera il veicolo corrispondente alla riga i dalla matrice dei costi
                Emergency emergency = slots.get(assignment[i]); // recupera lo slot (sarebbe il riferimento all'emergenza stessa) corrispondente al veicolo assegnato
                double distanceKm = GeoUtils.haversine(vehicle.getPosition(), emergency.getPosition());
                double arrivalMinutes = GeoUtils.estimatedArrivalMinutes(distanceKm, category);
                if (emergency.getEffectiveSeverity() == Severity.HIGH && arrivalMinutes > TOO_FAR_THRESHOLD_MIN) { // troppo lontano per un'emergenza ad alta gravità: tenuto come piano B, si tenta prima la fase 3
                    vehicle.setStatus(VehicleStatus.RESERVED);
                    // ricordando che a una stessa emergenza possono essere associati piu veicoli di una stessa categoria: crea la coppia (chiave - lista) se non esiste gia, altrimenti se la chiave esiste gia, restituisce la lista. Successivamente aggiunge il veicolo
                    vehicleBackupReservations.computeIfAbsent(new PendingReservationKey(emergency.getId(), category), k -> new ArrayList<>()).add(vehicle.getId());
                } else {
                    vehicle.setStatus(VehicleStatus.EN_ROUTE);
                    vehicle.setDestination(emergency.getPosition());
                    emergency.addAssignedVehicle(vehicle.getId());
                    saveVehicleAndEmergencies(vehicle.getId(), VehicleStatus.EN_ROUTE, vehicle.getPosition(), emergency.getPosition(), vehicle.getBatteryLevel(), null, null, null, emergency); // salva il checkpoint del veicolo e l'emergenza, così da ripristinarli in caso di crash
                }
            }
        }
    }

    // calcola quanti veicoli di una categoria mancano ancora all'emergenza
    public int missingCount(Emergency emergency, VehicleCategory category) {
        return requiredQuantityFor(emergency, category) - countAssignedVehiclesOfCategory(emergency, category);
    }

    // calcola quanti veicoli della categoria sono ancora necessari per l'emergenza
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
            VehicleCategory vehicleCategory = vehicles.containsKey(id) ? getVehicle(id).getCategory() : Optional.ofNullable(borrowedInfo(id, emergency.getId())).map(BorrowedVehicleInfo::category).orElse(null);
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
        double baseCost = arrivalMinutes / emergency.getEffectiveSeverity().getWeight(); // calcola il costo dell'assegnazione
        // tie-breaker
        double batteryTerm = vehicle.getBatteryLevel() * BATTERY_EPSILON; // recupera il valore di batteria trasformandolo in una quantità piccolissima (necessario per evitare un numero troppo grande, che avrebbe molta influenza nel calcolo finale)
        double idTerm = vehicle.getId().value().hashCode() * ID_EPSILON; // recupera il valore (numerico) dell'id trasformandolo in una quantità piccolissima (necessario per evitare un numero troppo grande, che avrebbe molta influenza nel calcolo finale)
        return baseCost - batteryTerm - idTerm; // calcola il costo finale
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
            if (activeLoans.containsKey(vehicle.getId())) { // se il veicolo è stato prestato
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
                saveVehicleAndEmergencies(vehicle.getId(), VehicleStatus.EN_ROUTE, vehicle.getPosition(), newEmergency.getPosition(), vehicle.getBatteryLevel(), null, null, null, previousEmergency, newEmergency); // salva il checkpoint del veicolo e l'emergenza, così da ripristinarli in caso di crash
            }
        }
    }

    // trova l'emergenza attuale del veicolo
    public Optional<Emergency> findAssignedEmergency(VehicleId vehicleId) {
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
        if (currentEmergency.getEffectiveSeverity().getWeight() >= newEmergency.getEffectiveSeverity().getWeight()) { // se l'emergenza attuale del veicolo non ha gravità inferiore
            return INFEASIBLE_COST;
        }
        double distanceKm = GeoUtils.haversine(vehicle.getPosition(), newEmergency.getPosition()); // recupera la distanza dalla posizione attuale del veicolo alla nuova emergenza
        if (vehicle.getBatteryLevel() < GeoUtils.estimatedBatteryConsumptionPercent(distanceKm, category)) { // se la batteria del veicolo è insufficiente per raggiungere la nuova emergenza
            return INFEASIBLE_COST;
        }
        double arrivalMinutes = GeoUtils.estimatedArrivalMinutes(distanceKm, category); // recupera il tempo stimato di arrivo in minuti
        if (newEmergency.getEffectiveSeverity() == Severity.HIGH && arrivalMinutes > TOO_FAR_THRESHOLD_MIN) { // dirottamento verso HIGH consentito solo entro la soglia di 10 min
            return INFEASIBLE_COST;
        }
        double baseCost = arrivalMinutes / newEmergency.getEffectiveSeverity().getWeight(); // recupera il costo dell'assegnazione
        // tie-breaker
        double batteryTerm = vehicle.getBatteryLevel() * BATTERY_EPSILON; // recupera il valore di batteria trasformandolo in una quantità piccolissima (necessario per evitare un numero troppo grande, che avrebbe molta influenza nel calcolo finale)
        double idTerm = vehicle.getId().value().hashCode() * ID_EPSILON; // recupera il valore (numerico) dell'id trasformandolo in una quantità piccolissima (necessario per evitare un numero troppo grande, che avrebbe molta influenza nel calcolo finale)
        return baseCost - batteryTerm - idTerm; // calcola il costo finale
    }

    // rilascia il veicolo di backup se l'emergenza è stata coperta mentre un veicolo era tenuto come piano B (es. da un dirottamento di fase 2)
    private void releaseBackupIfCovered(Emergency emergency, VehicleCategory category) {
        List<VehicleId> backup = vehicleBackupReservations.remove(new PendingReservationKey(emergency.getId(), category)); // recupera la lista dei veicoli di backup di una categoria
        if (backup == null) { // nessun veicolo di backup esistente di quella categoria
            return;
        }
        if (countAssignedVehiclesOfCategory(emergency, category) >= requiredQuantityFor(emergency, category)) { // veicoli già assegnati all'emergenza
            for (VehicleId vehicleId : backup) { // per ogni veicolo di backup di una categoria
                getVehicle(vehicleId).setStatus(VehicleStatus.AVAILABLE); // libera il veicolo
            }
        } else {
            vehicleBackupReservations.put(new PendingReservationKey(emergency.getId(), category), backup); // altrimenti li rimette
        }
    }

    // gestisce i veicoli di backup dopo la fase 3: attiva i veicoli di riserva ancora necessari, rilascia gli altri
    public void resolveVehicleBackup(EmergencyId emergencyId, VehicleCategory category, int remoteConfirmedCount) {
        List<VehicleId> backup = vehicleBackupReservations.remove(new PendingReservationKey(emergencyId, category)); // recupera la lista dei veicoli di backup di una categoria
        if (backup == null) { // nessun veicolo di backup esistente di quella categoria
            return;
        }
        Emergency emergency = getEmergency(emergencyId).orElseThrow(() -> new IllegalArgumentException("Emergency not found in this zone: " + emergencyId));
        int remainingNeed = backup.size() - remoteConfirmedCount; // quanti piano B servono ancora dopo il contributo remoto
        for (int i = 0; i < backup.size(); i++) {
            Vehicle vehicle = getVehicle(backup.get(i));
            if (i < remainingNeed) { // nessuna offerta remota lo ha sostituito: attivato
                vehicle.setStatus(VehicleStatus.EN_ROUTE);
                vehicle.setDestination(emergency.getPosition());
                emergency.addAssignedVehicle(vehicle.getId());
                saveVehicleAndEmergencies(vehicle.getId(), VehicleStatus.EN_ROUTE, vehicle.getPosition(), emergency.getPosition(), vehicle.getBatteryLevel(), null, null, null, emergency); // salva il checkpoint del veicolo e l'emergenza, così da ripristinarli in caso di crash
            } else { // sostituito da un'offerta remota migliore: rilasciato
                vehicle.setStatus(VehicleStatus.AVAILABLE);
            }
        }
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

    // salva la posizione dell'emergenza ricevuta dalla CallForProposal, in modo che il contractor possa assegnarla al momento della ricezione di un messaggio ContractResolution
    public void storeEmergencyPosition(EmergencyId emergencyId, VehicleCategory category, Position position) {
        pendingEmergencyPositions.put(new PendingReservationKey(emergencyId, category), position);
    }

    // rimuove l'informazione della posizione una volta che ContractResolution è stata conclusa, così da non lasciare dati inutili in memoria
    public Optional<Position> removeEmergencyPosition(EmergencyId emergencyId, VehicleCategory category) {
        return Optional.ofNullable(pendingEmergencyPositions.remove(new PendingReservationKey(emergencyId, category)));
    }

    // prestiti attivi (handoff)

    // salva un prestito quando il veicolo parte verso l'emergenza, così da escludere il veicolo del prestito dalla fase 2
    public void addActiveLoan(VehicleId vehicleId, EmergencyId emergencyId, ZoneId requesterZoneId, VehicleCategory category) {
        activeLoans.put(vehicleId, new ActiveLoan(emergencyId, requesterZoneId, category));
    }

    // chiude il prestito in corso per consentire al veicolo di essere riassegnato dalla propria zona una volta che ha concluso l'intervento
    public void closeActiveLoan(VehicleId vehicleId) {
        activeLoans.remove(vehicleId);
    }

    // salva nel db un prestito quando il veicolo ritorna dall'emergenza, così da poter notificare l'initiator che l'emergenza è stata completata
    public void savePendingLoan(VehicleId vehicleId) {
        ActiveLoan loan = activeLoans.get(vehicleId);
        if (loan == null) {
            throw new IllegalStateException("No active loan found to persist for vehicle: " + vehicleId.value());
        }
        loanRepository.save(vehicleId, loan.emergencyId(), loan.requesterZoneId(), loan.category()); // salva nel db (non può essere fatto insieme ad addActiveLoan perchè viene utilizzato per inviare le notifiche di completamento all'initiator. Se venisse fatto in addActiveLoan, allora le notifiche arriverebbero anche quando il veicolo è ancora ON_ROUTE)
    }

    // rimuove dal db un prestito lasciato in sospeso dopo l'intervento del veicolo sull'emergenza, una volta ricevuto l'ack dell'initiator
    public void deletePendingLoan(VehicleId vehicleId, EmergencyId emergencyId) {
        loanRepository.delete(vehicleId, emergencyId);
    }

    // recupera i prestiti ancora in sospeso
    public List<PendingLoan> loadPendingLoans() {
        return loanRepository.loadAll();
    }

    public Optional<ActiveLoan> getActiveLoan(VehicleId vehicleId) {
        return Optional.ofNullable(activeLoans.get(vehicleId));
    }

    public boolean hasActiveLoan(VehicleId vehicleId) {
        return activeLoans.containsKey(vehicleId);
    }

    // barriera per l'intervento dei veicoli sull'emergenza

    // registra l'arrivo di un veicolo su un'emergenza
    public List<VehicleId> registerArrivalOnScene(EmergencyId emergencyId, VehicleCategory category, VehicleId vehicleId) {
        List<VehicleId> arrived = vehiclesOnScene.computeIfAbsent(new PendingReservationKey(emergencyId, category), k -> new ArrayList<>());
        if (!arrived.contains(vehicleId)) { // evita di contare due volte un arrivo duplicato, rimandato dopo un crash del contractor
            arrived.add(vehicleId);
            saveEmergencyById(emergencyId); // salva l'emergenza con l'info dei veicoli arrivati
        }
        return arrived;
    }

    // verifica se tutti i veicoli richiesti di una categoria sono arrivati sull'emergenza
    public boolean isOnSceneGroupComplete(Emergency emergency, VehicleCategory category, List<VehicleId> arrived) {
        return arrived.size() >= requiredQuantityFor(emergency, category);
    }

    public void clearOnSceneGroup(EmergencyId emergencyId, VehicleCategory category) {
        if (vehiclesOnScene.remove(new PendingReservationKey(emergencyId, category)) != null) {
            saveEmergencyById(emergencyId); // salva l'emergenza con l'info del gruppo di veicoli che hanno completato l'intervento
        }
    }
}