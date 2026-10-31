package com.kakei.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kakei.app.ui.theme.KakeiTheme
import com.kakei.app.ui.theme.kakeiCard

// ============================================================================
// Category icons & colors
// ============================================================================

/** Maps a category / goal `iconKey` (lucide-style names from the backend) to a Material icon. */
fun iconForKey(iconKey: String?): ImageVector = when (iconKey?.lowercase()) {
    // Food & Dining
    "shopping-cart", "cart", "groceries" -> Icons.Outlined.ShoppingCart
    "shopping-basket" -> Icons.Outlined.ShoppingBasket
    "coffee", "cafe" -> Icons.Outlined.LocalCafe
    "utensils", "utensils-crossed", "restaurant" -> Icons.Outlined.Restaurant
    "bike" -> Icons.Outlined.DeliveryDining
    // Transportation & Car
    "car", "car-front", "transport", "bus" -> Icons.Outlined.DirectionsCar
    "car-taxi-front", "taxi" -> Icons.Outlined.LocalTaxi
    "train" -> Icons.Outlined.Train
    "fuel" -> Icons.Outlined.LocalGasStation
    "square-parking" -> Icons.Outlined.LocalParking
    "wrench" -> Icons.Outlined.CarRepair
    "plane", "travel" -> Icons.Outlined.Flight
    "luggage" -> Icons.Outlined.Luggage
    // Housing
    "home", "house", "rent" -> Icons.Outlined.Home
    "building" -> Icons.Outlined.Apartment
    "building-2" -> Icons.Outlined.Domain
    "zap", "bolt" -> Icons.Outlined.Bolt
    "sofa" -> Icons.Outlined.Weekend
    "spray-can" -> Icons.Outlined.CleaningServices
    "phone", "smartphone", "mobile" -> Icons.Outlined.PhoneAndroid
    // Shopping
    "shopping-bag" -> Icons.Outlined.ShoppingBag
    "shirt" -> Icons.Outlined.Checkroom
    "laptop" -> Icons.Outlined.Laptop
    "pencil-ruler" -> Icons.Outlined.Edit
    "box", "package" -> Icons.Outlined.Inventory2
    // Health & Wellness
    "heart", "health", "heart-pulse" -> Icons.Outlined.FavoriteBorder
    "pill" -> Icons.Outlined.Medication
    "dumbbell" -> Icons.Outlined.FitnessCenter
    "sparkles" -> Icons.Outlined.Spa
    // Entertainment & Digital Services
    "film" -> Icons.Outlined.Movie
    "tv", "subscription", "subscriptions" -> Icons.Outlined.Tv
    "ticket" -> Icons.Outlined.ConfirmationNumber
    "monitor-smartphone" -> Icons.Outlined.Devices
    "bot" -> Icons.Outlined.SmartToy
    "gamepad-2" -> Icons.Outlined.SportsEsports
    "app-window" -> Icons.Outlined.Apps
    "book-open" -> Icons.AutoMirrored.Outlined.MenuBook
    // Income & Finance
    "wallet" -> Icons.Outlined.AccountBalanceWallet
    "piggy-bank", "piggy", "savings" -> Icons.Outlined.Savings
    "briefcase", "salary", "work" -> Icons.Outlined.Work
    "briefcase-business" -> Icons.Outlined.BusinessCenter
    "rotate-ccw" -> Icons.Outlined.Replay
    "percent" -> Icons.Outlined.Percent
    "badge-percent" -> Icons.Outlined.Discount
    "handshake" -> Icons.Outlined.Handshake
    "tag" -> Icons.Outlined.Sell
    "landmark" -> Icons.Outlined.AccountBalance
    "receipt" -> Icons.AutoMirrored.Outlined.ReceiptLong
    "credit-card" -> Icons.Outlined.CreditCard
    "triangle-alert" -> Icons.Outlined.Warning
    "calculator" -> Icons.Outlined.Calculate
    "banknote" -> Icons.Outlined.Payments
    "swap", "transfer", "repeat", "arrow-left-right" -> Icons.Outlined.SwapHoriz
    "shield", "shield-check" -> Icons.Outlined.Shield
    // Family, Education, Gifts, Work
    "users" -> Icons.Outlined.People
    "heart-handshake" -> Icons.Outlined.Diversity3
    "baby" -> Icons.Outlined.ChildCare
    "paw-print" -> Icons.Outlined.Pets
    "graduation-cap" -> Icons.Outlined.School
    "presentation" -> Icons.Outlined.CoPresent
    "gift", "gifts" -> Icons.Outlined.CardGiftcard
    "hand-heart" -> Icons.Outlined.VolunteerActivism
    "monitor" -> Icons.Outlined.Monitor
    else -> Icons.Outlined.Sell
}

