package domain.triage.assault;

import domain.triage.common.Answer;

// contiene le selezioni dell'utente nella gui
public record AssaultAnswers(PeopleInvolved peopleInvolved, Answer injuries) {
}