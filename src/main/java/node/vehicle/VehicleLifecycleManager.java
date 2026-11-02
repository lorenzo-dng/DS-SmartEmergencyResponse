package node.vehicle;

import communication.handoff.ArrivedOnScene;
import communication.handoff.InterventionCompleted;
import communication.handoff.NodeClient;
import communication.handoff.OnSceneDeadline;
import domain.common.Position;
import domain.emergency.Emergency;
import domain.emergency.EmergencyId;
import domain.utils.GeoUtils;
import domain.vehicle.Vehicle;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.vehicle.VehicleStatus;
import domain.zone.ZoneId;
import io.vertx.core.Vertx;
import node.persistence.*;
import node.zone.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// gestisce il ciclo di vita di un veicolo dopo l'assegnazione: avanzamento, arrivo, intervento, ritorno alla base
public class VehicleLifecycleManager {

    private static final long POSITION_UPDATE_INTERVAL_MS = 250; // frequenza di aggiornamento della posizione
    private static final double MILLIS_PER_HOUR = 3_600_000.0;
    private static final long CHECKPOINT_INTERVAL_MS = 2000; // aggiornamento periodico durante il movimento
    private static final long ON_SCENE_DURATION_MS = 2000; // durata dell'intervento
    private final ZoneState zoneState;
    private final Vertx vertx;
    private final VehicleRepository vehicleRepository;
    private final NodeClient nodeClient;
    private final Map<VehicleId, Long> onSceneStartedAtByVehicle; // tiene traccia dell'orario di inizio intervento sull'emergenza, per ricostruire correttamente il tempo residuo dopo un crash
    private final Map<VehicleId, Long> onSceneDeadlineByVehicle; // tiene traccia del tempo di fine intervento di un veicolo (coincide con il tempo di fine intervento di tutti i veicoli della stessa categoria)
    private final Map<VehicleId, Long> recoveryStartTimeByVehicle; // dopo un crash, per ogni veicolo ripristinato, l'orario da cui ricostruire quanto è accaduto (utilizzato da recoverAfterCrash)

    public VehicleLifecycleManager(ZoneState zoneState, Vertx vertx, VehicleRepository vehicleRepository, NodeClient nodeClient) {
        this.zoneState = zoneState;
        this.vertx = vertx;
        this.vehicleRepository = vehicleRepository;
        this.nodeClient = nodeClient;
        this.onSceneStartedAtByVehicle = new HashMap<>();
        this.onSceneDeadlineByVehicle = new HashMap<>();
        this.recoveryStartTimeByVehicle = new HashMap<>();
    }

    // comune

    // avvia il timer periodico che avanza la posizione di tutti i veicoli in movimento
    public void start() {
        vertx.setPeriodic(POSITION_UPDATE_INTERVAL_MS, id -> advanceAllVehicles());
    }

    // avanza la posizione dei veicoli verso la destinazione (emergenza o base)
    private void advanceAllVehicles() {
        for (Vehicle vehicle : zoneState.getVehicles()) { // per ogni veicolo
            if (isTravelling(vehicle)) {
                double remainingKm = GeoUtils.haversine(vehicle.getPosition(), vehicle.getDestination());
                double stepKm = GeoUtils.averageSpeedKmh(vehicle.getCategory()) * (POSITION_UPDATE_INTERVAL_MS / MILLIS_PER_HOUR); // distanza percorsa a ogni aggiornamento
                if (remainingKm <= stepKm) { // il prossimo step supererebbe la destinazione: arrivato
                    vehicle.setPosition(vehicle.getDestination());
                    onArrival(vehicle, System.currentTimeMillis()); // il veicolo è arrivato all'emergenza
                } else {
                    double fraction = stepKm / remainingKm; // converta i km nella copertura del segmento che separa il veicolo alla destinazione ("quanto segmento deve coprire con x stepKm?")
                    double newLat = vehicle.getPosition().latitude() + fraction * (vehicle.getDestination().latitude() - vehicle.getPosition().latitude());
                    double newLon = vehicle.getPosition().longitude() + fraction * (vehicle.getDestination().longitude() - vehicle.getPosition().longitude());
                    vehicle.setPosition(new Position(newLat, newLon));
                }
            }
        }
    }

    // verifica se un veicolo è in viaggio (verso l'evento o verso la base)
    private boolean isTravelling(Vehicle vehicle) {
        return vehicle.getStatus() == VehicleStatus.EN_ROUTE || (vehicle.getStatus() == VehicleStatus.AVAILABLE && vehicle.getDestination() != null);
    }

