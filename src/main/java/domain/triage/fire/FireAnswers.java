package domain.triage.fire;

import domain.triage.common.Answer;

// contiene le selezioni dell'utente nella gui
public record FireAnswers(FireType fireType, FireSize size, Answer dangerToPeopleOrBuildings) {
}