package communication.contractnet;

import domain.vehicle.VehicleId;
import io.vertx.core.json.JsonObject;

// rappresenta una singola offerta di veicolo dentro una ProposalSubmission
public record VehicleOffer(VehicleId vehicleId, double distanceKm) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("vehicleId", vehicleId.value())
                .put("distanceKm", distanceKm);
    }

    public static VehicleOffer fromJson(JsonObject json) {
        return new VehicleOffer(new VehicleId(json.getString("vehicleId")), json.getDouble("distanceKm"));
    }
}