// Команда wolfy-server — API приложения Wolfy.
//
// Сервис делает то, чего клиент не может сам: опознаёт пользователя по
// аккаунту Читавука, синхронизирует библиотеку между устройствами и ходит во
// внешние API, пряча ключи. Чтение книг и разбор слов к серверу не обращаются
// вовсе — они считаются на устройстве ядром на Rust.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/wolfy/server/internal/account"
	"github.com/wolfy/server/internal/api"
	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/bookfiles"
	"github.com/wolfy/server/internal/config"
	"github.com/wolfy/server/internal/dictionary"
	"github.com/wolfy/server/internal/discovery"
	"github.com/wolfy/server/internal/library"
	"github.com/wolfy/server/internal/ocr"
	"github.com/wolfy/server/internal/social"
	"github.com/wolfy/server/internal/store"
	"github.com/wolfy/server/internal/translate"
	"github.com/wolfy/server/internal/updates"
)

func main() {
	if err := run(); err != nil {
		slog.Error("сервис остановлен", "error", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	level := slog.LevelInfo
	if cfg.Development() {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: level}))

	// Контекст живёт до Ctrl+C или SIGTERM от оркестратора.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := store.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer db.Close()
	log.Info("база подключена, миграции применены")

	translator := translate.New(db.Pool, cfg.DeepLKey, cfg.DeepLURL, cfg.RequestTimeout)
	if !translator.Configured() {
		// Не ошибка: без ключа приложение остаётся рабочим, просто без
		// контекстного перевода. Знать об этом на старте полезно.
		log.Warn("DEEPL_API_KEY не задан — контекстный перевод отключён")
	}
	accountService := account.New(
		cfg.CitavukLoginURL,
		cfg.CitavukRegisterURL,
		cfg.CitavukResendURL,
		cfg.RequestTimeout,
	).WithSocial(
		cfg.CitavukGoogleURL,
		cfg.CitavukYandexStartURL,
		cfg.CitavukYandexCompleteURL,
	).WithYandexWebReturn(cfg.CitavukYandexWebReturn)
	googleAuth := social.NewGoogle(
		accountService,
		cfg.GoogleClientID,
		cfg.GoogleClientSecret,
		cfg.GoogleCallbackURL,
		cfg.OAuthStateSecret,
		cfg.RequestTimeout,
	).WithWebOrigin(cfg.WebOrigin)
	if !googleAuth.Configured() {
		log.Info("Google code+PKCE OAuth не настроен — браузер использует GIS")
	}
	if cfg.GoogleWebClientID == "" || !accountService.CanGoogle() {
		log.Warn("Google GIS не настроен — кнопка Google будет скрыта")
	}
	if !accountService.CanYandexWeb() {
		log.Warn("trusted web-return Яндекса не включён — кнопка Яндекса будет скрыта")
	}
	discoveryService := discovery.New(
		db,
		discovery.NewGutenbergSource(cfg.GutendexURL, cfg.RequestTimeout),
		cfg.RequestTimeout,
	)
	dictionaryService, err := dictionary.Open(cfg.DictionaryPath)
	if err != nil {
		log.Warn("словарь не развёрнут — офлайн-загрузка и fallback отключены", "error", err)
		dictionaryService = dictionary.Unavailable()
	} else {
		log.Info("словарь загружен", "path", cfg.DictionaryPath)
	}

	server := &http.Server{
		Addr: cfg.Addr,
		Handler: api.NewServer(
			db,
			auth.NewVerifier(db.Pool),
			translator,
			library.New(db),
			ocr.New(cfg.OCRKey, cfg.OCRURL, cfg.OCRModel, cfg.RequestTimeout),
			accountService,
			googleAuth,
			discoveryService,
			dictionaryService,
			updates.New(cfg.ReleasesPath),
			bookfiles.New(db, cfg.BookFilesPath),
			log,
		).WithWebOrigin(cfg.WebOrigin).
			WithGoogleWebClientID(cfg.GoogleWebClientID).
			Handler(),
		// Таймауты обязательны: без них одно зависшее соединение держит
		// горутину и файловый дескриптор до перезапуска сервиса.
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}

	errc := make(chan error, 1)
	go func() {
		log.Info("сервис слушает", "addr", cfg.Addr, "env", cfg.Env)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errc <- err
		}
	}()

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
		log.Info("получен сигнал, завершаемся")
	}

	// Даём доработать начатым запросам: обрыв на середине синхронизации
	// оставил бы клиента с неизвестным состоянием.
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return server.Shutdown(shutdownCtx)
}