/** Parses `#RRGGBB` / `#AARRGGBB`; falls back to [fallback] for anything else. */
fun parseHexColor(hex: String?, fallback: Color): Color {
    val raw = hex?.trim()?.removePrefix("#") ?: return fallback
    val value = raw.toLongOrNull(16) ?: return fallback
    return when (raw.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(value)
        else -> fallback
    }
}

/** Stable accent for people / goals without their own color, picked from the categorical tokens. */
@Composable
fun accentForName(name: String): Color {
    val c = KakeiTheme.colors
    val palette = listOf(c.mint, c.pink, c.blue, c.violet, c.amber, c.cyan, c.coral)
    return palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
}

// ============================================================================
// Tiles, avatars, badges
// ============================================================================

/** Rounded square with a tinted fill and a colored icon (category tile, settings row icon). */
@Composable
fun IconTile(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    cornerRadius: Dp = 12.dp,
    selected: Boolean = false
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(color.copy(alpha = if (KakeiTheme.colors.isDark) 0.16f else 0.14f))
            .then(if (selected) Modifier.border(2.dp, color, shape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
    }
}

/** Rounded square with a solid fill and a centered glyph (account letter, "Σ"). */
@Composable
fun LetterTile(
    letter: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    contentColor: Color = Color.White
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(letter, color = contentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun Avatar(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentColor: Color = Color.White
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = contentColor,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp
        )
    }
}

/** Small tinted label, e.g. "Проверить", "Превышен", "68%". */
@Composable
fun KakeiBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    Text(
        text = text,
        color = color,
        style = if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(if (large) 8.dp else 6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = if (large) 8.dp else 6.dp, vertical = if (large) 4.dp else 2.dp)
    )
}

// ============================================================================
// Buttons
// ============================================================================

enum class KakeiButtonKind { PRIMARY, TONAL, OUTLINE }

/**
 * Design-system button. PRIMARY is a solid [color] fill, TONAL a tinted fill,
 * OUTLINE a hairline border on a transparent background.
 */
@Composable
fun KakeiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: KakeiButtonKind = KakeiButtonKind.PRIMARY,
    icon: ImageVector? = null,
    color: Color = KakeiTheme.colors.mint,
    enabled: Boolean = true,
    height: Dp = 52.dp
) {
    val c = KakeiTheme.colors
    val container = when (kind) {
        KakeiButtonKind.PRIMARY -> color
        KakeiButtonKind.TONAL -> color.copy(alpha = 0.16f)
        KakeiButtonKind.OUTLINE -> Color.Transparent
    }
    val content = when (kind) {
        KakeiButtonKind.PRIMARY -> if (color == c.mint) c.mintInk else Color.White
        KakeiButtonKind.TONAL -> color
        KakeiButtonKind.OUTLINE -> c.text
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = height),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = c.surf2,
            disabledContentColor = c.faint
        ),
        border = if (kind == KakeiButtonKind.OUTLINE) BorderStroke(1.dp, c.line) else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 40dp round button with a hairline border; [badge] draws the coral notification dot. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    size: Dp = 40.dp,
    containerColor: Color = KakeiTheme.colors.surf,
    contentColor: Color = KakeiTheme.colors.text,
    bordered: Boolean = true
) {
    val c = KakeiTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .then(if (bordered) Modifier.border(1.dp, c.line, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = contentColor, modifier = Modifier.size(20.dp))
        if (badge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = size * 0.22f, end = size * 0.24f)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(c.coral)
            )
        }
    }
}

/** Full-width dashed call-to-action ("+ Новый бюджет"). */
@Composable
fun DashedAddButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = KakeiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(20.dp))
            .drawBehind {
                drawRoundRect(
                    color = c.mint.copy(alpha = 0.55f),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
                    )
                )
            }
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = c.mint)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = c.mint, style = MaterialTheme.typography.titleMedium)
    }
}

// ============================================================================
// Chips & segmented control
// ============================================================================

/** Pill chip. Active chips get a tinted fill and a [color] border. */
@Composable
fun KakeiChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    color: Color = KakeiTheme.colors.mint,
    iconTint: Color? = null
) {
    val c = KakeiTheme.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) color.copy(alpha = 0.16f) else c.surf2)
            .border(1.dp, if (selected) color.copy(alpha = 0.7f) else c.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) color else (iconTint ?: c.muted),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) color else c.text,
            maxLines = 1
        )
    }
}

