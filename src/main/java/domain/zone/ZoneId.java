package domain.zone;

public record ZoneId(String value) {
    public ZoneId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ZoneId cannot be null or blank");
        }
    }
}
