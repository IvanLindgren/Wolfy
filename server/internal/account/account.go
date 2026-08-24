// Package account проксирует общий аккаунт Читавука: вход, регистрацию и
// повторную отправку письма с подтверждением.
//
// Именно проксирует, а не повторяет. Тела запросов и ответов идут насквозь,
// без разбора и без переупаковки, и это не лень: контрактом владеет Читавук.
// Он добавит поле устройства или новый код ошибки — и Wolfy передаст их, ничего
// не потеряв и не отстав на релиз. Своя копия структур означала бы, что каждое
// изменение там ломает вход здесь.
//
// Знать Wolfy обязан ровно одно: какой код ответа что значит, — и об этом
// говорит статус, а не тело.
package account

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

var ErrUnavailable = errors.New("вход через Читавук сейчас недоступен")

// ErrNotConfigured отличается от ErrUnavailable намеренно: первое — «эту
// возможность здесь не включали», второе — «включали, но сейчас не отвечает».
// Читателю в первом случае надо предложить другой путь, а не просить подождать.
var ErrNotConfigured = errors.New("этот способ входа не настроен")

// Result — ответ Читавука как есть.
type Result struct {
	Status int
	Body   []byte
}

// LoginResult оставлен как прежнее имя того же типа: переименование не должно
// ломать тех, кто уже на него ссылается.
type LoginResult = Result

// Service ходит в Читавук за тремя вещами. Адреса разные, поведение одно,
// поэтому и код один.
type Service struct {
	login           string
	register        string
	resend          string
	google          string
	yandexStart     string
	yandexComplete  string
	yandexWebReturn bool
	client          *http.Client
}

// WithSocial подключает социальные способы к тому же аккаунту Читавука.
// Они не создают отдельного пользователя Wolfy: Читавук сам связывает адрес
// провайдера с существующим аккаунтом либо заводит общий аккаунт при первом
// входе.
func (s *Service) WithSocial(google, yandexStart, yandexComplete string) *Service {
	s.google = strings.TrimSpace(google)
	s.yandexStart = strings.TrimSpace(yandexStart)
	s.yandexComplete = strings.TrimSpace(yandexComplete)
	return s
}

// WithYandexWebReturn подтверждает, что upstream Читавука умеет вернуть
// браузер на проверенный returnUrl Wolfy. Старый контракт всегда возвращает
// returnTarget=web на citavuk.ru; включать флаг до обновления Читавука нельзя.
func (s *Service) WithYandexWebReturn(enabled bool) *Service {
	s.yandexWebReturn = enabled
	return s
}

// New принимает три адреса. Пустой адрес — это выключенная возможность, а не
// ошибка настройки: сервис, поднятый только ради чтения, вправе не уметь
// заводить аккаунты.
func New(login, register, resend string, timeout time.Duration) *Service {
	return &Service{
		login:    strings.TrimSpace(login),
		register: strings.TrimSpace(register),
		resend:   strings.TrimSpace(resend),
		client: &http.Client{
			Timeout: timeout,
			// Пароль нельзя повторно отправлять по адресу из ответа upstream.
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}
}

// Configured сообщает, можно ли войти.
func (s *Service) Configured() bool { return usable(s.login) }

// CanRegister сообщает, можно ли завести аккаунт прямо из приложения.
func (s *Service) CanRegister() bool { return usable(s.register) }

// CanResend сообщает, можно ли переслать письмо с подтверждением.
func (s *Service) CanResend() bool { return usable(s.resend) }

func (s *Service) CanGoogle() bool { return usable(s.google) }

func (s *Service) CanYandex() bool {
	return usable(s.yandexStart) && usable(s.yandexComplete)
}

// CanYandexWeb отделён от CanYandex: desktop использует безопасный loopback и
// работал в старом контракте, а сайт требует явно доверенного HTTPS-возврата.
func (s *Service) CanYandexWeb() bool {
	return s.CanYandex() && s.yandexWebReturn
}

func (s *Service) Login(ctx context.Context, body []byte) (Result, error) {
	return s.forward(ctx, s.login, body)
}

// Register заводит аккаунт. Ответ 202 у Читавука означает «письмо ушло, ждём
// подтверждения почты» — сессии в нём нет, и клиент обязан это различать.
func (s *Service) Register(ctx context.Context, body []byte) (Result, error) {
	return s.forward(ctx, s.register, body)
}

// ResendVerification просит выслать письмо ещё раз — для тех, у кого первое
// не дошло. Читавук отвечает одинаково и на несуществующий адрес, и на уже
// подтверждённый, чтобы по нему нельзя было проверять чужие почты.
func (s *Service) ResendVerification(ctx context.Context, body []byte) (Result, error) {
	return s.forward(ctx, s.resend, body)
}

// Google меняет проверенный Google ID token на обычную общую сессию
// Читавука. Регистрация и вход намеренно являются одной операцией: решение,
// существует ли уже пользователь, принадлежит владельцу аккаунта.
func (s *Service) Google(ctx context.Context, body []byte) (Result, error) {
	return s.forward(ctx, s.google, body)
}

func (s *Service) YandexStart(ctx context.Context, body []byte) (Result, error) {
	var request struct {
		ReturnTarget string `json:"returnTarget"`
	}
	if json.Unmarshal(body, &request) == nil && request.ReturnTarget == "web" && !s.CanYandexWeb() {
		return Result{}, ErrNotConfigured
	}
	return s.forward(ctx, s.yandexStart, body)
}

func (s *Service) YandexComplete(ctx context.Context, body []byte) (Result, error) {
	return s.forward(ctx, s.yandexComplete, body)
}

func (s *Service) forward(ctx context.Context, address string, body []byte) (Result, error) {
	if !usable(address) {
		return Result{}, ErrNotConfigured
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, address, bytes.NewReader(body))
	if err != nil {
		return Result{}, fmt.Errorf("подготовка запроса: %w", err)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "Wolfy/1.0")
	response, err := s.client.Do(request)
	if err != nil {
		return Result{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer response.Body.Close()
	result, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return Result{}, fmt.Errorf("чтение ответа: %w", err)
	}
	return Result{Status: response.StatusCode, Body: result}, nil
}

func usable(address string) bool {
	u, err := url.Parse(address)
	return err == nil && u.Scheme == "https" && u.Host != ""
}
