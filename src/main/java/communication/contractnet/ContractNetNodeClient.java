package communication.contractnet;

import communication.NeighborHealth;
import domain.zone.ZoneId;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// initiator che comunica con i contractor adiacenti
public class ContractNetNodeClient {

    private final WebClient webClient; // contiene il client http
    private final NeighborHealth neighborHealth;
    private final Function<ZoneId, String> addressResolver; // riferimento al metodo addressFor in NodeConfig (zoneId -> stringa)
    private final int timeoutMs; // timeout che stabilisce quanto tempo aspettare una risposta

    public ContractNetNodeClient(Vertx vertx, Function<ZoneId, String> addressResolver, NeighborHealth neighborHealth, int timeoutMs) {
        this.webClient = WebClient.create(vertx, new WebClientOptions().setConnectTimeout(timeoutMs)); // crea il client
        this.addressResolver = addressResolver;
        this.neighborHealth = neighborHealth;
        this.timeoutMs = timeoutMs;
    }

    // (orchestrazione) invia la CallForProposal a tutti i vicini (in parallelo), e restituisce solo le proposte arrivate entro il timeout
    public Future<List<ProposalSubmission>> callForProposals(CallForProposal cfp, Set<ZoneId> neighbors) {
        List<Future<Optional<ProposalSubmission>>> requests =
                neighbors.stream() // trasforma il set dei vicini in uno stream (per poter eseguire le operazioni successive)
                        .map(neighborId -> requestProposal(neighborId, cfp)) // per ogni vicino, chiama il metodo
                        .collect(Collectors.toList()); // recupera tutte le risposte ricevute e le mette nella lista requests
        return Future.join(requests) // attende che tutte le future contenute in request (e generate dai vicini) siano state completate
                .map(composite -> requests.stream() // trasforma la lista requests in uno (per poter eseguire le operazioni successive)
                        .filter(Future::succeeded) // filtra solo le risposte ricevute con successo
                        .map(Future::result) // da ogni future, estrai il valore contenuto
                        .filter(Optional::isPresent)// dai valori estratti, filtra solo per gli optional diversi da 204
                        .map(Optional::get) // da ogni optional, estrae il valore contenuto
                        .collect(Collectors.toList())); // le mette in una lista (che verra restituita con la future)
    }

    // invia una richiesta (CallForProposal) a una zona vicina e recupera la risposta (ProposalSubmission)
    private Future<Optional<ProposalSubmission>> requestProposal(ZoneId neighborId, CallForProposal cfp) {
        String address = addressResolver.apply(neighborId); // recupera l'indirizzo della zona vicina (tramite il suo id)
        return webClient.postAbs("http://" + address + "/contract-net/call-for-proposal").timeout(timeoutMs).sendJsonObject(cfp.toJson()) // invia una richiesta http POST alla zona vicina
                .compose(response -> { // se riceva la risposta
                    neighborHealth.markReachable(neighborId); // contrassegna il vicino come raggiungibile
                    if (response.statusCode() == 204) {
                        return Future.succeededFuture(Optional.<ProposalSubmission>empty()); //restituisce un optional empty, a indicare che la risposta è avvenuta con successo ma con codice 204
                    }
                    return Future.succeededFuture(Optional.of(ProposalSubmission.fromJson(response.bodyAsJsonObject()))); // altrimenti, restituisce un optional
                })
                .recover(err -> { // se non riceve la risposta (o presenta di errori)
                    neighborHealth.markUnreachable(neighborId); // contrassegna il vicino come non raggiungibile
                    return Future.failedFuture(err);
                });
    }

    // invia l'esito conclusivo della negoziazione a un contractor
    public Future<Void> sendResolution(ContractResolution resolution, ZoneId contractorId) {
        String address = addressResolver.apply(contractorId);
        return webClient.postAbs("http://" + address + "/contract-net/resolution").timeout(timeoutMs).sendJsonObject(resolution.toJson())
                .compose(aresponse -> {
                    neighborHealth.markReachable(contractorId);
                    return Future.<Void>succeededFuture();
                })
                .recover(err -> {
                    neighborHealth.markUnreachable(contractorId);
                    return Future.failedFuture(err);
                });
    }
}