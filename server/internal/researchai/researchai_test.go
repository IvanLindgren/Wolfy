package researchai

import "testing"

func validFixture() Artifact {
	return Artifact{
		Version: AnalysisVersion, Title: "Книга", Subtitle: "Нити сюжета", Summary: "Краткая проверяемая сводка.", Notice: "ИИ может ошибаться.",
		Threads: []Thread{
			{ID: "t1", Title: "Герой", Summary: "Его путь.", Steps: []ThreadStep{{ID: "t1-1", Title: "Начало", Text: "Первый факт.", AnchorWords: 0}, {ID: "t1-2", Title: "Дальше", Text: "Второй факт.", AnchorWords: 100}}},
			{ID: "t2", Title: "Место", Summary: "Его роль.", Steps: []ThreadStep{{ID: "t2-1", Title: "Среда", Text: "Третий факт.", AnchorWords: 0}, {ID: "t2-2", Title: "Сдвиг", Text: "Четвёртый факт.", AnchorWords: 100}}},
		},
		Checkpoints: []Checkpoint{{ID: "c1", Title: "Веха", Text: "Первая веха.", AnchorWords: 0}, {ID: "c2", Title: "Веха 2", Text: "Вторая веха.", AnchorWords: 100}},
	}
}

func TestArtifactRejectsDuplicateOrSpoilerOrder(t *testing.T) {
	artifact := validFixture()
	if !validArtifact(&artifact) || !spoilersSafe(&artifact) {
		t.Fatal("fixture must be accepted")
	}
	artifact.Threads[1].Steps[0].ID = artifact.Threads[0].Steps[0].ID
	if validArtifact(&artifact) {
		t.Fatal("duplicate card id must be rejected")
	}
	artifact = validFixture()
	artifact.Threads[0].Steps[1].AnchorWords = -1
	if validArtifact(&artifact) {
		t.Fatal("negative anchor must be rejected")
	}
	artifact = validFixture()
	artifact.Threads[0].Steps[1].AnchorWords = 10
	artifact.Threads[0].Steps[0].AnchorWords = 20
	if spoilersSafe(&artifact) {
		t.Fatal("a later step cannot be revealed before an earlier step")
	}
}

func TestCleanJSONRemovesOnlyEnvelope(t *testing.T) {
	if got := string(cleanJSON("```json\n{\"ok\":true}\n```")); got != "{\"ok\":true}" {
		t.Fatalf("unexpected JSON: %q", got)
	}
}
