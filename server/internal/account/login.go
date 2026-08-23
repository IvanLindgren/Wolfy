// Package account проксирует вход в общий аккаунт Читавука.
package account

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

var ErrUnavailable = errors.New("вход через Читавук сейчас недоступен")

type LoginResult struct {
	Status int
	Body   []byte
}

type Service struct {
	address string
	client  *http.Client
}

func New(address string, timeout time.Duration) *Service {
	return &Service{
		address: strings.TrimSpace(address),
		client: &http.Client{
			Timeout: timeout,
			// Пароль нельзя повторно отправлять по адресу из ответа upstream.
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}
}

func (s *Service) Configured() bool {
	u, err := url.Parse(s.address)
	return err == nil && u.Scheme == "https" && u.Host != ""
}

// Login передаёт JSON без преобразования: контрактом владеет Читавук, а
// Wolfy не должен терять добавленные им поля устройства.
func (s *Service) Login(ctx context.Context, body []byte) (LoginResult, error) {
	if !s.Configured() {
		return LoginResult{}, ErrUnavailable
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, s.address, bytes.NewReader(body))
	if err != nil {
		return LoginResult{}, fmt.Errorf("подготовка входа: %w", err)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "Wolfy/1.0")
	response, err := s.client.Do(request)
	if err != nil {
		return LoginResult{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer response.Body.Close()
	result, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return LoginResult{}, fmt.Errorf("чтение ответа входа: %w", err)
	}
	return LoginResult{Status: response.StatusCode, Body: result}, nil
}
