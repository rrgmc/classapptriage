package com.rrgmc.classapptriage.ui.moments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.api.ClassAppClient
import com.rrgmc.classapptriage.api.Moment
import com.rrgmc.classapptriage.api.MomentMedia
import com.rrgmc.classapptriage.ui.appViewModel
import com.rrgmc.classapptriage.ui.formatElapsed
import com.rrgmc.classapptriage.ui.messages.Avatar
import com.rrgmc.classapptriage.ui.messages.looksLikeHtml
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(onBack: () -> Unit) {
    val vm = appViewModel { MomentsViewModel(it) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val now = remember { Instant.now() }

    LaunchedEffect(state.error) {
        val text = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.dismissError()
    }
    // Loads the next page as the end of the list comes into view.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.hasMore, state.loading) {
        if (nearEnd && state.hasMore && !state.loading) vm.loadMore()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.moments_title))
                        if (state.entityName.isNotEmpty()) {
                            Text(state.entityName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
                    if (state.entityId != 0L) {
                        IconButton(onClick = { openUri(context, ClassAppClient.momentsWebUrl(state.entityId), null) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.detail_open_web))
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                state.loading && state.moments.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.moments.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(R.string.moments_none),
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(state.moments, key = { it.id }) { m ->
                        MomentCard(m, now, liking = m.id in state.liking, onLike = { vm.toggleLike(m.id) })
                        HorizontalDivider()
                    }
                    if (state.loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentCard(m: Moment, now: Instant, liking: Boolean, onLike: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                name = m.entity?.fullname.orEmpty(),
                pictureUri = m.entity?.picture?.uri,
                unread = false,
                background = MaterialTheme.colorScheme.surface,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                m.entity?.fullname.orEmpty(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatElapsed(m.created, now), style = MaterialTheme.typography.labelMedium, color = muted)
        }
        if (m.medias.isNotEmpty()) MomentMedias(m.medias)
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLike, enabled = !liking) {
                if (m.liked) Icon(Icons.Filled.Favorite, stringResource(R.string.moments_unlike), tint = Color(0xFFE53935))
                else Icon(Icons.Filled.FavoriteBorder, stringResource(R.string.moments_like))
            }
            if (m.likes > 0) {
                Text(pluralStringResource(R.plurals.moments_likes, m.likes, m.likes), style = MaterialTheme.typography.labelLarge)
            }
        }
        val raw = m.content.orEmpty()
        if (raw.isNotBlank()) {
            val text = remember(raw) { if (looksLikeHtml(raw)) AnnotatedString.fromHtml(raw) else AnnotatedString(raw) }
            Text(text, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The moment's photos and videos, swiped through when there are several. */
@Composable
private fun MomentMedias(medias: List<MomentMedia>) {
    // One aspect ratio for the whole pager (the first item's), kept within
    // portrait 4:5 and landscape 1.91:1 so a single tall photo can't fill the screen.
    val first = medias.first()
    val ratio = if (first.width > 0 && first.height > 0) (first.width.toFloat() / first.height).coerceIn(0.8f, 1.91f) else 1f
    if (medias.size == 1) {
        MediaPage(first, Modifier.fillMaxWidth().aspectRatio(ratio))
        return
    }
    val pager = rememberPagerState { medias.size }
    Box {
        HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(ratio), key = { medias[it].id }) { page ->
            MediaPage(medias[page], Modifier.fillMaxSize())
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.6f),
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            Text("${pager.currentPage + 1}/${medias.size}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A photo, or a video's thumbnail with a play mark; tapping opens it in an external app. */
@Composable
private fun MediaPage(media: MomentMedia, modifier: Modifier) {
    val context = LocalContext.current
    val image = if (media.isVideo) media.thumbnail else media.original?.takeIf { it.isNotBlank() } ?: media.uri
    val target = if (media.isVideo) media.uri else image
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { openUri(context, target, media.mimetype) },
        contentAlignment = Alignment.Center,
    ) {
        if (!image.isNullOrBlank()) {
            AsyncImage(
                model = image,
                contentDescription = media.origName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (media.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.moments_play_video),
                tint = Color.White,
                modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(8.dp),
            )
        }
    }
}

private fun openUri(context: Context, uri: String?, mimetype: String?) {
    try {
        if (uri.isNullOrBlank()) throw ActivityNotFoundException()
        val intent = Intent(Intent.ACTION_VIEW)
        if (mimetype.isNullOrBlank()) intent.data = uri.toUri() else intent.setDataAndType(uri.toUri(), mimetype)
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.detail_open_failed), Toast.LENGTH_SHORT).show()
    }
}
