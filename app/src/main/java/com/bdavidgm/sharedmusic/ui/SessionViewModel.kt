package com.bdavidgm.sharedmusic.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.bdavidgm.sharedmusic.data.SessionRepository
import com.bdavidgm.sharedmusic.domain.model.SessionState
import com.bdavidgm.sharedmusic.service.SessionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Único intermediario entre la UI y la lógica de sesión. La UI solo observa
 * [state] y delega acciones aquí; toda la lógica vive en el repositorio.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val repository: SessionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val state: StateFlow<SessionState> = repository.state

    fun startServer(port: Int) {
        SessionService.start(context)
        repository.startServer(port)
    }

    fun startClient(host: String, port: Int) {
        SessionService.start(context)
        repository.startClient(host, port)
    }

    fun startRepeater(upstreamHost: String, upstreamPort: Int, listenPort: Int) {
        SessionService.start(context)
        repository.startRepeater(upstreamHost, upstreamPort, listenPort)
    }

    fun selectTrack(uri: Uri) = repository.selectTrack(uri)

    fun addPlaylistItems(uris: List<Uri>) = repository.addPlaylistItems(uris)

    fun addPlaylistFromTree(treeUri: Uri) = repository.addPlaylistFromTree(treeUri)

    fun togglePlaylistTransport() = repository.togglePlaylistTransport()

    fun removePlaylistItemAt(index: Int) = repository.removePlaylistItemAt(index)

    fun play() = repository.startPlayback()

    fun pause() = repository.pause()

    fun resume() = repository.resume()

    fun seekTo(positionMs: Long) = repository.seekTo(positionMs)

    fun stopPlayback() = repository.stopPlayback()

    fun stopSession() {
        repository.stopSession()
        SessionService.stop(context)
    }
}
