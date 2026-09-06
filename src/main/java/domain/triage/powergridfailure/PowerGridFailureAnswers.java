package domain.triage.powergridfailure;

import domain.triage.common.Answer;

// contiene le selezioni dell'utente nella gui
public record PowerGridFailureAnswers(PowerGridExtent extent, Answer exposedWires) {
}