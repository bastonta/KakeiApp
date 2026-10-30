package com.kakei.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kakei.app.R
import com.kakei.app.core.i18n.AppLanguage
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.repository.AppSettingsState
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.components.*
import com.kakei.app.ui.theme.KakeiTheme
import com.kakei.app.ui.theme.ThemeMode
import com.kakei.app.ui.theme.kakeiCard

/**
 * Welcome screen: start offline (encrypted local vault) or sign in / register with Kakei Cloud,
 * followed by the 2FA and email-confirmation steps when the server asks for them.
 */
@Composable
fun AuthScreen(
    settings: AppSettingsState,
    repository: KakeiRepository
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    var showCloudForm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .background(
                Brush.radialGradient(
                    colors = listOf(c.mint.copy(alpha = if (c.isDark) 0.14f else 0.18f), Color.Transparent),
                    radius = 1400f
                )
            )
            .systemBarsPadding()
            // Shrink the scroll viewport above the keyboard so focused fields stay visible
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top language & theme switchers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.surf)
                    .clickable {
                        val next = if (settings.language == AppLanguage.RU) AppLanguage.EN else AppLanguage.RU
                        repository.setLanguage(next)
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Language, contentDescription = s.languageLabel, tint = c.muted, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(settings.language.code.uppercase(), style = MaterialTheme.typography.labelLarge, color = c.text)
            }
            CircleIconButton(
                icon = when (settings.themeMode) {
                    ThemeMode.DARK -> Icons.Outlined.DarkMode
                    ThemeMode.LIGHT -> Icons.Outlined.LightMode
                    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                },
                contentDescription = s.themeLabel,
                bordered = false,
                contentColor = c.muted,
                onClick = {
                    val nextTheme = when (settings.themeMode) {
                        ThemeMode.DARK -> ThemeMode.LIGHT
                        ThemeMode.LIGHT -> ThemeMode.SYSTEM
                        ThemeMode.SYSTEM -> ThemeMode.DARK
                    }
                    repository.setThemeMode(nextTheme)
                }
            )
        }

        Spacer(modifier = Modifier.height(if (showCloudForm) 16.dp else 48.dp))

        // Brand header: app icon, name and tagline
        Box(
            modifier = Modifier
                .size(if (showCloudForm) 88.dp else 116.dp)
                .shadow(28.dp, RoundedCornerShape(30.dp), ambientColor = c.mint, spotColor = c.mint)
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFF3DD68C)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.45f)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = s.authWelcomeTitle,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
            color = c.text,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = s.authWelcomeSubtitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Normal,
            color = c.muted,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Conditional 2FA or Email Confirm step
        if (settings.pending2faUserId != null) {
            var twoFaCode by remember { mutableStateOf("") }
            CodeStepCard(
                title = s.auth2faTitle,
                label = s.auth2faCodeLabel,
                value = twoFaCode,
                onValueChange = { twoFaCode = it },
                onConfirm = { repository.verify2fa(twoFaCode) }
            )
            return@Column
        }

        if (settings.pendingEmailConfirmUserId != null) {
            var emailConfirmCode by remember { mutableStateOf("") }
            CodeStepCard(
                title = s.authEmailConfirmTitle,
                label = s.authEmailConfirmCode,
                value = emailConfirmCode,
                onValueChange = { emailConfirmCode = it },
                onConfirm = { repository.confirmRegistrationEmail(emailConfirmCode) }
            )
            return@Column
        }

        if (showCloudForm) {
            CloudSignInCard(settings = settings, repository = repository)
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { showCloudForm = false }) {
                Text(s.pick("Назад", "Back"), color = c.muted)
            }
            return@Column
        }

        // Feature highlights
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            FeatureRow(
                icon = Icons.AutoMirrored.Outlined.Chat,
                color = c.cyan,
                title = s.pick("Операции из SMS и push", "Records from SMS & push"),
                subtitle = s.pick("Kaspi, Halyk, Freedom и другие банки", "Kaspi, Halyk, Freedom and other banks")
            )
            FeatureRow(
                icon = Icons.Outlined.BarChart,
                color = c.mint,
                title = s.pick("Прогноз и бюджеты", "Forecast & budgets"),
                subtitle = s.pick("Сколько можно тратить в день — без таблиц", "How much you can spend per day — no spreadsheets")
            )
            FeatureRow(
                icon = Icons.Outlined.GppGood,
                color = c.violet,
                title = s.pick("Шифрование на устройстве", "On-device encryption"),
                subtitle = s.pick("SQLCipher и биометрия, облако по желанию", "SQLCipher and biometrics, cloud is optional")
            )
        }

        Spacer(modifier = Modifier.height(36.dp))

        KakeiButton(
            text = s.authContinueOffline,
            icon = Icons.Outlined.Lock,
            onClick = { repository.enterOfflineAutonomousMode() },
            modifier = Modifier.fillMaxWidth(),
            height = 58.dp
        )
        Spacer(modifier = Modifier.height(12.dp))
        KakeiButton(
            text = s.pick("Войти в Kakei Cloud", "Sign in to Kakei Cloud"),
            icon = Icons.Outlined.Cloud,
            kind = KakeiButtonKind.OUTLINE,
            onClick = { showCloudForm = true },
            modifier = Modifier.fillMaxWidth(),
            height = 58.dp
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = s.pick(
                "Данные остаются на телефоне, пока вы не включите синхронизацию",
                "Your data stays on the phone until you turn on sync"
            ),
            style = MaterialTheme.typography.bodySmall,
            color = c.faint,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, color: Color, title: String, subtitle: String) {
    val c = KakeiTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon = icon, color = color, size = 48.dp, iconSize = 24.dp, cornerRadius = 14.dp)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal, color = c.muted)
        }
    }
}

