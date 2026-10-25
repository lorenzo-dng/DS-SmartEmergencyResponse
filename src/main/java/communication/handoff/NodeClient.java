package communication.handoff;

import communication.NeighborHealth;
import domain.zone.ZoneId;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import java.util.function.Function;
import java.util.function.IntConsumer;

// notifiche tra contractor e initiator durante l'handoff (arrivo, scadenza, completamento): ogni messaggio viene ritentato a intervalli finché non arriva una risposta definitiva
public class NodeClient {

    private final Vertx vertx;
    private final WebClient webClient; // contiene il client http
    private final NeighborHealth neighborHealth;
    private final Function<ZoneId, String> addressResolver; // riferimento al metodo addressFor in NodeConfig (zoneId -> stringa)
    private final int timeoutMs; // timeout che stabilisce quanto tempo aspettare una risposta
    private final int retryIntervalMs; // intervallo tra un tentativo e il successivo

    public NodeClient(Vertx vertx, Function<ZoneId, String> addressResolver, NeighborHealth neighborHealth, int timeoutMs, int retryIntervalMs) {
        this.vertx = vertx;
        this.webClient = WebClient.create(vertx, new WebClientOptions().setConnectTimeout(timeoutMs));
        this.addressResolver = addressResolver;
        this.neighborHealth = neighborHealth;
        this.timeoutMs = timeoutMs;
        this.retryIntervalMs = retryIntervalMs;
    }

    // contractor -> initiator: notifica l'arrivo di un veicolo prestato sull'emergenza (onRejected: l'initiator non accetta l'arrivo, quindi il veicolo non serve)
    public void notifyArrival(ZoneId requesterZoneId, ArrivedOnScene message, Runnable onRejected) {
        sendUntilAnswer(requesterZoneId, "/contract-net/arrived-on-scene", message.toJson(), status -> {
            if (!isAck(status)) { // se la risposta è negativa (ad esempio 409)
                onRejected.run(); // viene eseguito il codice passato come argomento
            }
        });
    }

    // invia il messaggio e lo ritenta a intervalli finché non arriva una risposta definitiva: errore di rete, timeout e codici 5xx vengono ritentati, qualunque altro codice (2xx e 4xx) no
    private void sendUntilAnswer(ZoneId zoneId, String path, JsonObject body, IntConsumer onAnswer) {
        String address = addressResolver.apply(zoneId);
        webClient.postAbs("http://" + address + path).timeout(timeoutMs).sendJsonObject(body)
                .onSuccess(response -> {
                    neighborHealth.markReachable(zoneId); // contrassegna il vicino come raggiungibile
                    if (response.statusCode() >= 500) { // errore del server: riprova dopo il timer
                        scheduleRetry(zoneId, path, body, onAnswer);
                    } else {
                        onAnswer.accept(response.statusCode());
                    }
                })
                .onFailure(err -> {
                    neighborHealth.markUnreachable(zoneId); // contrassegna il vicino come non raggiungibile
                    scheduleRetry(zoneId, path, body, onAnswer); // riprova dopo il timer
                });
    }

    // timer per un nuovo tentativo di invio del messaggio
    private void scheduleRetry(ZoneId zoneId, String path, JsonObject body, IntConsumer onAnswer) {
        vertx.setTimer(retryIntervalMs, id -> sendUntilAnswer(zoneId, path, body, onAnswer));
    }

    // vero se il codice indica che il messaggio è stato ricevuto ed elaborato (2xx)
    private static boolean isAck(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    // initiator -> contractor: comunica la scadenza dell'intervento di un veicolo prestato
    public void notifyDeadline(ZoneId contractorZoneId, OnSceneDeadline message, Runnable onAck) {
        sendUntilAnswer(contractorZoneId, "/contract-net/on-scene-deadline", message.toJson(), status -> ackOrLog(status, "on-scene-deadline", onAck));
    }

    // contractor -> initiator: notifica il completamento dell'intervento di un veicolo prestato
    public void notifyCompletion(ZoneId requesterZoneId, InterventionCompleted message, Runnable onAck) {
        sendUntilAnswer(requesterZoneId, "/contract-net/intervention-completed", message.toJson(), status -> ackOrLog(status, "intervention-completed", onAck));
    }

    // esegue la callback solo se la risposta è un ack (2xx), altrimenti scrive un log (la risposta è definitiva, quindi non viene più ritentato)
    private void ackOrLog(int status, String route, Runnable onAck) {
        if (isAck(status)) { // se la risposta è positiva
            onAck.run(); // viene eseguito il codice passato come argomento
        } else { // altrimenti
            System.out.println("Unexpected status " + status + " for " + route + ": message not acknowledged");
        }
    }
}