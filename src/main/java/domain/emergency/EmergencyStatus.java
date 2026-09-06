package domain.emergency;

public enum EmergencyStatus {
    QUEUED,
    PARTIALLY_ASSIGNED, //sono stati assegnati solo alcuni veicoli
    ASSIGNED,
    CLOSED
}