// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistShareTest {

    private fun yt(i: Int) = Track(
        id = "yt:abcDEF_${i.toString().padStart(3, '0')}", title = "Song $i", artist = "Artist ${i % 7}",
        artwork = "https://lh3.googleusercontent.com/x$i=w600-h600", durationMs = 200_000L + i,
        source = Source.YOUTUBE, ref = "https://music.youtube.com/watch?v=abcDEF_${i.toString().padStart(3, '0')}",
    )

    private val saavn = Track(
        id = "jsv:Ab12_cd", title = "Tum Hi Ho", artist = "Arijit Singh", artwork = "https://c.saavncdn.com/123/x-500x500.jpg",
        durationMs = 262_000, source = Source.SAAVN, ref = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyWg3+abc/def==", explicit = true,
    )
    private val soundcloud = Track(
        id = "sc:some-artist/a-track", title = "A Track", artist = "Some Artist", artwork = "https://i1.sndcdn.com/a-t500x500.jpg",
        durationMs = 180_000, source = Source.SOUNDCLOUD, ref = "https://soundcloud.com/some-artist/a-track",
    )
    private val local = Track(id = "local:5", title = "Voice memo", artist = "Me", source = Source.LOCAL, ref = "content://media/external/audio/media/5")

    @Test
    fun roundTripKeepsSongsAndOrder() {
        val tracks = (1..40).map(::yt) + saavn + soundcloud + local
        val encoded = PlaylistShare.encode("Road trip 🚗", tracks)
        assertEquals(42, encoded.shared)
        assertEquals(1, encoded.skipped)

        val message = PlaylistShare.message("Road trip 🚗", encoded)
        // What arrives after WhatsApp: the whole message, maybe with extra text around it.
        val code = PlaylistShare.find("forwarded:\n$message\nthanks!")
        assertNotNull(code)
        val decoded = PlaylistShare.decode(code!!)!!
        assertEquals("Road trip 🚗", decoded.name)
        assertEquals(tracks.filter { it.source != Source.LOCAL }.map { it.id }, decoded.tracks.map { it.id })

        val song = decoded.tracks.first()
        assertEquals("https://www.youtube.com/watch?v=abcDEF_001", song.ref)
        assertEquals("Song 1", song.title)
        assertEquals(200_000L, song.durationMs)
        val js = decoded.tracks.first { it.source == Source.SAAVN }
        assertEquals(saavn.ref, js.ref)
        assertTrue(js.explicit)
        val sc = decoded.tracks.first { it.source == Source.SOUNDCLOUD }
        assertEquals(soundcloud.ref, sc.ref)
    }

    @Test
    fun codeIsCompactForATypicalPlaylist() {
        val code = PlaylistShare.encode("Mix", (1..40).map(::yt)).code
        // Comfortably pasteable in any chat app.
        assertTrue("code was ${code.length} chars", code.length < 3_000)
    }

    @Test
    fun brokenOrHostileCodesAreRejected() {
        assertNull(PlaylistShare.decode("lumen:pl1:notbase64!!"))
        assertNull(PlaylistShare.decode("lumen:pl1:AAAAAAAAAAAA"))
        val good = PlaylistShare.encode("x", listOf(yt(1))).code
        // Cut off halfway, as when only part of a message is copied.
        assertNull(PlaylistShare.decode(good.take(good.length / 2)))
    }

    @Test
    fun backupSanitizerDropsForeignStreamLinks() {
        assertNull(PlaylistShare.safeTrack(yt(1).copy(ref = "https://evil.example/watch?v=1")))
        assertNull(PlaylistShare.safeTrack(soundcloud.copy(ref = "http://soundcloud.com/a/b")))
        assertNotNull(PlaylistShare.safeTrack(yt(1)))
        assertNull(PlaylistShare.safeTrack(yt(1).copy(artwork = "javascript:alert(1)"))?.artwork)
    }

    @Test
    fun backupRoundTripMergesWithoutDeleting() {
        val mine = LibraryState(
            liked = listOf(yt(1)),
            playlists = listOf(UserPlaylist("p1", "Mine", listOf(yt(2)))),
        )
        val other = LibraryState(
            liked = listOf(yt(1), yt(3)),
            playlists = listOf(UserPlaylist("p2", "From backup", listOf(yt(4), saavn))),
        )
        val restored = LibraryBackup.read(LibraryBackup.export(other).toByteArray())!!
        val (merged, summary) = LibraryBackup.merge(mine, restored)
        assertEquals(listOf("p1", "p2"), merged.playlists.map { it.id })
        assertEquals(listOf(yt(1).id, yt(3).id), merged.liked.map { it.id })
        assertEquals(1, summary.playlists)
        assertEquals(1, summary.liked)
        assertNull(LibraryBackup.read("{\"hello\":1}".toByteArray())?.playlists?.firstOrNull())
    }
}