    // gestisce l'arrivo del veicolo all'emergenza o alla base
    private void onArrival(Vehicle vehicle, long arrivalTime) {
        if (vehicle.getStatus() == VehicleStatus.EN_ROUTE) { // è in viaggio, ma il prossimo step è quello dell'arrivo all'emergenza
            Optional<ActiveLoan> loan = zoneState.getActiveLoan(vehicle.getId());
            vehicleRepository.save(vehicle.getId(), VehicleStatus.ON_SCENE, vehicle.getPosition(), null, vehicle.getBatteryLevel(), loan.orElse(null), arrivalTime, null); // salva il checkpoint del veicolo con il tempo di arrivo sull'emergenza nel db, così da ripristinarlo in caso di crash
            onSceneStartedAtByVehicle.put(vehicle.getId(), arrivalTime); // registra il tempo di arrivo del veicolo sull'emergenza
            vehicle.setStatus(VehicleStatus.ON_SCENE);
            if (loan.isPresent()) { // se il veicolo è stato prestato
                notifyBorrowedArrival(vehicle, loan.get());
            } else { // se il veicolo è locale
                Emergency emergency = zoneState.findAssignedEmergency(vehicle.getId()).orElseThrow(() -> new IllegalStateException("No emergency found for local vehicle on scene: " + vehicle.getId().value()));
                handleArrivalOnScene(emergency, vehicle.getCategory(), vehicle.getId(), arrivalTime);
            }
        } else { // è arrivato alla base
            vehicle.setDestination(null);
            vehicleRepository.delete(vehicle.getId());
        }
    }

    private void notifyBorrowedArrival(Vehicle vehicle, ActiveLoan loan) {
        ArrivedOnScene arrivalMessage = new ArrivedOnScene(loan.emergencyId(), vehicle.getId(), loan.category(), zoneState.getZoneId());
        nodeClient.notifyArrival(loan.requesterZoneId(), arrivalMessage, () -> scheduleBorrowedCompletion(vehicle.getId(), System.currentTimeMillis())); // notifica l'initiator che il veicolo prestato è arrivato sull'emergenza; se l'initiator rifiuta l'arrivo (il veicolo non serve), l'intervento termina subito e il veicolo torna alla base
    }

    // salva periodicamente le informazioni dei veicoli nel db, così da ricostruirli in caso di crash
    public void startCheckpointing() {
        vertx.setPeriodic(CHECKPOINT_INTERVAL_MS, id -> checkpointAllVehicles());
    }

    private void checkpointAllVehicles() {
        for (Vehicle vehicle : zoneState.getVehicles()) {
            if (vehicle.getStatus() == VehicleStatus.RESERVED || isAtBase(vehicle)) { // se i veicoli sono RESERVED o in base, allora non c'è necessità di salvarne le informazioni, perchè sono fermi
                vehicleRepository.delete(vehicle.getId());
            } else { // se il veicolo è AVAILABLE non in base
                Optional<ActiveLoan> loan = zoneState.getActiveLoan(vehicle.getId());
                vehicleRepository.save(vehicle.getId(), vehicle.getStatus(), vehicle.getPosition(), vehicle.getDestination(), vehicle.getBatteryLevel(), loan.orElse(null), onSceneStartedAtByVehicle.get(vehicle.getId()), onSceneDeadlineByVehicle.get(vehicle.getId())); // salva il checkpoint del veicolo nel db, così da ripristinarlo in caso di crash
            }
        }
    }

    // verifica se un veicolo è alla base
    private boolean isAtBase(Vehicle vehicle) {
        return vehicle.getStatus() == VehicleStatus.AVAILABLE && vehicle.getDestination() == null;
    }

    // recovery dopo un crash

