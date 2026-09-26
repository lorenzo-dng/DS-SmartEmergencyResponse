package node.zone;

import communication.contractnet.InterventionCompleted;
import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.zone.ZoneId;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.function.Function;

// notifica all'initiator il completamento dell'intervento di un veicolo prestato, ritentando finché non arriva ack
public class InterventionNotifier {

    private final Vertx vertx;
    private final WebClient webClient;
    private final Function<ZoneId, String> addressResolver;
    private final ZoneState zoneState;
    private final int retryIntervalMs;

    public InterventionNotifier(Vertx vertx, Function<ZoneId, String> addressResolver, ZoneState zoneState, int connectTimeoutMs, int retryIntervalMs) {
        this.vertx = vertx;
        this.webClient = WebClient.create(vertx, new WebClientOptions().setConnectTimeout(connectTimeoutMs));
        this.addressResolver = addressResolver;
        this.zoneState = zoneState;
        this.retryIntervalMs = retryIntervalMs;
    }

    // notifica il completamento dell'intervento
    public void notifyCompletion(EmergencyId emergencyId, ZoneId requesterZoneId, VehicleId vehicleId, VehicleCategory category) {
        attemptSend(emergencyId, requesterZoneId, vehicleId, category);
    }

    private void attemptSend(EmergencyId emergencyId, ZoneId requesterZoneId, VehicleId vehicleId, VehicleCategory category) {
        InterventionCompleted message = new InterventionCompleted(emergencyId, vehicleId, category);
        String address = addressResolver.apply(requesterZoneId);
        webClient.postAbs("http://" + address + "/contract-net/intervention-completed").sendJsonObject(message.toJson())
                .onSuccess(response -> zoneState.removeActiveLoan(vehicleId)) // ack ricevuto: prestito concluso
                .onFailure(err -> vertx.setTimer(retryIntervalMs, id -> attemptSend(emergencyId, requesterZoneId, vehicleId, category))); // riprova dopo trascorso il timer
    }
}