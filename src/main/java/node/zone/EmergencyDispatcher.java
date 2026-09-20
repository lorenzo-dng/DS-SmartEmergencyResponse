package node.zone;

import communication.contractnet.*;
import domain.emergency.Emergency;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.zone.ZoneId;
import io.vertx.core.Future;

import java.util.*;
import java.util.stream.Collectors;

// orchestratore della logica di comunicazione Contract Net
public class EmergencyDispatcher {

    private final ZoneState zoneState;
    private final ContractNetNodeClient contractNetClient;
    private final Set<ZoneId> neighbors;

    public EmergencyDispatcher(ZoneState zoneState, ContractNetNodeClient contractNetClient, Set<ZoneId> neighbors) {
        this.zoneState = zoneState;
        this.contractNetClient = contractNetClient;
        this.neighbors = neighbors;
    }

    // gestisce una nuova emergenza, prima localmente (fasi 1 e 2), poi cross-zona (fase 3)
    public Future<Void> dispatch(Emergency emergency) {
        List<VehicleCategory> stillUncovered = zoneState.handleNewEmergency(emergency); // recupera le categorie di veicoli ancora da assegnare (dopo aver eseguito fase 1 e 2)
        List<Future<Void>> categoryFutures = new ArrayList<>();
        for (VehicleCategory category : stillUncovered) { // per ogni categoria di veicolo ancora scoperta
            categoryFutures.add(requestProposalsForCategory(emergency, category));
        }
        return Future.join(categoryFutures).mapEmpty(); // attende tutte e restituisce Future<Void>
    }

    // gestisce l'invio della richiesta di una categoria di veicolo alle zone vicine
    private Future<Void> requestProposalsForCategory(Emergency emergency, VehicleCategory category) {
        int missing = zoneState.missingCount(emergency, category); // recupera il numero di veicoli di una categoria ancora da assegnare
        if (missing <= 0) { // se non ce ne sono, allora termina
            return Future.succeededFuture();
        }
        // altrimenti, esegue una richiesta cross-zona ai vicini
        CallForProposal cfp = new CallForProposal(emergency.getId(), zoneState.getZoneId(), emergency.getPosition(), category, missing);
        return contractNetClient.callForProposals(cfp, neighbors).compose(submissions -> resolveAndConfirm(emergency, category, submissions)); // recupera tutte le risposte e le risolve
    }

    // fonde tutte le offerte ricevute dai contractor, sceglie i più vicini rispetto all'emergenza, e notifica l'esito a ogni contractor
    private Future<Void> resolveAndConfirm(Emergency emergency, VehicleCategory category, List<ProposalSubmission> submissions) {
        int stillMissing = zoneState.missingCount(emergency, category); // ricontrolla il numero di veicoli di una categoria ancora da assegnare (potrebbe essere cambiato nel frattempo)
        Set<VehicleId> selectedIds = new HashSet<>();
        if (stillMissing > 0 && !submissions.isEmpty()) { // se ci sono ancora veicoli du una categoria ancora da assegnare
            List<FlatOffer> flatOffers = getFlatOffers(submissions); // recupera le proposte di veicoli ricevute dai vicini
            flatOffers.sort(Comparator.comparingDouble(FlatOffer::distanceKm) // criterio primario: ordina i veicoli in bsae alla distanza dall'emergenza
                    .thenComparing(Comparator.comparingInt(FlatOffer::availableFleetCount).reversed()) // a parità: ordina i veicoli in base alla maggiore disponibilita di flotta del contractor per quella categoria
                    .thenComparing(Comparator.comparingDouble(FlatOffer::batteryLevel).reversed()) // a parità: ordina i veicoli in base alla maggiore batteria residua
                    .thenComparingInt(offer -> offer.vehicleId().value().hashCode()) // tie-breaker finale
            );
            List<FlatOffer> selected = flatOffers.stream().limit(stillMissing).collect(Collectors.toList()); // prende solo i primi veicoli nel numero mancante
            for (FlatOffer offer : selected) { // per ogni offerta di veicolo
                zoneState.addBorrowedVehicle(offer.vehicleId(), category);
                emergency.addAssignedVehicle(offer.vehicleId()); // aggiunge il veicolo tra quelli assegnati all'emergenza
                selectedIds.add(offer.vehicleId()); // aggiunge l'id del veicolo nella lista dedicata
            }
        }
        return sendResolutionsToAll(emergency, category, submissions, selectedIds);
    }

    // recupera le proposte di veicoli dalle risposte inviate dalle zone vicine
    private static List<FlatOffer> getFlatOffers(List<ProposalSubmission> submissions) {
        List<FlatOffer> flatOffers = new ArrayList<>();
        for (ProposalSubmission submission : submissions) { // per ogni risposta
            for (VehicleOffer offer : submission.vehicleOffers()) { // per ogni veicolo indicato nella risposta
                flatOffers.add(new FlatOffer(submission.contractorZoneId(), offer.vehicleId(), offer.distanceKm(), offer.batteryLevel(), submission.availableFleetCount())); // aggiunge il veicolo richiesto salvando le info: contractor, id veicolo, distanza dall'emergenza, batteria, flotta disponibile del contractor
            }
        }
        return flatOffers;
    }

    // gestisce l'invio dell'esito conclusivo della negoziazione
    private Future<Void> sendResolutionsToAll(Emergency emergency, VehicleCategory category, List<ProposalSubmission> submissions, Set<VehicleId> selectedIds) {
        List<Future<Void>> sendFutures = new ArrayList<>();
        for (ProposalSubmission submission : submissions) { // per ogni risposta ricevuta dai contractor
            List<VehicleId> confirmed = submission.vehicleOffers().stream()// accede alla lista dei veicoli offerti
                    .map(VehicleOffer::vehicleId) // estrae solo l'id del veicolo
                    .filter(selectedIds::contains) // filtra la lista di id mantenendo solo quelli presenti nella lista
                    .collect(Collectors.toList()); // mette tutto in una nuova lista

            // invia l'esito conclusivo ai contractor
            ContractResolution resolution = new ContractResolution(emergency.getId(), category, zoneState.getZoneId(), confirmed);
            sendFutures.add(contractNetClient.sendResolution(resolution, submission.contractorZoneId()));
        }
        return Future.join(sendFutures).mapEmpty(); // attende che tutte le risoluzioni siano state inviate, per poi restituire il risultato
    }
}