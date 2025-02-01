package com.metrolist.music.utils

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.completed
import com.metrolist.innertube.utils.completedLibraryPage
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.DownloadUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncUtils @Inject constructor(
    val database: MusicDatabase,
    private val downloadUtil: DownloadUtil,
    @ApplicationContext private val context: Context
) {
    private val _isSyncingLikedSongs = MutableStateFlow(false)
    private val _isSyncingLibrarySongs = MutableStateFlow(false)
    private val _isSyncingLikedAlbums = MutableStateFlow(false)
    private val _isSyncingArtistsSubscriptions = MutableStateFlow(false)
    private val _isSyncingSavedPlaylists = MutableStateFlow(false)

    val isSyncingLikedSongs: StateFlow<Boolean> = _isSyncingLikedSongs.asStateFlow()
    val isSyncingLibrarySongs: StateFlow<Boolean> = _isSyncingLibrarySongs.asStateFlow()
    val isSyncingLikedAlbums: StateFlow<Boolean> = _isSyncingLikedAlbums.asStateFlow()
    val isSyncingArtistsSubscriptions: StateFlow<Boolean> = _isSyncingArtistsSubscriptions.asStateFlow()
    val isSyncingSavedPlaylists: StateFlow<Boolean> = _isSyncingSavedPlaylists.asStateFlow()

    suspend fun syncAll() {
        coroutineScope {
            launch { syncLikedSongs() }
            launch { syncLibrarySongs() }
            launch { syncLikedAlbums() }
            launch { syncArtistsSubscriptions() }
            launch { syncSavedPlaylists() }
        }
    }

    suspend fun syncLikedSongs() {
        if (!_isSyncingLikedSongs.compareAndSet(expect = false, update = true)) return

        try {
            YouTube.playlist("LM").completed().onSuccess { page ->
                val songs = page.songs.reversed()

                database.likedSongsByNameAsc().first()
                    .filterNot { it.id in songs.map(SongItem::id) }
                    .forEach { database.update(it.song.localToggleLike()) }

                coroutineScope {
                    songs.forEach { song ->
                        launch(Dispatchers.IO) {
                            val dbSong = database.song(song.id).firstOrNull()
                            database.transaction {
                                when (dbSong) {
                                    null -> insert(song.toMediaMetadata(), SongEntity::localToggleLike)
                                    else -> if (!dbSong.song.liked) update(dbSong.song.localToggleLike())
                                }
                            }
                        }
                    }
                }
            }

            val songs = database.likedSongsNotDownloaded().first().map { it.song }
            downloadUtil.autoDownloadIfLiked(songs)
        } finally {
            _isSyncingLikedSongs.value = false
        }
    }

    suspend fun syncLibrarySongs() {
        if (!_isSyncingLibrarySongs.compareAndSet(expect = false, update = true)) return

        try {
            val remoteSongs = getRemoteData<SongItem>("FEmusic_liked_videos", "FEmusic_library_privately_owned_tracks")

            database.songsByNameAsc().first()
                .filterNot { it.id in remoteSongs.map(SongItem::id) }
                .forEach { database.update(it.song.toggleLibrary()) }

            coroutineScope {
                remoteSongs.forEach { song ->
                    launch(Dispatchers.IO) {
                        val dbSong = database.song(song.id).firstOrNull()
                        database.transaction {
                            when (dbSong) {
                                null -> insert(song.toMediaMetadata(), SongEntity::toggleLibrary)
                                else -> if (dbSong.song.inLibrary == null) update(dbSong.song.toggleLibrary())
                            }
                        }
                    }
                }
            }
        } finally {
            _isSyncingLibrarySongs.value = false
        }
    }

    suspend fun syncLikedAlbums() {
        if (!_isSyncingLikedAlbums.compareAndSet(expect = false, update = true)) return

        try {
            val remoteAlbums = getRemoteData<AlbumItem>("FEmusic_liked_albums", "FEmusic_library_privately_owned_releases")

            database.albumsLikedByNameAsc().first()
                .filterNot { it.id in remoteAlbums.map(AlbumItem::id) }
                .forEach { database.update(it.album.localToggleLike()) }

            coroutineScope {
                remoteAlbums.forEach { album ->
                    launch(Dispatchers.IO) {
                        val dbAlbum = database.album(album.id).firstOrNull()
                        YouTube.album(album.browseId).onSuccess { albumPage ->
                            when (dbAlbum) {
                                null -> {
                                    database.insert(albumPage)
                                    database.album(album.id).firstOrNull()?.let {
                                        database.update(it.album.localToggleLike())
                                    }
                                }
                                else -> if (dbAlbum.album.bookmarkedAt == null)
                                    database.update(dbAlbum.album.localToggleLike())
                            }
                        }
                    }
                }
            }
        } finally {
            _isSyncingLikedAlbums.value = false
        }
    }

    suspend fun syncArtistsSubscriptions() {
        if (!_isSyncingArtistsSubscriptions.compareAndSet(expect = false, update = true)) return

        try {
            val remoteArtists = getRemoteData<ArtistItem>("FEmusic_library_corpus_track_artists", "FEmusic_library_privately_owned_artists")

            database.artistsBookmarkedByNameAsc().first()
                .filterNot { it.id in remoteArtists.map(ArtistItem::id) }
                .forEach { database.update(it.artist.localToggleLike()) }

            coroutineScope {
                remoteArtists.forEach { artist ->
                    launch(Dispatchers.IO) {
                        val dbArtist = database.artist(artist.id).firstOrNull()
                        database.transaction {
                            when (dbArtist) {
                                null -> {
                                    insert(
                                        ArtistEntity(
                                            id = artist.id,
                                            name = artist.title,
                                            thumbnailUrl = artist.thumbnail,
                                            channelId = artist.channelId,
                                            bookmarkedAt = LocalDateTime.now()
                                        )
                                    )
                                }
                                else -> if (dbArtist.artist.bookmarkedAt == null)
                                    update(dbArtist.artist.localToggleLike())
                            }
                        }
                    }
                }
            }
        } finally {
            _isSyncingArtistsSubscriptions.value = false
        }
    }

    suspend fun syncSavedPlaylists() {
        if (!_isSyncingSavedPlaylists.compareAndSet(expect = false, update = true)) return

        try {
            YouTube.library("FEmusic_liked_playlists").completedLibraryPage().onSuccess { page ->
                val playlistList = page.items.filterIsInstance<PlaylistItem>()
                    .filterNot { it.id == "LM" || it.id == "SE" }
                    .reversed()
                val dbPlaylists = database.playlistsByNameAsc().first()

                dbPlaylists.filterNot { it.playlist.browseId in playlistList.map(PlaylistItem::id) }
                    .filterNot { it.playlist.browseId == null }
                    .forEach { database.update(it.playlist.localToggleLike()) }

                coroutineScope {
                    playlistList.forEach { playlist ->
                        launch(Dispatchers.IO) {
                            var playlistEntity =
                                dbPlaylists.find { playlist.id == it.playlist.browseId }?.playlist
                            if (playlistEntity == null) {
                                playlistEntity = PlaylistEntity(
                                    name = playlist.title,
                                    browseId = playlist.id,
                                    isEditable = playlist.isEditable,
                                    bookmarkedAt = LocalDateTime.now(),
                                    remoteSongCount = playlist.songCountText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() },
                                    playEndpointParams = playlist.playEndpoint?.params,
                                    shuffleEndpointParams = playlist.shuffleEndpoint?.params,
                                    radioEndpointParams = playlist.radioEndpoint?.params
                                )

                                database.insert(playlistEntity)
                            } else database.update(playlistEntity, playlist)

                            syncPlaylist(playlist.id, playlistEntity.id)
                        }
                    }
                }
            }
        } finally {
            _isSyncingSavedPlaylists.value = false
        }
    }

    suspend fun syncPlaylist(browseId: String, playlistId: String) {
        YouTube.playlist(browseId).completed().onSuccess { playlistPage ->
            coroutineScope {
                launch(Dispatchers.IO) {
                    database.transaction {
                        clearPlaylist(playlistId)
                        val songEntities = playlistPage.songs
                            .map(SongItem::toMediaMetadata)
                            .onEach { insert(it) }

                        val playlistSongMaps = songEntities.mapIndexed { position, song ->
                            PlaylistSongMap(
                                songId = song.id,
                                playlistId = playlistId,
                                position = position,
                                setVideoId = song.setVideoId
                            )
                        }
                        playlistSongMaps.forEach { insert(it) }
                    }
                }
            }
        }
    }

    private suspend inline fun <reified T> getRemoteData(libraryId: String, uploadsId: String): MutableList<T> {
        val browseIds = mapOf(
            libraryId to 0,
            uploadsId to 1
        )

        val remote = mutableListOf<T>()
        coroutineScope {
            val fetchJobs = browseIds.map { (browseId, tab) ->
                async {
                    YouTube.library(browseId, tab).completed().onSuccess { page ->
                        val data = page.items.filterIsInstance<T>().reversed()
                        synchronized(remote) { remote.addAll(data) }
                    }
                }
            }
            fetchJobs.awaitAll()
        }

        return remote
    }
}
