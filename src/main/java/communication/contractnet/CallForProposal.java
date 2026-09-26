package communication.contractnet;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;
import domain.zone.ZoneId;
import domain.common.Position;
import io.vertx.core.json.JsonObject;

// initiator -> tutti i contractor (vicini): richiesta di un veicolo per un emergenza
public record CallForProposal(EmergencyId emergencyId, ZoneId requesterZoneId, Position emergencyPosition,
                              VehicleCategory requiredVehicleCategory, int requiredCount, boolean strictDistanceLimit) {

    // serve all' initiator per inviare una richiesta json ai contractor
    public JsonObject toJson() {
        return new JsonObject()
                .put("emergencyId", emergencyId.value())
                .put("requesterZoneId", requesterZoneId.value())
                .put("latitude", emergencyPosition.latitude())
                .put("longitude", emergencyPosition.longitude())
                .put("requiredVehicleCategory", requiredVehicleCategory.name())
                .put("requiredCount", requiredCount)
                .put("strictDistanceLimit", strictDistanceLimit);
    }

    // serve ai contractor per ricevere il messaggio json
    public static CallForProposal fromJson(JsonObject json) {
        return new CallForProposal(new EmergencyId(json.getString("emergencyId")),
                new ZoneId(json.getString("requesterZoneId")),
                new Position(json.getDouble("latitude"), json.getDouble("longitude")),
                VehicleCategory.valueOf(json.getString("requiredVehicleCategory")),
                json.getInteger("requiredCount"),
                json.getBoolean("strictDistanceLimit"));

    }
}