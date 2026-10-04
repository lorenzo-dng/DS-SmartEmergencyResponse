package communication.contractnet;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import io.vertx.core.json.JsonObject;

// contractor -> initiator: un veicolo prestato è arrivato sulla scena, in attesa che il gruppo della categoria si completi
public record ArrivedOnScene(EmergencyId emergencyId, VehicleId vehicleId, VehicleCategory category) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("emergencyId", emergencyId.value())
                .put("vehicleId", vehicleId.value())
                .put("category", category.name());
    }

    public static ArrivedOnScene fromJson(JsonObject json) {
        return new ArrivedOnScene(
                new EmergencyId(json.getString("emergencyId")),
                new VehicleId(json.getString("vehicleId")),
                VehicleCategory.valueOf(json.getString("category")));
    }
}