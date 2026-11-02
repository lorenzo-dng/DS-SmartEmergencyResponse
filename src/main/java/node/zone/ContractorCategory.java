package node.zone;

import domain.vehicle.VehicleCategory;
import domain.zone.ZoneId;

// contractor e categoria dei veicoli presi in prestito
public record ContractorCategory(ZoneId contractorZoneId, VehicleCategory category) {
}
