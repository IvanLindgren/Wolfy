package api

import (
	"strings"
	"testing"
	"time"
)

func TestCostAwareLimiter(t *testing.T) {
	now := time.Now()
	limiter := newRateLimiter(5, 1, time.Minute)
	limiter.now = func() time.Time { return now }

	// Маленький текст стоит 1
	if !limiter.allowN("1.2.3.4", 1) {
		t.Fatal("маленький запрос не прошёл")
	}
	// Большой текст стоит 5, должен упереться в лимит
	// После первого запроса осталось 4, большой на 5 не поместится
	if limiter.allowN("1.2.3.4", 5) {
		t.Fatal("большой запрос прошёл при нехватке токенов")
	}
	// После пополнения большой должен пройти
	now = now.Add(2 * time.Second) // +2 токена => 6? burst 5 => 5
	// now left = 4 +2 =5? Actually after first, left=4, then refill 2 => min(5,6)=5, cost5 => left0
	if !limiter.allowN("1.2.3.4", 5) {
		t.Fatal("большой запрос не прошёл после пополнения")
	}
}

func TestTranslateCost(t *testing.T) {
	small := "hello"
	large := strings.Repeat("a", 2000)
	costSmall := translateCost(small, "")
	costLarge := translateCost(large, "")
	if costLarge <= costSmall {
		t.Fatalf("большой текст стоит не больше маленького: %v vs %v", costLarge, costSmall)
	}
	if costSmall != 1 {
		t.Fatalf("маленький должен стоить 1, получено %v", costSmall)
	}
	if costLarge < 4 {
		t.Fatalf("большой текст должен стоить несколько токенов, получено %v", costLarge)
	}
	// Context добавляет стоимость
	costWithContext := translateCost(small, large)
	if costWithContext <= costLarge {
		t.Fatalf("контекст должен увеличивать стоимость")
	}
}

func TestAllowNRespectsBurst(t *testing.T) {
	now := time.Now()
	limiter := newRateLimiter(3, 1, time.Minute)
	limiter.now = func() time.Time { return now }
	// Стоимость больше burst должна отклоняться даже на пустом бакете
	if limiter.allowN("1.2.3.4", 4) {
		t.Fatal("стоимость больше burst прошла")
	}
	// Стоимость ровно burst должна пройти один раз
	if !limiter.allowN("5.6.7.8", 3) {
		t.Fatal("стоимость ровно burst не прошла")
	}
	// Повторный такой же должен отклониться
	if limiter.allowN("5.6.7.8", 3) {
		t.Fatal("второй запрос с полной стоимостью прошёл без пополнения")
	}
}