    // ripristina lo stato dei veicoli (ma non quanto accaduto durante il crash)
    public void restoreVehicles(List<VehicleCheckpoint> checkpoints) {
        for (VehicleCheckpoint checkpoint : checkpoints) { // per ogni veicolo non AVAILABLE al momento del crash
            Vehicle vehicle = zoneState.getVehicle(checkpoint.vehicleId());
            vehicle.setStatus(checkpoint.status());
            vehicle.setPosition(checkpoint.currentPosition());
            vehicle.setDestination(checkpoint.destination());
            vehicle.setBatteryLevel(checkpoint.batteryLevel());
            if (checkpoint.emergencyId() != null) { // se era un prestito attivo, lo riaggiunge ai prestiti attivi
                zoneState.addActiveLoan(checkpoint.vehicleId(), checkpoint.emergencyId(), checkpoint.requesterZoneId(), vehicle.getCategory());
            }
            long referenceTime = checkpoint.checkpointAtMillis(); // prende l'orario dell'ultimo salvataggio del checkpoint
            if (checkpoint.status() == VehicleStatus.ON_SCENE && checkpoint.onSceneStartedAtMillis() != null) { // se il veicolo era sull'emergenza e quindi stava iniziando l'intervento
                /*
                ripopola le mappe con l'orario di inizio e di fine intervento letti dal checkpoint:
                1. il funzionamento del nodo legge i valori dalle mappe
                2. il checkpoint periodico riscrive la riga del veicolo prendendo i valori dalle mappe: se fossero vuote, verrebbero sostituiti con null
                */
                onSceneStartedAtByVehicle.put(checkpoint.vehicleId(), checkpoint.onSceneStartedAtMillis());
                if (checkpoint.onSceneDeadlineMillis() != null) {
                    onSceneDeadlineByVehicle.put(checkpoint.vehicleId(), checkpoint.onSceneDeadlineMillis());
                }
                referenceTime = checkpoint.onSceneStartedAtMillis(); // prende il tempo dall'inizio reale dell'intervento sull'emergenza
            }
            recoveryStartTimeByVehicle.put(checkpoint.vehicleId(), referenceTime); // da questo orario verrà ricostruito quanto è accaduto durante il crash
        }
    }

    // ricostruisce il comportamento dei veicoli durante il crash
    public void recoverAfterCrash() {
        resendUnacknowledgedDeadlines(); // notifica al contractor la deadline dell'intervento dei veicoli per cui non è stato ricevuto l'ack
        resendPendingCompletions(); // notifica all'initiator il completamento dei veicoli durante il crash per cui non è stato ricevuto l'ack
        long now = System.currentTimeMillis(); // orario attuale
        for (Map.Entry<VehicleId, Long> entry : recoveryStartTimeByVehicle.entrySet()) { // per ogni veicolo ripristinato
            long elapsedMs = now - entry.getValue(); // tempo trascorso durante il crash
            recoverVehicle(zoneState.getVehicle(entry.getKey()), elapsedMs); // ricostruisce il comportamento del veicolo durante il crash
        }
    }

    // initiator -> contractor: rinvia la deadline dell'intervento dei veicoli presi in prestito per cui non è stato ricevuto l'ack
    private void resendUnacknowledgedDeadlines() {
        for (Map.Entry<EmergencyId, Map<VehicleId, BorrowedVehicleInfo>> emergencyEntry : zoneState.getBorrowedVehicles().entrySet()) { // per ogni emergenza con veicoli presi in prestito
            for (Map.Entry<VehicleId, BorrowedVehicleInfo> entry : emergencyEntry.getValue().entrySet()) { // per ogni veicolo preso in prestito
                BorrowedVehicleInfo info = entry.getValue();
                if (info.deadlineMillis() != null && !info.deadlineAcked()) { // se la scadenza era stata fissata ma non è stato ricevuto l'ack
                    EmergencyId emergencyId = emergencyEntry.getKey();
                    VehicleId vehicleId = entry.getKey();
                    OnSceneDeadline deadlineMessage = new OnSceneDeadline(emergencyId, vehicleId, info.deadlineMillis());
                    nodeClient.notifyDeadline(info.contractorZoneId(), deadlineMessage, () -> zoneState.markDeadlineAcked(emergencyId, vehicleId)); // invia la notifica e registra l'ack se l'ack viene ricevuto
                }
            }
        }
    }

    // contractor -> initiator: rinvia il completamento dell'intervento dei veicoli prestati per cui non è stato ricevuto l'ack
    private void resendPendingCompletions() {
        for (PendingLoan loan : zoneState.loadPendingLoans()) {
            InterventionCompleted completionMessage = new InterventionCompleted(loan.emergencyId(), loan.vehicleId(), loan.category());
            nodeClient.notifyCompletion(loan.requesterZoneId(), completionMessage, () -> zoneState.deletePendingLoan(loan.vehicleId(), loan.emergencyId())); // invia la notifica e cancella il prestito se l'ack viene ricevuto
        }
    }

