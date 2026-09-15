package node.server;

import communication.contractnet.*;
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

    public NodeServer(int port, ZoneState zoneState, int reservationTimeoutMs) {
        this.port = port;
        this.zoneState = zoneState;
        this.reservationTimeoutMs = reservationTimeoutMs;
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

        // crea e avvia il server http vertx in ascolto sulla porta indicata
        return vertx.createHttpServer().requestHandler(router).listen(port)
                .onSuccess(server -> System.out.println("HTTP server listening on port " + port))
                .onFailure(err -> System.err.println("Failed to start HTTP server: " + err.getMessage()));
    }

    // gestisce una CallForProposal ricevuta da un vicino: cerca il veicolo disponibile più vicino della categoria richiesta, lo prenota temporaneamente e risponde con una ProposalSubmission;
    // se non ha veicoli disponibili risponde 204
    private void handleCallForProposal(RoutingContext ctx) {
        CallForProposal cfp = CallForProposal.fromJson(ctx.body().asJsonObject()); // legge il body della richiesta HTTP (arrivato come JSON)
        List<Vehicle> available = zoneState.getAvailableVehicles(cfp.requiredVehicleCategory()); // recupera dalla propria zona tutti i veicoli AVAILABLE della categoria richiesta
        available.sort(Comparator.comparingDouble(v -> GeoUtils.haversine(v.getPosition(), cfp.eventPosition()))); // ordina la lista per distanza crescente (in km) dalla posizione dell'evento:
        List<Vehicle> selected = available.stream().limit(cfp.requiredCount()).toList(); // dalla lista ordinata, prende i veicoli nel numero richiesto

        if (selected.isEmpty()) { // se non è stato trovato nessun veicolo
            ctx.response().setStatusCode(204).end();  //indica "la richiesta è stata elaborata con successo, ma non c'è nessun contenuto da restituire nel body della risposta" (il contractor non risponde)
            return;
        }

        List<VehicleOffer> offers = new ArrayList<>();
        List<VehicleId> reservedIds = new ArrayList<>();
        for (Vehicle vehicle : selected) { // per ogni veicolo della lista ottenuta
            vehicle.setStatus(VehicleStatus.RESERVED); // lo imposta come RESERVED
            double distance = GeoUtils.haversine(vehicle.getPosition(), cfp.eventPosition()); // calcola la posizione rispetto all'evento
            offers.add(new VehicleOffer(vehicle.getId(), distance)); // aggiunge informazioni (id veicolo + posizione) a una lista
            reservedIds.add(vehicle.getId()); // aggiunge l'id del veicolo alla lista dedicata (utile per prenotazione interna)
        }

        zoneState.addPendingReservation(cfp.emergencyId(), cfp.requiredVehicleCategory(), reservedIds); // aggiunge il veicolo alla lista dei veicoli riservati
        scheduleReservationTimeout(cfp.emergencyId(), cfp.requiredVehicleCategory()); // avvia il timer di sicurezza

        ProposalSubmission proposal = new ProposalSubmission(cfp.emergencyId(), cfp.requiredVehicleCategory(), zoneState.getZoneId(), offers);
        ctx.response().putHeader("content-type", "application/json").end(proposal.toJson().encode()); // invia un ProposalSubmission
    }

    // gestione del timeout: l'initiator è crashato/irraggiungibile
    private void scheduleReservationTimeout(EmergencyId emergencyId, VehicleCategory category) {
        vertx.setTimer(reservationTimeoutMs, timerId -> { // se il timer è trascorso
            Optional<List<VehicleId>> timedOut = zoneState.removePendingReservation(emergencyId, category); // rimuove i veicoli prenotati (se non gia rimossi da un ContractResolution precedente)
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
        Optional<List<VehicleId>> allReserved = zoneState.removePendingReservation(resolution.emergencyId(), resolution.category()); // recupera tutti i veicoli riservati per questa negoziazione (se esistono, ovvero se l'initiator comunica entro il timer)
        allReserved.ifPresentOrElse(reservedIds -> { // se sono presenti
                    for (VehicleId vehicleId : reservedIds) { // per ogni veicolo
                        VehicleStatus newStatus = resolution.confirmedVehicleIds().contains(vehicleId) ? VehicleStatus.EN_ROUTE : VehicleStatus.AVAILABLE; // assegna il veicoli se confermato, libera se non confermato
                        zoneState.getVehicle(vehicleId).setStatus(newStatus); // aggiorna lo stato del veicolo
                    }
                },
                () -> System.out.println("ContractResolution arrived too late for emergency " + resolution.emergencyId().value() + " (" + resolution.category() + "): reservation already timed out") // se è scaduto il timer
        );
        ctx.response().setStatusCode(204).end(); //indica "la richiesta è stata elaborata con successo, ma non c'è nessun contenuto da restituire nel body della risposta" (il contractor non risponde)
    }
}