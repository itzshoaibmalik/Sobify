// API Configuration
const MUSIXMATCH_API_KEY = 'ada5dae30f9844762db447567a1e97e0';
const MUSIXMATCH_BASE_URL = 'https://api.musixmatch.com/ws/1.1';
const CORS_PROXY = 'https://cors-anywhere.herokuapp.com/';

// DOM Elements
const landingPage = document.getElementById('landingPage');
const playerContainer = document.getElementById('playerContainer');
const songSearch = document.getElementById('songSearch');
const searchBtn = document.getElementById('searchBtn');
const playBtn = document.getElementById('playBtn');
const prevBtn = document.getElementById('prevBtn');
const nextBtn = document.getElementById('nextBtn');
const albumCover = document.getElementById('albumCover');
const songTitle = document.getElementById('songTitle');
const artistName = document.getElementById('artistName');
const lyrics = document.getElementById('lyrics');
const progressBar = document.querySelector('.progress');
const progressFilled = document.querySelector('.progress-filled');
const volumeSlider = document.querySelector('.volume-slider');
const currentTimeSpan = document.querySelector('.current-time');
const totalTimeSpan = document.querySelector('.total-time');

// Audio element
const audio = new Audio();

// State
let currentSong = null;
let isPlaying = false;
let searchResults = [];

// Search functionality
searchBtn.addEventListener('click', async () => {
    const searchTerm = songSearch.value.trim();
    if (searchTerm) {
        try {
            showLoading();
            const results = await searchTracks(searchTerm);
            if (results.length > 0) {
                searchResults = results;
                await loadSong(results[0]);
                showPlayer();
            } else {
                alert('No songs found. Please try a different search term.');
            }
        } catch (error) {
            console.error('Error searching for songs:', error);
            alert('Error searching for songs. Please try again. If the error persists, try using a different search term.');
        } finally {
            hideLoading();
        }
    }
});

songSearch.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') {
        searchBtn.click();
    }
});

// API Functions
async function searchTracks(query) {
    try {
        const response = await fetch(`${CORS_PROXY}${MUSIXMATCH_BASE_URL}/track.search?q=${encodeURIComponent(query)}&apikey=${MUSIXMATCH_API_KEY}&page_size=10&s_track_rating=desc`);
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        const data = await response.json();
        
        if (data.message.header.status_code === 200) {
            const tracks = data.message.body.track_list;
            if (!tracks || tracks.length === 0) {
                return [];
            }
            
            return tracks.map(track => ({
                id: track.track.track_id,
                title: track.track.track_name,
                artist: track.track.artist_name,
                album: track.track.album_name,
                cover: track.track.album_coverart_100x100 || 'https://via.placeholder.com/400',
                preview: track.track.track_share_url
            }));
        } else {
            throw new Error(`API error: ${data.message.header.status_code}`);
        }
    } catch (error) {
        console.error('Error in searchTracks:', error);
        throw error;
    }
}

async function getLyrics(trackId) {
    try {
        const response = await fetch(`${CORS_PROXY}${MUSIXMATCH_BASE_URL}/track.lyrics.get?track_id=${trackId}&apikey=${MUSIXMATCH_API_KEY}`);
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        const data = await response.json();
        
        if (data.message.header.status_code === 200) {
            const lyricsText = data.message.body.lyrics.lyrics_body;
            if (!lyricsText) {
                return [];
            }
            
            // Split lyrics into lines and create time-based array
            return lyricsText.split('\n')
                .filter(line => line.trim() !== '')
                .map((line, index) => ({
                    time: index * 3, // Approximate timing (3 seconds per line)
                    text: line
                }));
        } else {
            throw new Error(`API error: ${data.message.header.status_code}`);
        }
    } catch (error) {
        console.error('Error in getLyrics:', error);
        return [];
    }
}

// Load song
async function loadSong(song) {
    try {
        showLoading();
        currentSong = song;
        
        // Update UI
        albumCover.src = song.cover;
        songTitle.textContent = song.title;
        artistName.textContent = song.artist;
        
        // Get lyrics
        const lyricsData = await getLyrics(song.id);
        
        // Populate lyrics
        if (lyricsData.length > 0) {
            lyrics.innerHTML = lyricsData.map(line => 
                `<p class="lyric-line">${line.text}</p>`
            ).join('');
        } else {
            lyrics.innerHTML = '<p class="lyric-line">No lyrics available for this track.</p>';
        }

        // Load audio preview (if available)
        if (song.preview) {
            audio.src = song.preview;
            audio.load();
        } else {
            console.log('No preview available for this track');
            audio.src = '';
        }
        
    } catch (error) {
        console.error('Error loading song:', error);
        alert('Error loading song. Please try another song.');
    } finally {
        hideLoading();
    }
}

