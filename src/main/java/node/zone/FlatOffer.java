package node.zone;

import domain.vehicle.VehicleId;
import domain.zone.ZoneId;

// utilizzato dal contractor: rappresenta una singola offerta di veicolo proposta all'initiator
public record FlatOffer(ZoneId contractorZoneId, VehicleId vehicleId, double distanceKm, double batteryLevel, int availableFleetCount) {
}