    // ricostruisce il percorso del veicolo per il tempo trascorso durante il crash
    private void recoverVehicle(Vehicle vehicle, long elapsedMs) {
        long now = System.currentTimeMillis();
        long remainingMs = elapsedMs; // tempo trascorso durante il crash dall'inizio dell'intervento
        while (remainingMs > 0 && !isAtBase(vehicle)) { // finché ci sono ms da recuperare a causa del crash e il veicolo non è tornato alla base
            if (isTravelling(vehicle)) { // se il veicolo sta viaggiando
                remainingMs = catchUpMovement(vehicle, remainingMs, now); // segmento di viaggio (andata o ritorno)
            } else if (vehicle.getStatus() == VehicleStatus.ON_SCENE) { // se il veicolo è sulla scena
                remainingMs = catchUpOnScene(vehicle, remainingMs); // segmento di intervento
            }
        }
    }

    // ricostruisce il viaggio del veicolo
    private long catchUpMovement(Vehicle vehicle, long elapsedMs, long now) {
        double remainingKm = GeoUtils.haversine(vehicle.getPosition(), vehicle.getDestination()); // distanza ancora da percorrere dalla posizione del checkpoint
        double speedKmh = GeoUtils.averageSpeedKmh(vehicle.getCategory());
        long neededMs = (long) ((remainingKm / speedKmh) * MILLIS_PER_HOUR); // tempo necessario per coprire quella distanza
        if (elapsedMs >= neededMs) { // se il tempo trascorso durante il crash supera i ms necessari per arrivare a destinazione
            vehicle.setPosition(vehicle.getDestination()); // imposta la posizione del veicolo direttamente sulla destinazione
            long remainingAfterArrival = elapsedMs - neededMs; // tempo trascorso dopo l'arrivo a destinazione (in eccesso - da usare nel segmento successivo)
            long actualArrivalTime = now - remainingAfterArrival; // orario in cui il veicolo è effettivamente arrivato a destinazione e ha iniziato l'intervento (orario attuale - tempo in eccesso)
            onArrival(vehicle, actualArrivalTime);
            return remainingAfterArrival; // restituisce il tempo in eccesso (il ciclo while continua)
        }
        // il tempo trascorso non basta ad arrivare, quindi il veicolo è ancora in viaggio e si calcola la posizione che ha raggiunto durante il crash
        double fraction = neededMs == 0 ? 1.0 : elapsedMs / (double) neededMs; // quantita di percorso coperta durante il crash
        double newLat = vehicle.getPosition().latitude() + fraction * (vehicle.getDestination().latitude() - vehicle.getPosition().latitude());
        double newLon = vehicle.getPosition().longitude() + fraction * (vehicle.getDestination().longitude() - vehicle.getPosition().longitude());
        vehicle.setPosition(new Position(newLat, newLon)); // determina la nuova posizione
        return 0; // restituisce 0 per far terminare il ciclo while (tutto il tempo è stato recuperato)
    }

    // ricostruisce l'intervento del veicolo sull'emergenza
    private long catchUpOnScene(Vehicle vehicle, long elapsedMs) {
        Long deadline = onSceneDeadlineByVehicle.get(vehicle.getId()); // recupera il tempo di fine intervento del veicolo
        if (deadline != null) { // se il tempo di fine intervento era già conosciuto al momento del crash (erano arrivati tutti i veicoli della categoria sull'emergenza)
            long start = onSceneStartedAtByVehicle.get(vehicle.getId()); // recupera il tempo di inizio intervento
            long durationNeeded = deadline - start; // recupera i ms necessari al completamento dell'intervento
            if (elapsedMs >= durationNeeded) { // l'intervento si è concluso durante il crash
                onInterventionComplete(vehicle);
                return elapsedMs - durationNeeded; // tempo trascorso dopo la fine dell'intervento (utile per il ritorno del veicolo dopo l'intervento)
            }
            long remainingOnSceneMs = durationNeeded - elapsedMs; // ms mancanti al termine dell'intervento ancora in corso
            vertx.setTimer(remainingOnSceneMs, id -> onInterventionComplete(vehicle)); // attende questi ms e poi gestisce il completamento (n.b.: qui il comportamento non è bloccante, quindi si prosegue con l'esecuzione)
            return 0; // restituisce 0 per far terminare il ciclo while
        }
        // altrimenti, se il tempo di fine intervento non era conosciuto al momento del crash (non erano arrivati tutti i veicoli della categoria sull'emergenza)
        Optional<ActiveLoan> loan = zoneState.getActiveLoan(vehicle.getId());
        if (loan.isPresent()) { // se il veicolo è stato dato in prestito
            notifyBorrowedArrival(vehicle, loan.get()); // notifica l'initiator dell'arrivo (potrebbe essere già stato inviato, ma l'initiator ignora i duplicati)
            return 0;
        }
        // se il veicolo è locale
        Emergency emergency = zoneState.findAssignedEmergency(vehicle.getId()).orElseThrow(() -> new IllegalStateException("No emergency found for local vehicle on scene: " + vehicle.getId().value()));
        handleArrivalOnScene(emergency, vehicle.getCategory(), vehicle.getId(), onSceneStartedAtByVehicle.get(vehicle.getId()));
        return 0;
    }

