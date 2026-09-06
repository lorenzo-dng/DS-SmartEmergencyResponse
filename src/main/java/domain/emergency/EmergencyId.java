package domain.emergency;

public record EmergencyId(String value) {
    public EmergencyId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EmergencyId cannot be null or blank");
        }
    }
}
