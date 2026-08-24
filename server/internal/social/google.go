// Package social проводит браузерный OAuth, не отдавая секреты провайдера
// установленному приложению.
package social

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/wolfy/server/internal/account"
)

const (
	googleAuthorizeURL = "https://accounts.google.com/o/oauth2/v2/auth"
	googleTokenURL     = "https://oauth2.googleapis.com/token"
	flowLifetime       = 5 * time.Minute
)

var (
	ErrNotConfigured = errors.New("вход через Google не настроен")
	ErrInvalidReturn = errors.New("небезопасный адрес возврата")
	ErrInvalidState  = errors.New("запрос входа устарел или повреждён")
)

type Google struct {
	clientID     string
	clientSecret string
	callbackURL  string
	stateKey     [32]byte
	stateReady   bool
	accounts     *account.Service
	client       *http.Client
	webOrigin    string
}

type StartRequest struct {
	ReturnURL    string          `json:"returnUrl"`
	ReturnTarget string          `json:"returnTarget"`
	Device       json.RawMessage `json:"device"`
}

type callbackState struct {
	ReturnURL    string          `json:"returnUrl"`
	ReturnTarget string          `json:"returnTarget"`
	Device       json.RawMessage `json:"device"`
	Verifier     string          `json:"verifier"`
	ExpiresAt    int64           `json:"expiresAt"`
}

type tokenResponse struct {
	IDToken string `json:"id_token"`
	Error   string `json:"error"`
}

func NewGoogle(
	accounts *account.Service,
	clientID, clientSecret, callbackURL, stateSecret string,
	timeout time.Duration,
) *Google {
	g := &Google{
		accounts: accounts,
		clientID: strings.TrimSpace(clientID), clientSecret: strings.TrimSpace(clientSecret),
		callbackURL: strings.TrimSpace(callbackURL),
		client:      &http.Client{Timeout: timeout},
	}
	if len(strings.TrimSpace(stateSecret)) >= 32 {
		g.stateKey = sha256.Sum256([]byte(stateSecret))
		g.stateReady = true
	}
	return g
}

// WithWebOrigin разрешает обычный браузерный возврат только на один явно
// настроенный origin. Без настройки веб-адрес не принимается: это не повод
// превращать OAuth state в открытый редирект.
func (g *Google) WithWebOrigin(origin string) *Google {
	g.webOrigin = cleanOrigin(origin)
	return g
}

func (g *Google) Configured() bool {
	return g != nil && g.accounts != nil && g.accounts.CanGoogle() &&
		g.clientID != "" && g.clientSecret != "" && validCallback(g.callbackURL) && g.stateReady
}

// Start создаёт OAuth-запрос с PKCE. Весь изменяемый контекст зашифрован в
// state: серверу не нужна временная таблица с токенами, а подменить loopback
// адрес или устройство по дороге нельзя.
func (g *Google) Start(request StartRequest) (string, error) {
	if !g.Configured() {
		return "", ErrNotConfigured
	}
	if !g.validReturn(request.ReturnURL, request.ReturnTarget) || !validDevice(request.Device) {
		return "", ErrInvalidReturn
	}
	verifier, err := randomURL(48)
	if err != nil {
		return "", err
	}
	state, err := g.seal(callbackState{
		ReturnURL: request.ReturnURL, ReturnTarget: request.ReturnTarget,
		Device: request.Device, Verifier: verifier, ExpiresAt: time.Now().Add(flowLifetime).Unix(),
	})
	if err != nil {
		return "", err
	}
	challenge := sha256.Sum256([]byte(verifier))
	query := url.Values{
		"client_id":             {g.clientID},
		"redirect_uri":          {g.callbackURL},
		"response_type":         {"code"},
		"scope":                 {"openid email profile"},
		"state":                 {state},
		"code_challenge":        {base64.RawURLEncoding.EncodeToString(challenge[:])},
		"code_challenge_method": {"S256"},
		"prompt":                {"select_account"},
	}
	return googleAuthorizeURL + "?" + query.Encode(), nil
}

