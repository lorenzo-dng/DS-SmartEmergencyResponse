package domain.triage.roadobstruction;

import domain.triage.common.Answer;

// contiene le selezioni dell'utente nella gui
public record RoadObstructionAnswers(ObstacleType obstacleType, Answer roadBlocked, Answer injuries) {
}