    // initiator

    // gestisce il completamento dell'intervento dei veicoli
    public void handleArrivalOnScene(Emergency emergency, VehicleCategory category, VehicleId vehicleId, long arrivalTimeForDeadline) {
        List<VehicleId> arrived = zoneState.registerArrivalOnScene(emergency.getId(), category, vehicleId);
        if (zoneState.isOnSceneGroupComplete(emergency, category, arrived)) { // se sono arrivati tutti i veicoli sull'emergenza
            completeOnSceneGroup(emergency, category, arrived, arrivalTimeForDeadline);
        }
    }

    // il gruppo è completo: fissa la scadenza condivisa, avvia il timer per i locali, notifica la deadline ai contractor dei veicoli presi in prestito
    private void completeOnSceneGroup(Emergency emergency, VehicleCategory category, List<VehicleId> arrived, long completingArrivalTime) {
        long deadlineMillis = completingArrivalTime + ON_SCENE_DURATION_MS; // orario di completamento dell'intervento
        zoneState.saveOnSceneGroupCompletion(emergency, category, arrived, deadlineMillis, onSceneStartedAtByVehicle::get); // salva nel db, in un'unica transazione, la scadenza dei veicoli locali e dei veicoli prestati e la fine del gruppo di arrivi sull'emergenza
        for (VehicleId vehicleId : arrived) { // per ogni veicolo arrivato sull'emergenza
            if (zoneState.hasVehicle(vehicleId)) { // se il veicolo è locale
                onSceneDeadlineByVehicle.put(vehicleId, deadlineMillis); // registra il tempo di fine intervento del veicolo
                Vehicle groupVehicle = zoneState.getVehicle(vehicleId);
                long delay = Math.max(1, deadlineMillis - System.currentTimeMillis()); // converte l'orario di completamento dell'intervento in una durata (in ms - la funzione è necessaria perchè deadlineMillis è un orario precedente, quindi la sottrazione può dare risultato negativo)
                vertx.setTimer(delay, id -> onInterventionComplete(groupVehicle)); // imposta il timer dopo il quale il veicolo completa l'emergenza
            } else { // se il veicolo è stato prestato
                ZoneId contractorZoneId = zoneState.getBorrowedVehicleContractorZone(vehicleId, emergency.getId()).orElseThrow(() -> new IllegalStateException("No contractor zone found for borrowed vehicle: " + vehicleId.value()));
                OnSceneDeadline deadlineMessage = new OnSceneDeadline(emergency.getId(), vehicleId, deadlineMillis);
                nodeClient.notifyDeadline(contractorZoneId, deadlineMessage, () -> zoneState.markDeadlineAcked(emergency.getId(), vehicleId)); // notifica al contractor il tempo di completamento dell'intervento del veicolo prestato; quando il contractor lo riceve salva l'ack nel db
            }
        }
    }

    // contractor

