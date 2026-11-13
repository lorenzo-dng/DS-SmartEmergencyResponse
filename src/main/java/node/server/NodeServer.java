package node.server;

import communication.contractnet.*;
import communication.emergency.EmergencyRequest;
import communication.handoff.ArrivedOnScene;
import communication.handoff.InterventionCompleted;
import communication.handoff.OnSceneDeadline;
import domain.common.Position;
import domain.emergency.Emergency;
import domain.emergency.EmergencyId;
import domain.utils.GeoUtils;
import domain.vehicle.Vehicle;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.vehicle.VehicleStatus;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import node.vehicle.VehicleLifecycleManager;
import node.persistence.ActiveLoan;
import node.zone.ArrivalOutcome;
import node.persistence.VehicleRepository;
import node.zone.EmergencyDispatcher;
import node.zone.ZoneState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// server http del nodo
public class NodeServer extends VerticleBase {

    private final int port;
    private final ZoneState zoneState;
    private final int reservationTimeoutMs;
    private final VehicleRepository vehicleRepository;
    private final VehicleLifecycleManager lifecycleManager;
    private final EmergencyDispatcher emergencyDispatcher;

    public NodeServer(int port, ZoneState zoneState, int reservationTimeoutMs, VehicleRepository vehicleRepository, VehicleLifecycleManager lifecycleManager, EmergencyDispatcher emergencyDispatcher) {
        this.port = port;
        this.zoneState = zoneState;
        this.reservationTimeoutMs = reservationTimeoutMs;
        this.vehicleRepository = vehicleRepository;
        this.lifecycleManager = lifecycleManager;
        this.emergencyDispatcher = emergencyDispatcher;
    }

    // avvia il nodo
    @Override
    public Future<?> start() {
        lifecycleManager.recoverAfterCrash(); // ricostruisce il comportamento dei veicoli durante il crash e rinvia ai vicini completamenti e scadenze non confermati
        emergencyDispatcher.resendUnconfirmedResolutions(); // l'initiator rinvia ai contractor le conferme non ancora ricevute (fase 3)
        lifecycleManager.start(); // avvia la navigazione dei veicoli in movimento
        lifecycleManager.startCheckpointing(); // avvia il salvataggio periodico dei checkpoint dei veicoli
        return startHttpServer(); // avvia il server
    }

    // avvia il server http del nodo
    private Future<?> startHttpServer() {
        Router router = Router.router(vertx); // router che definisce quali richieste il server deve gestire
        router.route().handler(BodyHandler.create()); // necessario per poter leggere il body delle richieste POST

        // rotte
        router.get("/health").handler(ctx -> ctx.response().putHeader("content-type", "text/plain").end("OK"));
        router.post("/contract-net/call-for-proposal").handler(this::handleCallForProposal);
        router.post("/contract-net/resolution").handler(this::handleContractResolution);
        router.post("/contract-net/arrived-on-scene").handler(this::handleArrivedOnScene);
        router.post("/contract-net/on-scene-deadline").handler(this::handleOnSceneDeadline);
        router.post("/contract-net/intervention-completed").handler(this::handleInterventionCompleted);
        router.post("/emergencies").handler(this::handleNewEmergency);

        // crea e avvia il server http vertx in ascolto sulla porta indicata
        return vertx.createHttpServer().requestHandler(router).listen(port);
    }

    // contractor

    // gestisce una CallForProposal ricevuta da un initiator: cerca i veicoli disponibili più vicini della categoria richiesta, li prenota temporaneamente e risponde con una ProposalSubmission
    private void handleCallForProposal(RoutingContext ctx) {
        CallForProposal cfp = CallForProposal.fromJson(ctx.body().asJsonObject()); // legge il body della richiesta HTTP (arrivato come JSON)
        List<Vehicle> available = zoneState.getAvailableVehicles(cfp.requiredVehicleCategory()); // recupera dalla propria zona tutti i veicoli AVAILABLE della categoria richiesta
        List<Vehicle> selected = selectVehiclesToOffer(cfp, available); // sceglie i veicoli da offrire all'initiator
        if (selected.isEmpty()) { // se non è stato trovato nessun veicolo
            ctx.response().setStatusCode(204).end(); // invia una risposta positiva (ack)
            return;
        }
        List<VehicleOffer> offers = reserveVehicles(cfp, selected); // prenota i veicoli scelti
        ProposalSubmission proposal = new ProposalSubmission(cfp.emergencyId(), cfp.requiredVehicleCategory(), zoneState.getZoneId(), offers, available.size()); // available.size() è la flotta disponibile totale per questa categoria
        ctx.response().putHeader("content-type", "application/json").end(proposal.toJson().encode()); // invia un ProposalSubmission
    }

