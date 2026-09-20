package node.zone;

import domain.vehicle.VehicleId;
import domain.zone.ZoneId;

// rappresenta una singola offerta di veicolo, con il riferimento al contractor che l'ha proposta — usata da EmergencyDispatcher per fondere le offerte di più vicini in un'unica lista
public record FlatOffer(ZoneId contractorZoneId, VehicleId vehicleId, double distanceKm, double batteryLevel, int availableFleetCount) {
}