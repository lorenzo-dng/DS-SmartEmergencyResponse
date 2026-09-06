package domain.vehicle;

public record VehicleId(String value) {
    public VehicleId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("VehicleId cannot be null or blank");
        }
    }
}
