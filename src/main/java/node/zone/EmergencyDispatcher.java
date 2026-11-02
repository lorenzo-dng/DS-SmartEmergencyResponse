package node.zone;

import communication.contractnet.*;
import domain.emergency.Emergency;
import domain.emergency.EmergencyId;
import domain.emergency.Severity;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.zone.ZoneId;
import io.vertx.core.Future;
import node.persistence.BorrowedVehicleInfo;

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
        if (missing == 0) { // se non ce ne sono, allora termina
            return Future.succeededFuture();
        }
        // altrimenti, esegue una richiesta cross-zona ai vicini
        boolean isHigh = emergency.getEffectiveSeverity() == Severity.HIGH;
        CallForProposal cfp = new CallForProposal(emergency.getId(), zoneState.getZoneId(), emergency.getPosition(), category, missing, isHigh);
        return contractNetClient.callForProposals(cfp, neighbors).compose(submissions -> resolveAndConfirm(emergency, category, submissions)); // recupera tutte le risposte e le risolve
    }

    // fonde tutte le offerte ricevute dai contractor, sceglie i più vicini rispetto all'emergenza, e notifica l'esito a ogni contractor
    private Future<Void> resolveAndConfirm(Emergency emergency, VehicleCategory category, List<ProposalSubmission> submissions) {
        int stillMissing = zoneState.missingCount(emergency, category); // ricontrolla il numero di veicoli di una categoria ancora da assegnare (potrebbe essere cambiato nel frattempo)
        Set<VehicleId> selectedIds = new HashSet<>();
        if (stillMissing > 0 && !submissions.isEmpty()) { // se ci sono ancora veicoli di una categoria ancora da assegnare
            List<FlatOffer> flatOffers = getFlatOffers(submissions); // recupera le proposte di veicoli ricevute dai vicini
            flatOffers.sort(Comparator.comparingDouble(FlatOffer::distanceKm) // criterio primario: ordina i veicoli in base alla distanza dall'emergenza
                    .thenComparing(Comparator.comparingInt(FlatOffer::availableFleetCount).reversed()) // a parità: ordina i veicoli in base alla maggiore disponibilita di flotta del contractor per quella categoria
                    .thenComparing(Comparator.comparingDouble(FlatOffer::batteryLevel).reversed()) // a parità: ordina i veicoli in base alla maggiore batteria residua
                    .thenComparingInt(offer -> offer.vehicleId().value().hashCode()) // tie-breaker finale
            );
            List<FlatOffer> selected = flatOffers.stream().limit(stillMissing).toList(); // prende solo i primi veicoli nel numero mancante
            for (FlatOffer offer : selected) { // per ogni offerta di veicolo
                zoneState.addBorrowedVehicle(offer.vehicleId(), emergency.getId(), category, offer.contractorZoneId());
                emergency.addAssignedVehicle(offer.vehicleId()); // aggiunge il veicolo tra quelli assegnati all'emergenza
                selectedIds.add(offer.vehicleId()); // aggiunge l'id del veicolo nella lista dedicata
            }
            zoneState.saveEmergency(emergency); // salva i veicoli richiesti nel db prima di inviare la conferma ai contractor, così non vanno persi in caso di crash
        }
        zoneState.resolveVehicleBackup(emergency.getId(), category); // attiva o rilascia gli eventuali veicoli di backup
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
            sendFutures.add(sendResolution(emergency.getId(), category, submission.contractorZoneId(), confirmed)); // invia l'esito conclusivo ai contractor
        }
        return Future.join(sendFutures).mapEmpty(); // attende che tutte le risoluzioni siano state inviate, per poi restituire il risultato
    }

    // invia a un contractor l'esito conclusivo della negoziazione: se il contractor lo conferma salva l'ack nel db, altrimenti l'initiator ritira i veicoli di quel contractor
    private Future<Void> sendResolution(EmergencyId emergencyId, VehicleCategory category, ZoneId contractorZoneId, List<VehicleId> confirmed) {
        ContractResolution resolution = new ContractResolution(emergencyId, category, zoneState.getZoneId(), confirmed);
        return contractNetClient.sendResolution(resolution, contractorZoneId)
                .onSuccess(v -> zoneState.markConfirmationAcked(emergencyId, confirmed)) // il contractor ha attivato i veicoli: salva l'ack nel db
                .onFailure(err -> { // il contractor non ha confermato (irraggiungibile, timeout o prenotazione non più valida): l'initiator ritira i veicoli di quel contractor
                    int withdrawn = zoneState.withdrawUnconfirmedVehicles(emergencyId, contractorZoneId, category);
                    if (withdrawn > 0) { // se ci sono veicoli ritirati
                        System.out.println("Resolution not confirmed by zone " + contractorZoneId.value() + " for emergency " + emergencyId.value() + " (" + category + "): withdrawn " + withdrawn + " vehicle(s)");
                    }
                })
                .recover(err -> Future.succeededFuture()); // trasforma una future fallita (caso .onFailure) in una riuscita, cosi che Future.join possa essere eseguito con successo
    }

    // initiator: al riavvio dopo un crash rinvia la conferma ai contractor per i veicoli presi in prestito di cui non è stata ricevuta la conferma (se la prenotazione è ancora valida il contractor attiva i veicoli, se li ha già attivati conferma senza riattivarli, altrimenti risponde 410 e l'initiator li ritira)
    public void resendUnconfirmedResolutions() {
        for (Map.Entry<EmergencyId, Map<VehicleId, BorrowedVehicleInfo>> emergencyEntry : zoneState.getBorrowedVehicles().entrySet()) { // per ogni emergenza con veicoli presi in prestito
            Map<ContractorCategory, List<VehicleId>> unconfirmed = new HashMap<>(); // veicoli senza conferma (raggruppati per contractor e categoria)
            for (Map.Entry<VehicleId, BorrowedVehicleInfo> entry : emergencyEntry.getValue().entrySet()) { // per ogni veicolo preso in prestito
                BorrowedVehicleInfo info = entry.getValue();
                if (!info.confirmationAcked()) { // se il contractor non ha confermato di aver attivato il veicolo
                    unconfirmed.computeIfAbsent(new ContractorCategory(info.contractorZoneId(), info.category()), k -> new ArrayList<>()).add(entry.getKey()); // aggiunge il evicolo alla mappa
                }
            }
            for (Map.Entry<ContractorCategory, List<VehicleId>> group : unconfirmed.entrySet()) { // per ogni gruppo di veicoli senza conferma
                sendResolution(emergencyEntry.getKey(), group.getKey().category(), group.getKey().contractorZoneId(), group.getValue()); // invia al contractor l'esito conclusivo della negoziazione
            }
        }
    }
}