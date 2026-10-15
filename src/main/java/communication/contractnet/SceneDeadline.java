package communication.contractnet;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleId;
import io.vertx.core.json.JsonObject;

public record SceneDeadline(EmergencyId emergencyId, VehicleId vehicleId, long deadlineMillis) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("emergencyId", emergencyId.value())
                .put("vehicleId", vehicleId.value())
                .put("deadlineMillis", deadlineMillis);
    }

    public static SceneDeadline fromJson(JsonObject json) {
        return new SceneDeadline(
                new EmergencyId(json.getString("emergencyId")),
                new VehicleId(json.getString("vehicleId")),
                json.getLong("deadlineMillis"));
    }
}