@Composable
private fun CodeStepCard(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit
) {
    val s = LocalAppStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = KakeiTheme.colors.text)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        KakeiButton(text = s.confirmCategory, onClick = onConfirm, modifier = Modifier.fillMaxWidth())
    }
}

/** Kakei Cloud server login / registration form. */
@Composable
private fun CloudSignInCard(
    settings: AppSettingsState,
    repository: KakeiRepository
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    var isLoginTab by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("demo@kakei.app") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("Аблай") }
    var selectedCurrency by remember { mutableStateOf(settings.profile.defaultCurrency) }
    var serverUrl by remember { mutableStateOf(settings.serverBaseUrl) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        KakeiSegmented(
            options = listOf(s.authLoginTab, s.authRegisterTab),
            selectedIndex = if (isLoginTab) 0 else 1,
            onSelect = { isLoginTab = it == 0 }
        )

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text(s.authServerUrlLabel) },
            trailingIcon = {
                IconButton(onClick = { repository.setServerBaseUrl(serverUrl) }) {
                    Icon(
                        imageVector = Icons.Outlined.CloudSync,
                        contentDescription = s.pick("Проверить сервер", "Test server"),
                        tint = c.mint
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (!isLoginTab) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(s.authNameLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Base Currency selector
            Text(
                text = s.authDefaultCurrencyLabel,
                style = MaterialTheme.typography.labelLarge,
                color = c.muted
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CurrencyFormatter.supportedCurrencies.forEach { curr ->
                    KakeiChip(
                        text = "$curr ${CurrencyFormatter.symbolFor(curr)}",
                        selected = selectedCurrency == curr,
                        onClick = { selectedCurrency = curr }
                    )
                }
            }
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(s.authEmailLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(s.authPasswordLabel) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (!settings.statusMessage.isNullOrBlank()) {
            Text(
                text = settings.statusMessage,
                color = c.mint,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Button(
            onClick = {
                repository.setServerBaseUrl(serverUrl)
                if (isLoginTab) {
                    repository.signIn(email, password)
                } else {
                    repository.registerAccount(name, email, password, selectedCurrency)
                }
            },
            enabled = !settings.syncInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (settings.syncInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = c.mintInk
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (isLoginTab) s.authSignInButton else s.authSignUpButton,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
