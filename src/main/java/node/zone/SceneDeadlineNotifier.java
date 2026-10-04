package node.zone;

import communication.contractnet.SceneDeadline;
import domain.emergency.EmergencyId;
import domain.vehicle.VehicleId;
import domain.zone.ZoneId;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import java.util.function.Function;

// comunica al contractor il completamento dell'intervento per un veicolo prestato (quindi tutti i veicoli della categoria hanno completato l'intervento), ritentando finché non arriva ack
public class SceneDeadlineNotifier {

    private final Vertx vertx;
    private final WebClient webClient;
    private final Function<ZoneId, String> addressResolver;
    private final int retryIntervalMs;

    public SceneDeadlineNotifier(Vertx vertx, Function<ZoneId, String> addressResolver, int connectTimeoutMs, int retryIntervalMs) {
        this.vertx = vertx;
        this.webClient = WebClient.create(vertx, new WebClientOptions().setConnectTimeout(connectTimeoutMs));
        this.addressResolver = addressResolver;
        this.retryIntervalMs = retryIntervalMs;
    }

    public void notifyDeadline(EmergencyId emergencyId, ZoneId contractorZoneId, VehicleId vehicleId, long deadlineMillis) {
        attemptSend(emergencyId, contractorZoneId, vehicleId, deadlineMillis);
    }

    private void attemptSend(EmergencyId emergencyId, ZoneId contractorZoneId, VehicleId vehicleId, long deadlineMillis) {
        SceneDeadline message = new SceneDeadline(emergencyId, vehicleId, deadlineMillis);
        String address = addressResolver.apply(contractorZoneId);
        webClient.postAbs("http://" + address + "/contract-net/scene-deadline").sendJsonObject(message.toJson())
                .onSuccess(response -> {}) // ack arrivato
                .onFailure(err -> vertx.setTimer(retryIntervalMs, id -> attemptSend(emergencyId, contractorZoneId, vehicleId, deadlineMillis))); // riprova dopo il timer
    }
}