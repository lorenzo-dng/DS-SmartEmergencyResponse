package domain.vehicle;

public enum VehicleStatus {
    AVAILABLE,
    RESERVED, // prenotazione temporanea cross-zona, non ancora confermata
    EN_ROUTE, // assegnato, sta viaggiando verso l'emergenza
    ON_SCENE, // arrivato sulla scena, intervento in corso
}