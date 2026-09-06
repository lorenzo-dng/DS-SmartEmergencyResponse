package domain.common;

public enum VectorClockComparison {
    BEFORE,     // First clock is before the other
    AFTER,      // First clock is after the other
    CONCURRENT, // Clocks are concurrent
    EQUAL       // Clocks are equal
}
