package node.zone;

import communication.contractnet.ArrivedOnScene;
import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.zone.ZoneId;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import java.util.function.Function;

// contractor -> initiator: notifica all'initiator l'arrivo di un veicolo prestato sull'emergenza, ritentando finché non arriva ack
public class ArrivalNotifier {

    private final Vertx vertx;
    private final WebClient webClient;
    private final Function<ZoneId, String> addressResolver;
    private final int retryIntervalMs;

    public ArrivalNotifier(Vertx vertx, Function<ZoneId, String> addressResolver, int connectTimeoutMs, int retryIntervalMs) {
        this.vertx = vertx;
        this.webClient = WebClient.create(vertx, new WebClientOptions().setConnectTimeout(connectTimeoutMs));
        this.addressResolver = addressResolver;
        this.retryIntervalMs = retryIntervalMs;
    }

    public void notifyArrival(EmergencyId emergencyId, ZoneId requesterZoneId, VehicleId vehicleId, VehicleCategory category) {
        attemptSend(emergencyId, requesterZoneId, vehicleId, category);
    }

    private void attemptSend(EmergencyId emergencyId, ZoneId requesterZoneId, VehicleId vehicleId, VehicleCategory category) {
        ArrivedOnScene message = new ArrivedOnScene(emergencyId, vehicleId, category);
        String address = addressResolver.apply(requesterZoneId);
        webClient.postAbs("http://" + address + "/contract-net/arrived-on-scene").sendJsonObject(message.toJson())
                .onSuccess(response -> {}) // ack ricevuto
                .onFailure(err -> vertx.setTimer(retryIntervalMs, id -> attemptSend(emergencyId, requesterZoneId, vehicleId, category))); // riprova dopo il timer
    }
}