// Play/Pause functionality
playBtn.addEventListener('click', () => {
    if (!currentSong) return;
    
    if (isPlaying) {
        audio.pause();
        playBtn.innerHTML = '<i class="fas fa-play"></i>';
    } else {
        if (audio.src) {
            audio.play().catch(error => {
                console.error('Error playing audio:', error);
                alert('Unable to play audio preview. Please try another song.');
            });
            playBtn.innerHTML = '<i class="fas fa-pause"></i>';
        } else {
            alert('No audio preview available for this track.');
        }
    }
    isPlaying = !isPlaying;
});

// Previous song
prevBtn.addEventListener('click', () => {
    const currentIndex = searchResults.findIndex(song => song.id === currentSong.id);
    if (currentIndex > 0) {
        loadSong(searchResults[currentIndex - 1]);
        if (isPlaying) {
            audio.play();
        }
    }
});

// Next song
nextBtn.addEventListener('click', () => {
    const currentIndex = searchResults.findIndex(song => song.id === currentSong.id);
    if (currentIndex < searchResults.length - 1) {
        loadSong(searchResults[currentIndex + 1]);
        if (isPlaying) {
            audio.play();
        }
    }
});

// Progress bar functionality
audio.addEventListener('timeupdate', () => {
    const percent = (audio.currentTime / audio.duration) * 100;
    progressFilled.style.width = `${percent}%`;
    currentTimeSpan.textContent = formatTime(audio.currentTime);
});

audio.addEventListener('loadedmetadata', () => {
    totalTimeSpan.textContent = formatTime(audio.duration);
});

progressBar.addEventListener('click', (e) => {
    const percent = (e.offsetX / progressBar.offsetWidth);
    audio.currentTime = percent * audio.duration;
});

// Volume control
volumeSlider.addEventListener('input', (e) => {
    audio.volume = e.target.value / 100;
});

// Format time in MM:SS
function formatTime(seconds) {
    if (isNaN(seconds)) return '0:00';
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
}

// Show player and hide landing page
function showPlayer() {
    landingPage.style.display = 'none';
    playerContainer.style.display = 'grid';
}

// Loading animation
function showLoading() {
    const loading = document.createElement('div');
    loading.className = 'loading';
    loading.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';
    document.body.appendChild(loading);
}

function hideLoading() {
    const loading = document.querySelector('.loading');
    if (loading) {
        loading.remove();
    }
}

// Add keyboard shortcuts
document.addEventListener('keydown', (e) => {
    if (e.code === 'Space') {
        e.preventDefault();
        playBtn.click();
    } else if (e.code === 'ArrowLeft') {
        prevBtn.click();
    } else if (e.code === 'ArrowRight') {
        nextBtn.click();
    }
});

// Sample music data (in a real app, this would come from an API)
const sampleTracks = [
    {
        title: "Summer Vibes",
        artist: "Cool Artist",
        duration: "3:45",
        cover: "https://via.placeholder.com/60",
        audio: "https://example.com/sample1.mp3"
    },
    {
        title: "Chill Beats",
        artist: "Relaxing Artist",
        duration: "4:20",
        cover: "https://via.placeholder.com/60",
        audio: "https://example.com/sample2.mp3"
    }
];

// DOM Elements
const searchInput = document.querySelector('.search-bar input');
const playlistCards = document.querySelectorAll('.playlist-card');
const tracks = document.querySelectorAll('.track');

// Search functionality
searchInput.addEventListener('input', (e) => {
    const searchTerm = e.target.value.toLowerCase();
    // In a real app, this would filter the tracks from an API
    console.log('Searching for:', searchTerm);
});

// Playlist card hover effects
playlistCards.forEach(card => {
    card.addEventListener('mouseenter', () => {
        card.style.transform = 'translateY(-4px)';
    });
    
    card.addEventListener('mouseleave', () => {
        card.style.transform = 'translateY(0)';
    });
});

// Track click functionality
tracks.forEach(track => {
    track.addEventListener('click', () => {
        // In a real app, this would load the selected track
        const trackTitle = track.querySelector('h4').textContent;
        console.log('Playing track:', trackTitle);
        
        // Update now playing section
        document.querySelector('.now-playing h4').textContent = trackTitle;
        document.querySelector('.now-playing p').textContent = track.querySelector('p').textContent;
        document.querySelector('.now-playing img').src = track.querySelector('img').src;
        
        // Start playing
        if (!isPlaying) {
            playBtn.click();
        }
    });
});

// Add smooth scrolling
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        e.preventDefault();
        document.querySelector(this.getAttribute('href')).scrollIntoView({
            behavior: 'smooth'
        });
    });
});

// Simulate loading tracks
function loadTracks() {
    showLoading();
    setTimeout(() => {
        hideLoading();
        // In a real app, this would populate the tracks from an API
        console.log('Tracks loaded');
    }, 1000);
}

// Initialize
loadTracks(); 