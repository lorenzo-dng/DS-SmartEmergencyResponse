package node.zone;

import domain.vehicle.VehicleCategory;
import domain.zone.ZoneId;

// utilizzato dall'initiator: rappresenta un veicolo preso in prestito da un contractor
public record BorrowedVehicleInfo(VehicleCategory category, ZoneId contractorZoneId) {}