    // sceglie i veicoli da offrire: tra quelli disponibili esclude i veicoli che non hanno abbastanza batteria per raggiungere l'emergenza (sola andata) e, se richiesto, quelli oltre la soglia di distanza; poi li ordina e prende il numero richiesto
    private List<Vehicle> selectVehiclesToOffer(CallForProposal cfp, List<Vehicle> available) {
        return available.stream().filter(v -> {
                    double distance = GeoUtils.haversine(v.getPosition(), cfp.emergencyPosition());
                    boolean batteryOk = v.getBatteryLevel() >= GeoUtils.estimatedBatteryConsumptionPercent(distance, cfp.requiredVehicleCategory());
                    boolean distanceOk = !cfp.strictDistanceLimit() || GeoUtils.estimatedArrivalMinutes(distance, cfp.requiredVehicleCategory()) <= ZoneState.TOO_FAR_THRESHOLD_MIN;
                    return batteryOk && distanceOk;
                }).sorted(Comparator.comparingDouble((Vehicle v) -> GeoUtils.haversine(v.getPosition(), cfp.emergencyPosition())) // ordina la lista per distanza crescente (in km) dalla posizione dell'emergenza
                        .thenComparing(Comparator.comparingDouble(Vehicle::getBatteryLevel).reversed()) // a parità di distanza (stesso nodo): criterio secondario, maggiore batteria residua
                        .thenComparingInt(v -> v.getId().value().hashCode())) // tie-breaker finale in caso di parità totale
                .limit(cfp.requiredCount()) // prende i veicoli nel numero richiesto
                .toList();
    }

    // prenota i veicoli scelti (RESERVED), registra la prenotazione e la posizione dell'emergenza (utili per handleContractResolution) e avvia il timer di sicurezza; restituisce le offerte da inviare all'initiator
    private List<VehicleOffer> reserveVehicles(CallForProposal cfp, List<Vehicle> selected) {
        List<VehicleOffer> offers = new ArrayList<>();
        List<VehicleId> reservedIds = new ArrayList<>();
        for (Vehicle vehicle : selected) { // per ogni veicolo della lista ottenuta
            vehicle.setStatus(VehicleStatus.RESERVED); // lo imposta come RESERVED
            double distance = GeoUtils.haversine(vehicle.getPosition(), cfp.emergencyPosition()); // calcola la distanza rispetto all'emergenza
            offers.add(new VehicleOffer(vehicle.getId(), distance, vehicle.getBatteryLevel())); // aggiunge informazioni (id veicolo + distanza + batteria) a una lista
            reservedIds.add(vehicle.getId()); // aggiunge l'id del veicolo alla lista dedicata
        }
        zoneState.addPendingReservation(cfp.emergencyId(), cfp.requiredVehicleCategory(), reservedIds); // aggiunge il veicolo alla lista dei veicoli riservati
        zoneState.storeEmergencyPosition(cfp.emergencyId(), cfp.requiredVehicleCategory(), cfp.emergencyPosition()); // memorizza la posizione dell'emergenza
        scheduleReservationTimeout(cfp.emergencyId(), cfp.requiredVehicleCategory()); // avvia il timer di sicurezza
        return offers;
    }

    // gestione del timeout: l'initiator è crashato/irraggiungibile
    private void scheduleReservationTimeout(EmergencyId emergencyId, VehicleCategory category) {
        vertx.setTimer(reservationTimeoutMs, timerId -> { // se il timer è trascorso
            Optional<List<VehicleId>> timedOut = zoneState.removePendingReservation(emergencyId, category); // rimuove i veicoli prenotati (se non gia rimossi da un ContractResolution precedente)
            zoneState.removeEmergencyPosition(emergencyId, category);
            timedOut.ifPresent(vehicleIds -> { // se esistevano veicoli riservati
                for (VehicleId vehicleId : vehicleIds) { // scorre tutti i veicoli (di una specifica categoria) che erano riservati per questa emergenza
                    zoneState.getVehicle(vehicleId).setStatus(VehicleStatus.AVAILABLE); // li libera
                }
                System.out.println("Reservation timed out for emergency " + emergencyId.value() + " (" + category + "): released " + vehicleIds.size() + " vehicle(s), initiator presumed unreachable"); // log informativo, utile per demo/debug
            });
        });
    }

