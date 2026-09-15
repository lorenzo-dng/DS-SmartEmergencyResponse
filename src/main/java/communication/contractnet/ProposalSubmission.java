package communication.contractnet;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;
import domain.zone.ZoneId;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

// contractor -> initiator: distanza del veicolo più vicino disponibile, con prenotazione temporanea
public record ProposalSubmission(EmergencyId emergencyId, VehicleCategory category, ZoneId contractorZoneId, List<VehicleOffer> vehicleOffers) {

    // serve ai contractor per inviare una risposta all' initiator
    public JsonObject toJson() {
        JsonArray offersJson = new JsonArray();
        for (VehicleOffer offer : vehicleOffers) { // aggiunge tutti i veicoli offerti dalla zona adiacente all'interno del json
            offersJson.add(offer.toJson());
        }
        return new JsonObject()
                .put("emergencyId", emergencyId.value())
                .put("category", category.name())
                .put("contractorZoneId", contractorZoneId.value())
                .put("vehicleOffers", offersJson);
    }

    // serve all' initiator per ricevere il messaggio json
    public static ProposalSubmission fromJson(JsonObject json) {
        List<VehicleOffer> vehicleOffers = new ArrayList<>();
        JsonArray offersJson = json.getJsonArray("vehicleOffers");
        for (int i = 0; i < offersJson.size(); i++) { // prende tutti i veicoli offerti dalla zona adiacente
            vehicleOffers.add(VehicleOffer.fromJson(offersJson.getJsonObject(i)));
        }
        return new ProposalSubmission(
                new EmergencyId(json.getString("emergencyId")),
                VehicleCategory.valueOf(json.getString("category")),
                new ZoneId(json.getString("contractorZoneId")), vehicleOffers);
    }
}