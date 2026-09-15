package communication.contractnet;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.zone.ZoneId;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

// initiator -> contractor: esito conclusivo della negoziazione per una categoria dell'emergenza.
public record ContractResolution(EmergencyId emergencyId, VehicleCategory category, ZoneId requesterZoneId, List<VehicleId> confirmedVehicleIds) {

    // serve all'initiator per inviare l'esito della negoziazione a un contractor
    public JsonObject toJson() {
        JsonArray confirmedJson = new JsonArray();
        for (VehicleId vehicleId : confirmedVehicleIds) { // aggiunge tutti i veicoli confermati all'array json
            confirmedJson.add(vehicleId.value());
        }
        return new JsonObject()
                .put("emergencyId", emergencyId.value())
                .put("category", category.name())
                .put("requesterZoneId", requesterZoneId.value())
                .put("confirmedVehicleIds", confirmedJson);
    }

    // serve al contractor per ricevere il messaggio json
    public static ContractResolution fromJson(JsonObject json) {
        List<VehicleId> confirmedVehicleIds = new ArrayList<>();
        JsonArray confirmedJson = json.getJsonArray("confirmedVehicleIds");
        for (int i = 0; i < confirmedJson.size(); i++) { // prende tutti i veicoli confermati dall'initiator
            confirmedVehicleIds.add(new VehicleId(confirmedJson.getString(i)));
        }
        return new ContractResolution(
                new EmergencyId(json.getString("emergencyId")),
                VehicleCategory.valueOf(json.getString("category")),
                new ZoneId(json.getString("requesterZoneId")),
                confirmedVehicleIds
        );
    }
}