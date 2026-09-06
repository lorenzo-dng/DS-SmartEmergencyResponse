package domain.emergency;

import domain.vehicle.VehicleCategory;

// dato che contiene le informazioni dei veicoli richiesti (categoria + quantità)
public record VehicleRequirement(VehicleCategory category, int quantity) {
    public VehicleRequirement {
        if (category == null) {
            throw new IllegalArgumentException("Category cannot be null");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

    }
}