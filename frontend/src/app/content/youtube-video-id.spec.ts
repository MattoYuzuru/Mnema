import { youtubeVideoId } from './youtube-video-id';

describe('youtubeVideoId', () => {
    it('keeps only a fixed YouTube identifier from common input forms', () => {
        const id = 'M7lc1UVf-VE';
        expect(youtubeVideoId(id)).toBe(id);
        expect(youtubeVideoId(`https://www.youtube.com/watch?v=${id}&t=12`)).toBe(id);
        expect(youtubeVideoId(`https://youtu.be/${id}`)).toBe(id);
        expect(youtubeVideoId(`https://www.youtube.com/shorts/${id}`)).toBe(id);
    });

    it('rejects executable, spoofed and ambiguous URLs', () => {
        const id = 'M7lc1UVf-VE';
        for (const url of [`javascript:${id}`, `https://youtube.com.evil.test/watch?v=${id}`,
            `http://youtube.com/watch?v=${id}`, `https://youtube.com/watch?v=${id}&v=${id}`,
            `https://youtube.com:444/watch?v=${id}`, `https://youtube.com/watch?v=${id}#fragment`]) {
            expect(youtubeVideoId(url)).toBeNull();
        }
    });
});
