package communication.contractnet;

import domain.vehicle.VehicleId;
import io.vertx.core.json.JsonObject;

// rappresenta una singola offerta di veicolo dentro una ProposalSubmission
public record VehicleOffer(VehicleId vehicleId, double distanceKm, double batteryLevel) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("vehicleId", vehicleId.value())
                .put("distanceKm", distanceKm)
                .put("batteryLevel", batteryLevel);
    }

    public static VehicleOffer fromJson(JsonObject json) {
        return new VehicleOffer(new VehicleId(json.getString("vehicleId")), json.getDouble("distanceKm"), json.getDouble("batteryLevel"));
    }
}