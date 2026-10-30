package com.kakei.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.ui.components.KakeiButton
import com.kakei.app.ui.theme.KakeiTheme

/**
 * Full-screen gate shown while the biometric app lock is engaged. The system prompt is
 * launched by the host activity; this screen only offers a retry and shows the last error.
 */
@Composable
fun AppLockScreen(
    errorMessage: String?,
    onUnlock: () -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(c.violet.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = c.violet, modifier = Modifier.size(44.dp))
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            s.pick("Kakei заблокирован", "Kakei is locked"),
            style = MaterialTheme.typography.headlineMedium,
            color = c.text,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            s.pick("Подтвердите личность, чтобы продолжить", "Confirm it's you to continue"),
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
            textAlign = TextAlign.Center
        )
        if (!errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = c.coral,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        KakeiButton(
            text = s.pick("Разблокировать", "Unlock"),
            onClick = onUnlock,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
