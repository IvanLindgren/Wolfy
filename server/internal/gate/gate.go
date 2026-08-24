// Package gate — общие ограничители параллелизма для дорогих операций.
//
// Загрузка 64 MiB книг и вызовы vision-модели дешёвыми не бывают: каждый такой
// запрос держит десятки мегабайтов памяти и внешний вызов. Без общего
// ограничителя несколько одновременных запросов выбили бы процесс по OOM на
// маленьком инстансе (systemd MemoryMax 192 MiB). Gate держит не более N
// одновременных операций и уважает отмену контекста, чтобы не блокировать
// горутину бесконечно.
package gate

import "context"

// Gate — семафор с отменой по контексту.
type Gate struct {
	sem chan struct{}
}

// New создаёт gate на n одновременных операций.
func New(n int) *Gate {
	if n <= 0 {
		n = 1
	}
	return &Gate{sem: make(chan struct{}, n)}
}

// Acquire блокирует до освобождения слота или отмены контекста.
func (g *Gate) Acquire(ctx context.Context) error {
	select {
	case g.sem <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// TryAcquire пытается занять слот без блокировки. Используется для
// загрузок книг, где клиенту лучше сразу сказать «занято, попробуй позже».
func (g *Gate) TryAcquire() bool {
	select {
	case g.sem <- struct{}{}:
		return true
	default:
		return false
	}
}

// Release освобождает слот.
func (g *Gate) Release() {
	select {
	case <-g.sem:
	default:
	}
}

// Available — сколько слотов свободно (для тестов/метрик).
func (g *Gate) Available() int {
	return cap(g.sem) - len(g.sem)
}

// Defaults — общие gates для процесса. Используются как единый
// ограничитель памяти на дорогие пути, чтобы remotebook и discovery не
// занимали каждый по 64 MiB одновременно.
//
// Download — загрузка книг (64 MiB каждая) и каталога Standard Ebooks.
// OCR — вызовы vision-модели.
var (
	Download = New(2)
	OCR      = New(4)
)
