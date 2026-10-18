package com.kakei.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.AppLanguage
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.repository.AppSettingsState
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.theme.EmeraldBrand
import com.kakei.app.ui.theme.IndigoAccent
import com.kakei.app.ui.theme.ThemeMode
import com.kakei.app.ui.theme.emeraldText
import com.kakei.app.ui.theme.frostedGlassCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    settings: AppSettingsState,
    repository: KakeiRepository
) {
    val s = LocalAppStrings.current
    var isLoginTab by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("demo@kakei.app") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("Аблай") }
    var selectedCurrency by remember { mutableStateOf(settings.profile.defaultCurrency) }
    var serverUrl by remember { mutableStateOf(settings.serverBaseUrl) }
    var twoFaCode by remember { mutableStateOf("") }
    var emailConfirmCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Theme & Language Switcher Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = {
                    val nextTheme = when (settings.themeMode) {
                        ThemeMode.DARK -> ThemeMode.LIGHT
                        ThemeMode.LIGHT -> ThemeMode.SYSTEM
                        ThemeMode.SYSTEM -> ThemeMode.DARK
                    }
                    repository.setThemeMode(nextTheme)
                },
                label = {
                    Text(
                        text = when (settings.themeMode) {
                            ThemeMode.DARK -> s.themeDark
                            ThemeMode.LIGHT -> s.themeLight
                            ThemeMode.SYSTEM -> s.themeSystem
                        },
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (settings.themeMode == ThemeMode.LIGHT) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = null,
                        tint = EmeraldBrand,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
            AssistChip(
                onClick = {
                    val next = if (settings.language == AppLanguage.RU) AppLanguage.EN else AppLanguage.RU
                    repository.setLanguage(next)
                },
                label = {
                    Text(
                        text = settings.language.displayName,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = EmeraldBrand,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Brand Header
        Surface(
            color = EmeraldBrand.copy(alpha = 0.16f),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    tint = EmeraldBrand,
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = s.authWelcomeTitle,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = s.authWelcomeSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(22.dp))

        // Conditional 2FA or Email Confirm step
        if (settings.pending2faUserId != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = s.auth2faTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = twoFaCode,
                    onValueChange = { twoFaCode = it },
                    label = { Text(s.auth2faCodeLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { repository.verify2fa(twoFaCode) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s.confirmCategory)
                }
            }
            return@Column
        }

        if (settings.pendingEmailConfirmUserId != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = s.authEmailConfirmTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = emailConfirmCode,
                    onValueChange = { emailConfirmCode = it },
                    label = { Text(s.authEmailConfirmCode) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { repository.confirmRegistrationEmail(emailConfirmCode) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s.confirmCategory)
                }
            }
            return@Column
        }

        // Autonomous Offline Mode Primary Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .frostedGlassCard(accentColor = EmeraldBrand)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = EmeraldBrand,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (s.language == AppLanguage.RU)
                            "Офлайн-хранилище SQLCipher AES-256"
                        else
                            "SQLCipher AES-256 Offline Vault",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (s.language == AppLanguage.RU)
                            "Мгновенный старт без регистрации с локальным перехватом SMS/Push и отложенной синхронизацией"
                        else
                            "Instant start without login, local SMS/Push capture & deferred Kakei Server sync",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = { repository.enterOfflineAutonomousMode() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldBrand,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(s.authContinueOffline, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Kakei Cloud Server Login / Register Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .frostedGlassCard(accentColor = IndigoAccent)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SecondaryTabRow(
                selectedTabIndex = if (isLoginTab) 0 else 1,
                containerColor = Color.Transparent
            ) {
                Tab(
                    selected = isLoginTab,
                    onClick = { isLoginTab = true },
                    text = {
                        Text(
                            text = s.authLoginTab,
                            color = if (isLoginTab) emeraldText() else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isLoginTab) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = !isLoginTab,
                    onClick = { isLoginTab = false },
                    text = {
                        Text(
                            text = s.authRegisterTab,
                            color = if (!isLoginTab) emeraldText() else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (!isLoginTab) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text(s.authServerUrlLabel) },
                trailingIcon = {
                    IconButton(onClick = { repository.setServerBaseUrl(serverUrl) }) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = "Test server",
                            tint = EmeraldBrand
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
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CurrencyFormatter.supportedCurrencies.forEach { curr ->
                        FilterChip(
                            selected = selectedCurrency == curr,
                            onClick = { selectedCurrency = curr },
                            label = { Text("$curr (${CurrencyFormatter.symbolFor(curr)})") }
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
                    color = emeraldText(),
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldBrand,
                    contentColor = Color.White
                ),
                enabled = !settings.syncInProgress,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (settings.syncInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isLoginTab) s.authSignInButton else s.authSignUpButton,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
