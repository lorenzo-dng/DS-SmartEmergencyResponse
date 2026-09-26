package communication.contractnet;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import io.vertx.core.json.JsonObject;

// contractor -> initiator: un veicolo prestato ha terminato il proprio intervento sulla scena
public record InterventionCompleted(EmergencyId emergencyId, VehicleId vehicleId, VehicleCategory category) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("emergencyId", emergencyId.value())
                .put("vehicleId", vehicleId.value())
                .put("category", category.name());
    }

    public static InterventionCompleted fromJson(JsonObject json) {
        return new InterventionCompleted(new EmergencyId(json.getString("emergencyId")), new VehicleId(json.getString("vehicleId")), VehicleCategory.valueOf(json.getString("category")));
    }
}