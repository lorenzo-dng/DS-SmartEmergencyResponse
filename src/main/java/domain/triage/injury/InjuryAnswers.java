package domain.triage.injury;

import domain.triage.common.Answer;

// contiene le selezioni dell'utente nella gui
public record InjuryAnswers(BodyPartCategory bodyPart, Answer conscious, Answer movement, Answer bleeding) {
}