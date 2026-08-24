package com.wolfy.ui.account

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Appear
import com.wolfy.widgets.PrimaryButton
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.WolfySticker
import com.wolfy.widgets.pressable

enum class AuthMode { SignIn, SignUp, AwaitingEmail }

@Composable
fun SignInScreen(
    mode: AuthMode,
    busy: Boolean,
    error: String?,
    canRegister: Boolean,
    canGoogle: Boolean,
    canYandex: Boolean,
    awaitingEmail: String = "",
    onSubmit: (email: String, password: String, name: String) -> Unit,
    onResend: (email: String) -> Unit,
    onMode: (AuthMode) -> Unit,
    onGoogle: () -> Unit,
    onYandex: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val motion = WolfyTheme.motion
    var email by remember { mutableStateOf(awaitingEmail) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(awaitingEmail) {
        if (awaitingEmail.isNotBlank() && email.isBlank()) email = awaitingEmail
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .padding(PaddingValues(spacing.pageMargin)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Appear(0) { WolfySticker(Sticker.HappyWave, size = 96.dp) }
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    fadeIn(tween(motion.quick, easing = Curves.Paper)) togetherWith
                        fadeOut(tween(motion.quick, easing = Curves.Paper))
                },
                label = "auth mode",
            ) { current ->
                when (current) {
                    AuthMode.AwaitingEmail -> AwaitingEmail(
                        email = awaitingEmail.ifBlank { email },
                        busy = busy,
                        error = error,
                        onResend = onResend,
                        onSignIn = { onMode(AuthMode.SignIn) },
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                        Text(
                            if (current == AuthMode.SignIn) "Войти в Wolfy" else "Создать аккаунт",
                            style = WolfyTheme.typography.screenTitle,
                            color = colors.ink,
                        )
                        Text(
                            "Аккаунт общий с Читавуком и нужен для синхронизации устройств.",
                            style = WolfyTheme.typography.caption,
                            color = colors.inkMuted,
                        )
                        if (canGoogle || canYandex) {
                            SocialButtons(
                                google = canGoogle,
                                yandex = canYandex,
                                enabled = !busy,
                                onGoogle = onGoogle,
                                onYandex = onYandex,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                            ) {
                                androidx.compose.foundation.layout.Box(
                                    Modifier.weight(1f).height(1.dp).background(colors.rule),
                                )
                                Text("или по почте", style = WolfyTheme.typography.caption, color = colors.inkMuted)
                                androidx.compose.foundation.layout.Box(
                                    Modifier.weight(1f).height(1.dp).background(colors.rule),
                                )
                            }
                        }
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Почта") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Пароль") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AnimatedVisibility(
                            visible = current == AuthMode.SignUp,
                            enter = expandVertically(tween(motion.quick)) + fadeIn(tween(motion.quick)),
                            exit = shrinkVertically(tween(motion.quick)) + fadeOut(tween(motion.quick)),
                        ) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Имя") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        ErrorMessage(error)
                        PrimaryButton(
                            text = if (busy) {
                                if (current == AuthMode.SignIn) "Входим…" else "Создаём аккаунт…"
                            } else {
                                if (current == AuthMode.SignIn) "Войти" else "Создать аккаунт"
                            },
                            enabled = !busy && email.isNotBlank() && password.isNotBlank() &&
                                (current != AuthMode.SignUp || name.isNotBlank()),
                            onClick = { onSubmit(email, password, name) },
                        )
                        if (canRegister || current == AuthMode.SignUp) {
                            Text(
                                if (current == AuthMode.SignIn) "Создать аккаунт" else "Уже есть аккаунт",
                                style = WolfyTheme.typography.button,
                                color = colors.accent,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .pressable {
                                        onMode(if (current == AuthMode.SignIn) AuthMode.SignUp else AuthMode.SignIn)
                                    }
                                    .padding(spacing.small),
                            )
                        }
                    }
                }
            }
            Appear(6) {
                Text(
                    "Продолжить без аккаунта",
                    style = WolfyTheme.typography.button,
                    color = colors.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.huge))
                        .pressable(onClick = onSkip)
                        .padding(spacing.medium),
                )
            }
        }
    }
}

@Composable
private fun SocialButtons(
    google: Boolean,
    yandex: Boolean,
    enabled: Boolean,
    onGoogle: () -> Unit,
    onYandex: () -> Unit,
) {
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        if (google) SocialButton("G", "Продолжить с Google", enabled, onGoogle)
        if (yandex) SocialButton("Я", "Продолжить с Яндексом", enabled, onYandex)
        Text(
            "При первом входе аккаунт создастся автоматически.",
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun SocialButton(mark: String, text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.huge))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(32.dp)
                .background(colors.surface, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(mark, style = WolfyTheme.typography.button, color = colors.accent)
        }
        Text(text, style = WolfyTheme.typography.button, color = colors.ink)
    }
}

@Composable
private fun AwaitingEmail(
    email: String,
    busy: Boolean,
    error: String?,
    onResend: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        Text("Проверьте почту", style = WolfyTheme.typography.screenTitle, color = colors.ink)
        Text(
            "Мы отправили ссылку на $email. После подтверждения вернитесь и войдите.",
            style = WolfyTheme.typography.body,
            color = colors.ink,
        )
        ErrorMessage(error)
        PrimaryButton(
            text = if (busy) "Отправляем…" else "Выслать письмо ещё раз",
            enabled = !busy,
            onClick = { onResend(email) },
        )
        Text(
            "Я уже подтвердил почту",
            style = WolfyTheme.typography.button,
            color = colors.accent,
            modifier = Modifier.pressable(onClick = onSignIn).padding(spacing.small),
        )
    }
}

@Composable
private fun ErrorMessage(error: String?) {
    val motion = WolfyTheme.motion
    AnimatedVisibility(
        visible = !error.isNullOrBlank(),
        enter = expandVertically(tween(motion.quick)) + fadeIn(tween(motion.quick)),
        exit = shrinkVertically(tween(motion.quick)) + fadeOut(tween(motion.quick)),
    ) {
        Text(error.orEmpty(), style = WolfyTheme.typography.caption, color = WolfyTheme.colors.accent)
    }
}
