const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const YOUTUBE_HOSTS = new Set(['youtube.com', 'www.youtube.com', 'm.youtube.com',
    'www.youtube-nocookie.com']);

/** Converts an allowlisted YouTube URL to an inert video ID; never persists embed HTML. */
export function youtubeVideoId(value: string): string | null {
    const candidate = value.trim();
    if (VIDEO_ID.test(candidate)) return candidate;
    let url: URL;
    try { url = new URL(candidate); } catch { return null; }
    if (url.protocol !== 'https:' || url.username || url.password || url.port || url.hash) return null;
    let id: string | null = null;
    if (url.hostname === 'youtu.be') {
        if (!/^\/[A-Za-z0-9_-]{11}$/.test(url.pathname)) return null;
        id = url.pathname.slice(1);
    } else if (YOUTUBE_HOSTS.has(url.hostname)) {
        if (url.pathname === '/watch') {
            if (url.searchParams.getAll('v').length !== 1) return null;
            id = url.searchParams.get('v');
        } else {
            const match = /^\/(?:embed|shorts)\/([A-Za-z0-9_-]{11})$/.exec(url.pathname);
            id = match?.[1] ?? null;
        }
    }
    return id !== null && VIDEO_ID.test(id) ? id : null;
}
