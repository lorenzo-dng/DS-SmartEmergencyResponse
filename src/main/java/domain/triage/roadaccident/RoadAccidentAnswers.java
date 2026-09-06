package domain.triage.roadaccident;

import domain.triage.common.Answer;

// contiene le selezioni dell'utente nella gui
public record RoadAccidentAnswers(VehiclesInvolved numVehicles, Answer injuries, Answer trapped) {
}