    // riceve l'esito conclusivo di una negoziazione: i veicoli in confirmedVehicleIds vengono assegnati definitivamente, tutti gli altri (della stessa categoria) riservati per questa emergenza vengono rilasciati
    private void handleContractResolution(RoutingContext ctx) {
        ContractResolution resolution = ContractResolution.fromJson(ctx.body().asJsonObject());
        Optional<List<VehicleId>> allReserved = zoneState.removePendingReservation(resolution.emergencyId(), resolution.category()); // recupera tutti i veicoli riservati per questa negoziazione
        Optional<Position> emergencyPosition = zoneState.removeEmergencyPosition(resolution.emergencyId(), resolution.category());
        if (allReserved.isPresent()) { // se la prenotazione esiste ancora (se l'initiator comunica entro il timer)
            applyResolution(resolution, allReserved.get(), emergencyPosition); // attiva i veicoli confermati e rilascia gli altri
            ctx.response().setStatusCode(204).end(); // invia una risposta positiva (ack)
        } else if (isAlreadyActivated(resolution)) { // altrimenti, se la prenotazione non esiste più perché la conferma era già stata elaborata (l'initiator la rinvia se non ha ricevuto la risposta) oppure non è stato confermato nessun veicolo
            ctx.response().setStatusCode(204).end(); // invia una risposta positiva (ack)
        } else { // altrimenti, è scaduto il timer e i veicoli sono stati rilasciati
            System.out.println("ContractResolution arrived too late for emergency " + resolution.emergencyId().value() + " (" + resolution.category() + "): reservation already timed out");
            ctx.response().setStatusCode(410).end(); // invia una risposta negativa: la prenotazione non esiste più
        }
    }

    // attiva i veicoli confermati (prestito attivo, checkpoint nel db e partenza verso l'emergenza) e rilascia gli altri veicoli riservati
    private void applyResolution(ContractResolution resolution, List<VehicleId> reservedIds, Optional<Position> emergencyPosition) {
        for (VehicleId vehicleId : reservedIds) { // per ogni veicolo
            Vehicle vehicle = zoneState.getVehicle(vehicleId);
            if (resolution.confirmedVehicleIds().contains(vehicleId)) { // se il veicolo è stato confermato
                Position destination = emergencyPosition.orElseThrow(() -> new IllegalStateException("Missing emergency position for confirmed vehicle: " + vehicleId.value()));
                ActiveLoan loan = new ActiveLoan(resolution.emergencyId(), resolution.requesterZoneId(), resolution.category());
                vehicleRepository.save(vehicleId, VehicleStatus.EN_ROUTE, vehicle.getPosition(), destination, vehicle.getBatteryLevel(), loan, null, null); // salva il checkpoint del veicolo con l'informazione del prestito nel db, così da ripristinarlo in caso di crash
                vehicle.setStatus(VehicleStatus.EN_ROUTE);
                vehicle.setDestination(destination);
                zoneState.addActiveLoan(vehicleId, resolution.emergencyId(), resolution.requesterZoneId(), resolution.category()); // salva l'attivazione del prestito
            } else { // se il veicolo non è stato confermato
                vehicle.setStatus(VehicleStatus.AVAILABLE);
            }
        }
    }

    // restituisce vero se tutti i veicoli confermati sono già stati attivati per l'emergenza oppure se non è stato confermato nessun veicolo
    private boolean isAlreadyActivated(ContractResolution resolution) {
        return resolution.confirmedVehicleIds().stream().allMatch(vehicleId -> zoneState.getActiveLoan(vehicleId).map(loan -> loan.emergencyId().equals(resolution.emergencyId())).orElse(false));
    }

    // initiator

    // gestisce la ricezione della notifica di arrivo di un veicolo sull'emergenza
    private void handleArrivedOnScene(RoutingContext ctx) {
        ArrivedOnScene notification = ArrivedOnScene.fromJson(ctx.body().asJsonObject());
        Optional<Emergency> emergency = zoneState.getEmergency(notification.emergencyId());
        if (emergency.isEmpty()) { // se l'emergenza non è più aperta (il veicolo che è arrivato non era previsto, e nel frattempo l'emergenza è stata coperta dalla fase 1,2)
            ctx.response().setStatusCode(409).end(); // invia una risposta negativa
        } else { // altrimenti
            ArrivalOutcome outcome = zoneState.admitArrival(emergency.get(), notification.vehicleId(), notification.category(), notification.contractorZoneId()); // verifica se accettare o meno l'arrivo del veicolo
            switch (outcome) {
                case ADMITTED -> {
                    lifecycleManager.handleArrivalOnScene(emergency.get(), notification.category(), notification.vehicleId(), System.currentTimeMillis());
                    ctx.response().setStatusCode(204).end(); // invia una risposta positiva (ack)
                }
                case ALREADY_REGISTERED -> ctx.response().setStatusCode(204).end(); // notifica duplicata (il contractor non ha ricevuto ancora l'ack)
                case REJECTED -> ctx.response().setStatusCode(409).end(); // invia una risposta negativa (il contractor libera il veicolo)
            }
        }
    }

    // contractor