// Complete меняет код Google на ID token, а ID token — на обычную сессию
// Читавука. Поэтому первый вход автоматически является регистрацией, а
// повторный открывает того же пользователя.
func (g *Google) Complete(ctx context.Context, code, sealed string) (string, account.Result, error) {
	if !g.Configured() {
		return "", account.Result{}, ErrNotConfigured
	}
	state, err := g.open(sealed)
	if err != nil {
		return "", account.Result{}, err
	}
	if strings.TrimSpace(code) == "" {
		return state.ReturnURL, account.Result{}, errors.New("Google не вернул код входа")
	}
	form := url.Values{
		"client_id":     {g.clientID},
		"client_secret": {g.clientSecret},
		"code":          {code},
		"redirect_uri":  {g.callbackURL},
		"grant_type":    {"authorization_code"},
		"code_verifier": {state.Verifier},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, googleTokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return state.ReturnURL, account.Result{}, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := g.client.Do(req)
	if err != nil {
		return state.ReturnURL, account.Result{}, fmt.Errorf("Google не отвечает: %w", err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return state.ReturnURL, account.Result{}, err
	}
	var token tokenResponse
	if response.StatusCode != http.StatusOK || json.Unmarshal(body, &token) != nil || token.IDToken == "" {
		return state.ReturnURL, account.Result{}, errors.New("Google не подтвердил вход")
	}
	upstreamBody, err := json.Marshal(struct {
		IDToken string          `json:"idToken"`
		Device  json.RawMessage `json:"device"`
	}{token.IDToken, state.Device})
	if err != nil {
		return state.ReturnURL, account.Result{}, err
	}
	result, err := g.accounts.Google(ctx, upstreamBody)
	return state.ReturnURL, result, err
}

// ReturnURL достаёт проверенный адрес даже при отказе пользователя у Google,
// чтобы приложение получило осмысленную отмену, а не ждало таймаут.
func (g *Google) ReturnURL(sealed string) (string, error) {
	state, err := g.open(sealed)
	if err != nil {
		return "", err
	}
	return state.ReturnURL, nil
}

// IsWebReturn сообщает API, нужно ли завершить поток обычным redirect вместо
// HTML-формы для loopback-слушателя desktop-приложения.
func (g *Google) IsWebReturn(value string) bool {
	parsed, err := url.Parse(value)
	return err == nil && g.webOrigin != "" && cleanOrigin(parsed.Scheme+"://"+parsed.Host) == g.webOrigin
}

func (g *Google) seal(state callbackState) (string, error) {
	plain, err := json.Marshal(state)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(g.stateKey[:])
	if err != nil {
		return "", err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return "", err
	}
	sealed := aead.Seal(nonce, nonce, plain, []byte("wolfy-google-v1"))
	return base64.RawURLEncoding.EncodeToString(sealed), nil
}

func (g *Google) open(value string) (callbackState, error) {
	var state callbackState
	sealed, err := base64.RawURLEncoding.DecodeString(value)
	// Отклоняем неканоническую base64url-запись. У последнего символа могут
	// быть неиспользуемые биты: другая строка иногда декодируется в те же байты
	// и формально проходит AEAD, хотя OAuth state в URL был изменён.
	if err != nil || base64.RawURLEncoding.EncodeToString(sealed) != value {
		return state, ErrInvalidState
	}
	block, err := aes.NewCipher(g.stateKey[:])
	if err != nil {
		return state, ErrInvalidState
	}
	aead, err := cipher.NewGCM(block)
	if err != nil || len(sealed) < aead.NonceSize() {
		return state, ErrInvalidState
	}
	nonce, ciphertext := sealed[:aead.NonceSize()], sealed[aead.NonceSize():]
	plain, err := aead.Open(nil, nonce, ciphertext, []byte("wolfy-google-v1"))
	if err != nil || json.Unmarshal(plain, &state) != nil ||
		state.ExpiresAt < time.Now().Unix() || !g.validReturn(state.ReturnURL, state.ReturnTarget) || !validDevice(state.Device) {
		return callbackState{}, ErrInvalidState
	}
	return state, nil
}

func (g *Google) validReturn(value, target string) bool {
	if target != "web" {
		return validLoopback(value)
	}
	parsed, err := url.Parse(value)
	if err != nil || parsed.User != nil || parsed.Fragment != "" || g.webOrigin == "" {
		return false
	}
	return cleanOrigin(parsed.Scheme+"://"+parsed.Host) == g.webOrigin && parsed.Path == "/auth/return"
}

func cleanOrigin(value string) string {
	parsed, err := url.Parse(strings.TrimSpace(value))
	if err != nil || parsed.User != nil || parsed.Host == "" || (parsed.Scheme != "https" && parsed.Scheme != "http") {
		return ""
	}
	return strings.TrimSuffix(parsed.Scheme+"://"+parsed.Host, "/")
}

func validLoopback(value string) bool {
	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme != "http" || parsed.User != nil || parsed.Fragment != "" || parsed.Port() == "" {
		return false
	}
	host := strings.Trim(parsed.Hostname(), "[]")
	return host == "127.0.0.1" || host == "::1"
}

func validCallback(value string) bool {
	parsed, err := url.Parse(value)
	if err != nil || parsed.User != nil || parsed.Host == "" {
		return false
	}
	return parsed.Scheme == "https" || (parsed.Scheme == "http" && parsed.Hostname() == "127.0.0.1")
}

func validDevice(raw json.RawMessage) bool {
	if len(raw) == 0 || len(raw) > 2048 || !json.Valid(raw) {
		return false
	}
	var device struct {
		ID string `json:"id"`
	}
	return json.Unmarshal(raw, &device) == nil && strings.TrimSpace(device.ID) != ""
}

func randomURL(size int) (string, error) {
	value := make([]byte, size)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}
