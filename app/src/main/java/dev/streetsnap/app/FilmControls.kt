package dev.streetsnap.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.streetsnap.color.FilmProfile
import dev.streetsnap.color.FilmRegistry

@Composable
internal fun FilmAdjustments(settings: AppSettings, model: CameraViewModel) {
    val active = FilmRegistry.find(settings.filmId)
    // T121: film bodies really load a roll into the camera; digital bodies apply the same
    // recipes as digital film-stock looks. GR never reaches this section (no film tab).
    val body = BodyCatalog.resolve(settings.cameraBody)
    val heading = when {
        body?.filmCamera == true -> "为 ${body.brand} ${body.model} 装一卷"
        body != null -> "胶片质感配方 · 数字模拟"
        else -> "胶片配方"
    }
    val emptyHint = when {
        body?.filmCamera == true -> "未装卷 · 当前为镜头原色，选一卷胶片决定色彩"
        else -> "选择胶片质感，拍下自己的色彩。"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(heading, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { model.setFilm(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (active == null) "未装卷" else "卸下胶片", fontSize = 12.sp)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = when {
            maxWidth / fontScale < 240.dp -> 2
            maxWidth / fontScale < 420.dp -> 3
            else -> 4
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilmRegistry.entries.chunked(columns).forEach { films ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    films.forEach { film ->
                        FilmCard(film, active?.id == film.id, Modifier.weight(1f)) { model.setFilm(film.id) }
                    }
                    repeat(columns - films.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    Text(active?.description ?: emptyHint, Modifier.padding(vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("影调里可调颗粒、饱和度等，每卷单独记忆。",
        Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FilmCard(film: FilmProfile, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected; contentDescription = "胶片：${film.displayName}" }) {
        Box(Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .15f), RoundedCornerShape(10.dp))) {
            FilmArtwork(film, Modifier.fillMaxSize().padding(6.dp))
            if (selected) Text("✓", Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = .75f))
                .padding(horizontal = 5.dp), color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(film.displayName, Modifier.fillMaxWidth().padding(top = 5.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