    // gestisce la ricezione della notifica del tempo di completamento previsto per il veicolo prestato
    private void handleOnSceneDeadline(RoutingContext ctx) {
        OnSceneDeadline notification = OnSceneDeadline.fromJson(ctx.body().asJsonObject());
        lifecycleManager.scheduleBorrowedCompletion(notification.vehicleId(), notification.deadlineMillis());
        ctx.response().setStatusCode(204).end(); // invia una risposta positiva (ack)
    }

    // initiator

    // gestisce la ricezione della notifica di completamento dell'intervento del veicolo prestato
    private void handleInterventionCompleted(RoutingContext ctx) {
        InterventionCompleted notification = InterventionCompleted.fromJson(ctx.body().asJsonObject());
        Optional<Emergency> emergency = zoneState.getEmergency(notification.emergencyId()); // recupera l'id dell'emergenza
        if (emergency.isPresent()) { // se l'emergenza è ancora aperta
            emergency.get().markVehicleCompleted(notification.vehicleId(), notification.category()); // segna che il veicolo ha completato l'intervento sull'emergenza
            zoneState.removeBorrowedVehicle(notification.vehicleId(), notification.emergencyId());
            zoneState.saveEmergency(emergency.get()); // salva lo stato aggiornato dell'emergenza nel db
        }
        ctx.response().setStatusCode(204).end(); // invia una risposta positiva (ack)
    }

    // gestisce la segnalazione di una nuova emergenza: legge e valida la richiesta (con il triage); se esiste già un'emergenza aperta della stessa categoria nelle vicinanze la segnalazione è una seconda osservazione di quella emergenza, altrimenti registra una nuova emergenza
    private void handleNewEmergency(RoutingContext ctx) {
        EmergencyRequest request;
        try {
            request = EmergencyRequest.fromJson(ctx.body().asJsonObject());
        } catch (IllegalArgumentException | DecodeException e) { // se la richiesta non è valida (campo mancante, valore non valido, json malformato)
            ctx.response().setStatusCode(400).putHeader("content-type", "text/plain").end("Invalid request: " + e.getMessage());
            return;
        }

        // for prevention - can be eliminated
        if (!zoneState.getZone().contains(request.position())) { // se la posizione non appartiene a questa zona (la gui ha inviato la richiesta alla zona sbagliata)
            ctx.response().setStatusCode(400).putHeader("content-type", "text/plain").end("Invalid request: position outside the zone of this node");
            return;
        }

        Optional<Emergency> existing = zoneState.findOpenEmergencyNear(request.type(), request.position()); // cerca un'emergenza già aperta della stessa categoria nelle vicinanze
        if (existing.isPresent()) { // se esiste
            registerRequest(existing.get(), request);
            respondWithEmergencyId(ctx, 200, existing.get()); // risponde con l'id dell'emergenza già esistente
        } else { // altrimenti è una nuova emergenza
            respondWithEmergencyId(ctx, 201, createEmergency(request)); // risponde con l'id della nuova emergenza
        }
    }

    // registra la segnalazione come seconda osservazione di un'emergenza già aperta: aggiorna gravità (media) e veicoli richiesti, e tenta di assegnare gli eventuali veicoli aggiuntivi (fasi 1 e 2)
    private void registerRequest(Emergency emergency, EmergencyRequest request) {
        emergency.updateRequest(request.triage().severity(), request.triage().vehiclesNeeded());
        zoneState.saveEmergency(emergency); // salva l'emergenza aggiornata nel db
        zoneState.handleUpdatedEmergency(emergency); // aggiorna l'emergenza
    }

    // crea la nuova emergenza e avvia le fasi 1, 2, 3
    private Emergency createEmergency(EmergencyRequest request) {
        Emergency emergency = new Emergency(new EmergencyId(UUID.randomUUID().toString()), request.type(), request.position(), zoneState.getZoneId(), Instant.now());
        emergency.setSeverity(request.triage().severity()); // gravità determinata dal triage
        emergency.setRequiredVehicles(request.triage().vehiclesNeeded()); // veicoli richiesti determinati dal triage
        zoneState.addEmergency(emergency); // registra l'emergenza e la salva nel db
        emergencyDispatcher.dispatch(emergency).onFailure(err -> System.err.println("Dispatch failed for emergency " + emergency.getId().value() + ": " + err.getMessage())); // avvia le fasi 1, 2, 3
        return emergency;
    }

    // risponde con l'id dell'emergenza
    private void respondWithEmergencyId(RoutingContext ctx, int statusCode, Emergency emergency) {
        ctx.response().setStatusCode(statusCode).putHeader("content-type", "application/json").end(new JsonObject().put("emergencyId", emergency.getId().value()).encode());
    }
}