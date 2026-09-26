package domain.emergency;

public enum Severity {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    UNCLASSIFIABLE(0); //utile per generare eccezioni
    private static final double MILLIS_PER_MINUTE = 60_000.0;
    private static final double LOW_TO_MEDIUM_THRESHOLD_MIN = 10.0;
    private static final double MEDIUM_TO_HIGH_THRESHOLD_MIN = 10.0;
    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }

    // calcola la gravità effettiva dell'emergenza considerando il tempo di aging accumulato
    public Severity effectiveSeverity(long agingTimeMillis) {
        double agingMinutes = agingTimeMillis / MILLIS_PER_MINUTE;
        if (this == LOW) {
            if (agingMinutes >= LOW_TO_MEDIUM_THRESHOLD_MIN + MEDIUM_TO_HIGH_THRESHOLD_MIN) return HIGH;
            if (agingMinutes >= LOW_TO_MEDIUM_THRESHOLD_MIN) return MEDIUM;
            return LOW;
        }
        if (this == MEDIUM) {
            return agingMinutes >= MEDIUM_TO_HIGH_THRESHOLD_MIN ? HIGH : MEDIUM;
        }
        return this;
    }
}