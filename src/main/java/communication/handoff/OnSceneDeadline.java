package communication.handoff;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleId;
import io.vertx.core.json.JsonObject;

public record OnSceneDeadline(EmergencyId emergencyId, VehicleId vehicleId, long deadlineMillis) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("emergencyId", emergencyId.value())
                .put("vehicleId", vehicleId.value())
                .put("deadlineMillis", deadlineMillis);
    }

    public static OnSceneDeadline fromJson(JsonObject json) {
        return new OnSceneDeadline(
                new EmergencyId(json.getString("emergencyId")),
                new VehicleId(json.getString("vehicleId")),
                json.getLong("deadlineMillis"));
    }
}