    // riceve dall'initiator il tempo di completamento dell'intervento del veicolo
    public void scheduleBorrowedCompletion(VehicleId vehicleId, long deadlineMillis) {
        Vehicle vehicle = zoneState.getVehicle(vehicleId);
        if (vehicle.getStatus() == VehicleStatus.ON_SCENE && !onSceneDeadlineByVehicle.containsKey(vehicleId)) { // se il veicolo è sulla scena e la scadenza non è stata già fissata
            onSceneDeadlineByVehicle.put(vehicleId, deadlineMillis); // registra il tempo di fine intervento del veicolo
            vehicleRepository.save(vehicleId, VehicleStatus.ON_SCENE, vehicle.getPosition(), null, vehicle.getBatteryLevel(), zoneState.getActiveLoan(vehicleId).orElse(null), onSceneStartedAtByVehicle.get(vehicleId), deadlineMillis); // salva le info del veicolo
            long delay = Math.max(1, deadlineMillis - System.currentTimeMillis()); // converte l'orario di completamento dell'intervento in una durata (in ms - la funzione è necessaria perchè deadlineMillis è un orario precedente, quindi la sottrazione può dare risultato negativo)
            vertx.setTimer(delay, id -> onInterventionComplete(vehicle)); // imposta il timer dopo il quale il veicolo completa l'emergenza
        }
    }

    // gestisce la fine dell'intervento sull'emergenza
    private void onInterventionComplete(Vehicle vehicle) {
        onSceneStartedAtByVehicle.remove(vehicle.getId()); // l'intervento è concluso, non serve più tracciarne l'inizio
        onSceneDeadlineByVehicle.remove(vehicle.getId()); // l'intervento è concluso, non serve più tracciare il tempo di fine intervento
        if (zoneState.hasActiveLoan(vehicle.getId())) { // se il veicolo è stato prestato
            ActiveLoan loan = zoneState.getActiveLoan(vehicle.getId()).orElseThrow(() -> new IllegalStateException("No active loan found for borrowed vehicle completing intervention: " + vehicle.getId().value()));
            zoneState.savePendingLoan(vehicle.getId()); // salva il prestito nel db (per notificare l'initiator in caso di crash)
            zoneState.closeActiveLoan(vehicle.getId()); // termina il prestito in corso: il veicolo può essere riassegnato dalla propria zona
            prepareForReturn(vehicle, null); // il veicolo torna alla base
            InterventionCompleted completionMessage = new InterventionCompleted(loan.emergencyId(), vehicle.getId(), loan.category());
            nodeClient.notifyCompletion(loan.requesterZoneId(), completionMessage, () -> zoneState.deletePendingLoan(vehicle.getId(), loan.emergencyId())); // notifica l'initiator il completamento dell'intervento del veicolo prestato
        } else { // se il veicolo non era stato prestato
            Emergency emergency = zoneState.findAssignedEmergency(vehicle.getId()).orElseThrow(() -> new IllegalStateException("No emergency found for local vehicle completing intervention: " + vehicle.getId().value()));
            emergency.markVehicleCompleted(vehicle.getId(), vehicle.getCategory());
            prepareForReturn(vehicle, emergency);
        }
        zoneState.handleVehicleAvailable(vehicle.getCategory()); // esegue le fasi 1, 2 quando si libera un veicolo + attiva i backup rilasciando quelli in eccesso
    }

    // prepara il ritorno del veicolo alla base: ricarica di emergenza se necessario, poi avvia il viaggio
    private void prepareForReturn(Vehicle vehicle, Emergency completedEmergency) {
        double distanceToBaseKm = GeoUtils.haversine(vehicle.getPosition(), vehicle.getBasePosition());
        if (vehicle.getBatteryLevel() < GeoUtils.estimatedBatteryConsumptionPercent(distanceToBaseKm, vehicle.getCategory())) {
            vehicle.setBatteryLevel(Vehicle.MAX_BATTERY_LEVEL); // ricarica di emergenza: solo se la carica residua non basterebbe per il ritorno, sempre da fermo
        }
        ActiveLoan loan = zoneState.getActiveLoan(vehicle.getId()).orElse(null);
        if (completedEmergency != null) { // veicolo locale: viene salvato nel db sia il veicolo liberato che l'emergenza completata
            zoneState.saveVehicleAndEmergencies(vehicle.getId(), VehicleStatus.AVAILABLE, vehicle.getPosition(), vehicle.getBasePosition(), vehicle.getBatteryLevel(), loan, null, null, completedEmergency);
        } else { // veicolo prestato: viene salvato nel db solo il veicolo liberato
            vehicleRepository.save(vehicle.getId(), VehicleStatus.AVAILABLE, vehicle.getPosition(), vehicle.getBasePosition(), vehicle.getBatteryLevel(), loan, null, null);
        }
        vehicle.setDestination(vehicle.getBasePosition());
        vehicle.setStatus(VehicleStatus.AVAILABLE);
    }
}