/** Three-way (or N-way) segmented control used for sub-tabs and record types. */
@Composable
fun KakeiSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = KakeiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 16.dp, color = c.surf)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            val bg by animateColorAsState(if (active) c.surf2 else Color.Transparent, label = "segment")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .then(if (active) Modifier.border(1.dp, c.line, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) c.text else c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
}

// ============================================================================
// Progress
// ============================================================================

@Composable
fun KakeiProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    trackColor: Color = KakeiTheme.colors.surf2
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = r)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) {
            drawRoundRect(
                color = color,
                size = Size((size.width * p).coerceAtLeast(size.height), size.height),
                cornerRadius = r
            )
        }
    }
}

/** Circular progress ring with centered content. */
@Composable
fun KakeiRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    strokeWidth: Dp = 6.dp,
    trackColor: Color = KakeiTheme.colors.surf2,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke)
            )
            val p = progress.coerceIn(0f, 1f)
            if (p > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * p,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
        content()
    }
}

// ============================================================================
// Headers & rows
// ============================================================================

/** Large screen title (Title · 26-30 Bold) with optional trailing actions. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displayMedium,
            color = KakeiTheme.colors.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
    }
}

/** Section title with an optional tappable action on the right ("Счета · + Добавить"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    actionColor: Color = KakeiTheme.colors.muted,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = KakeiTheme.colors.text)
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = actionColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

/** Uppercase caption above a settings group ("АВТОЗАХВАТ"). */
@Composable
fun GroupCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.sp,
        color = KakeiTheme.colors.faint,
        modifier = modifier.padding(start = 4.dp, top = 6.dp)
    )
}

/** Card that stacks rows separated by hairlines (settings groups, transaction lists). */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .kakeiCard(),
        content = content
    )
}

@Composable
fun RowDivider(startIndent: Dp = 0.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent),
        thickness = 1.dp,
        color = KakeiTheme.colors.line
    )
}

/**
 * Settings row: icon tile, title + optional subtitle, then either a value with a chevron,
 * a switch ([checked] non-null), or custom [trailing] content.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    titleColor: Color = KakeiTheme.colors.text,
    showChevron: Boolean = checked == null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = KakeiTheme.colors
    val click = onClick ?: if (checked != null && onCheckedChange != null) {
        { onCheckedChange(!checked) }
    } else null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon = icon, color = iconColor, size = 36.dp, iconSize = 18.dp, cornerRadius = 10.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
        when {
            trailing != null -> trailing()
            checked != null -> KakeiSwitch(checked = checked, onCheckedChange = onCheckedChange)
            else -> {
                if (value != null) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.muted,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (showChevron) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = c.faint,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun KakeiSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    val c = KakeiTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = c.mint,
            checkedBorderColor = c.mint,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = c.surf2,
            uncheckedBorderColor = c.line
        ),
        thumbContent = null
    )
}

/**
 * Ledger row: category tile, payee + "Category · Account" subtitle (with an optional
 * review badge), amount colored by type and a time / status line.
 */
@Composable
fun TransactionRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    amount: String,
    amountColor: Color,
    trailingCaption: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeColor: Color = KakeiTheme.colors.amber,
    extra: String? = null
) {
    val c = KakeiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon = icon, color = iconColor, size = 44.dp, iconSize = 22.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (badge != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    KakeiBadge(text = badge, color = badgeColor)
                }
            }
            if (!extra.isNullOrBlank()) {
                Text(
                    text = extra,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.faint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(amount, style = MaterialTheme.typography.titleMedium, color = amountColor, maxLines = 1)
            Text(trailingCaption, style = MaterialTheme.typography.bodySmall, color = c.faint, maxLines = 1)
        }
    }
}

// ============================================================================
// Bottom navigation
// ============================================================================

data class BottomNavItem(val label: String, val icon: ImageVector)

/** Four destinations split around a raised mint "+" button (Главная · Аналитика · + · Долги · Цели). */
@Composable
fun KakeiBottomBar(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    addContentDescription: String,
    modifier: Modifier = Modifier
) {
    val c = KakeiTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surf)
    ) {
        HorizontalDivider(thickness = 1.dp, color = c.line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(76.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val half = items.size / 2
            items.forEachIndexed { index, item ->
                if (index == half) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .shadow(
                                    elevation = 14.dp,
                                    shape = RoundedCornerShape(20.dp),
                                    ambientColor = c.mint,
                                    spotColor = c.mint
                                )
                                .clip(RoundedCornerShape(20.dp))
                                .background(c.mint)
                                .clickable(onClick = onAdd),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = addContentDescription,
                                tint = c.mintInk,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
                val active = index == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (active) c.mint.copy(alpha = 0.16f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (active) c.mint else c.muted,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) c.mint else c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
