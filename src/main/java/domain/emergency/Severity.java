package domain.emergency;

public enum Severity {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    UNCLASSIFIABLE(0); //utile per generare eccezioni

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }
}