package domain.common;

//for static position
public record Position(double latitude, double longitude) {

    public Position {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Invalid latitude");
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Invalid longitude");
        }
    }
}