package node.server;

import communication.contractnet.*;
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
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import node.vehicle.VehicleLifecycleManager;
import node.persistence.ActiveLoan;
import node.zone.ArrivalOutcome;
import node.persistence.VehicleRepository;
import node.zone.ZoneState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// server http del nodo
public class NodeServer extends VerticleBase {

    private final int port;
    private final ZoneState zoneState;
    private final int reservationTimeoutMs;
    private final VehicleRepository vehicleRepository;
    private final VehicleLifecycleManager lifecycleManager;

    public NodeServer(int port, ZoneState zoneState, int reservationTimeoutMs, VehicleRepository vehicleRepository, VehicleLifecycleManager lifecycleManager) {
        this.port = port;
        this.zoneState = zoneState;
        this.reservationTimeoutMs = reservationTimeoutMs;
        this.vehicleRepository = vehicleRepository;
        this.lifecycleManager = lifecycleManager;
    }

    // avvia il server http del nodo
    @Override
    public Future<?> start() {
        Router router = Router.router(vertx); // router che definisce quali richieste il server deve gestire
        router.route().handler(BodyHandler.create()); // necessario per poter leggere il body delle richieste POST

        //rotte
        router.get("/health").handler(ctx -> ctx.response().putHeader("content-type", "text/plain").end("OK"));
        router.post("/contract-net/call-for-proposal").handler(this::handleCallForProposal);
        router.post("/contract-net/resolution").handler(this::handleContractResolution);
        router.post("/contract-net/arrived-on-scene").handler(this::handleArrivedOnScene);
        router.post("/contract-net/on-scene-deadline").handler(this::handleOnSceneDeadline);
        router.post("/contract-net/intervention-completed").handler(this::handleInterventionCompleted);

        // crea e avvia il server http vertx in ascolto sulla porta indicata
        return vertx.createHttpServer().requestHandler(router).listen(port)
                .onSuccess(server -> System.out.println("HTTP server listening on port " + port))
                .onFailure(err -> System.err.println("Failed to start HTTP server: " + err.getMessage()));
    }

    // contractor

    // gestisce una CallForProposal ricevuta da un initiator: cerca il veicolo disponibile più vicino della categoria richiesta, lo prenota temporaneamente e risponde con una ProposalSubmission
    private void handleCallForProposal(RoutingContext ctx) {
        CallForProposal cfp = CallForProposal.fromJson(ctx.body().asJsonObject()); // legge il body della richiesta HTTP (arrivato come JSON)
        List<Vehicle> available = zoneState.getAvailableVehicles(cfp.requiredVehicleCategory()); // recupera dalla propria zona tutti i veicoli AVAILABLE della categoria richiesta
        int availableFleetCount = available.size(); // conta la flotta disponibile totale per questa categoria
        List<Vehicle> feasible = available.stream().filter(v -> { // esclude i veicoli che non hanno abbastanza batteria per raggiungere l'emergenza (sola andata)
            double distance = GeoUtils.haversine(v.getPosition(), cfp.emergencyPosition());
            boolean batteryOk = v.getBatteryLevel() >= GeoUtils.estimatedBatteryConsumptionPercent(distance, cfp.requiredVehicleCategory());
            boolean distanceOk = !cfp.strictDistanceLimit() || GeoUtils.estimatedArrivalMinutes(distance, cfp.requiredVehicleCategory()) <= ZoneState.TOO_FAR_THRESHOLD_MIN;
            return batteryOk && distanceOk;
        }).sorted(Comparator.comparingDouble((Vehicle v) -> GeoUtils.haversine(v.getPosition(), cfp.emergencyPosition())) // ordina la lista per distanza crescente (in km) dalla posizione dell'emergenza
                .thenComparing(Comparator.comparingDouble(Vehicle::getBatteryLevel).reversed()) // a parità di distanza (stesso nodo): criterio secondario, maggiore batteria residua
                .thenComparingInt(v -> v.getId().value().hashCode())).toList(); // tie-breaker finale in caso di parità totale
        List<Vehicle> selected = feasible.stream().limit(cfp.requiredCount()).toList(); // dalla lista ordinata, prende i veicoli nel numero richiesto
        if (selected.isEmpty()) { // se non è stato trovato nessun veicolo
            ctx.response().setStatusCode(204).end(); // invia una risposta positiva (ack)
            return;
        }
        List<VehicleOffer> offers = new ArrayList<>();
        List<VehicleId> reservedIds = new ArrayList<>();
        for (Vehicle vehicle : selected) { // per ogni veicolo della lista ottenuta
            vehicle.setStatus(VehicleStatus.RESERVED); // lo imposta come RESERVED
            double distance = GeoUtils.haversine(vehicle.getPosition(), cfp.emergencyPosition()); // calcola la distanza rispetto all'emergenza
            offers.add(new VehicleOffer(vehicle.getId(), distance, vehicle.getBatteryLevel())); // aggiunge informazioni (id veicolo + distanza + batteria) a una lista
            reservedIds.add(vehicle.getId()); // aggiunge l'id del veicolo alla lista dedicata
        }
        zoneState.addPendingReservation(cfp.emergencyId(), cfp.requiredVehicleCategory(), reservedIds); // aggiunge il veicolo alla lista dei veicoli riservati
        zoneState.storeEmergencyPosition(cfp.emergencyId(), cfp.requiredVehicleCategory(), cfp.emergencyPosition()); // memorizza la posizione dell'emergenza (utile per handleContractResolution)
        scheduleReservationTimeout(cfp.emergencyId(), cfp.requiredVehicleCategory()); // avvia il timer di sicurezza
        ProposalSubmission proposal = new ProposalSubmission(cfp.emergencyId(), cfp.requiredVehicleCategory(), zoneState.getZoneId(), offers, availableFleetCount);
        ctx.response().putHeader("content-type", "application/json").end(proposal.toJson().encode()); // invia un ProposalSubmission
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
            for (VehicleId vehicleId : allReserved.get()) { // per ogni veicolo
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
            ctx.response().setStatusCode(204).end(); // invia una risposta positiva (ack)
        } else { // altrimenti, se la prenotazione non esiste più (la conferma era già stata elaborata - l'initiator la rinvia se non ha ricevuto la risposta - oppure è scaduto il timer)
            boolean alreadyActivated = resolution.confirmedVehicleIds().stream().allMatch(vehicleId -> zoneState.getActiveLoan(vehicleId).map(loan -> loan.emergencyId().equals(resolution.emergencyId())).orElse(false)); // restituisce vero se tutti i veicoli confermati sono già stati attivati per l'emergenza oppure se non è stato confermato nessun veicolo
            if (alreadyActivated) { // se tutti i veicoli confermati già sono stati attivati o non è stato confermato nessun veicolo
                ctx.response().setStatusCode(204).end(); // invia una risposta positiva (ack)
            } else { // altrimenti, è scaduto i timer e i veicoli sono stati rilasciati
                System.out.println("ContractResolution arrived too late for emergency " + resolution.emergencyId().value() + " (" + resolution.category() + "): reservation already timed out");
                ctx.response().setStatusCode(410).end(); // invia una risposta negativa: la prenotazione non esiste più
            }
        }
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
}