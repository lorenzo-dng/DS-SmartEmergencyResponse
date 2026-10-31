package node.zone;

import domain.vehicle.VehicleId;
import domain.zone.ZoneId;

// utilizzato dall'initiator: rappresenta una singola offerta di veicolo proposta dal contractor
public record FlatOffer(ZoneId contractorZoneId, VehicleId vehicleId, double distanceKm, double batteryLevel, int availableFleetCount) {
}