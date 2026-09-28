import { TestBed } from '@angular/core/testing';

import { NativeYoutubeComponent } from './native-youtube.component';

describe('NativeYoutubeComponent', () => {
    it('loads only the privacy-enhanced provider frame after an explicit action', () => {
        const fixture = TestBed.createComponent(NativeYoutubeComponent);
        fixture.componentRef.setInput('videoId', 'M7lc1UVf-VE');
        fixture.componentRef.setInput('title', 'Пояснение схемы');
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('iframe')).toBeNull();
        expect((fixture.nativeElement.querySelector('a') as HTMLAnchorElement).href)
            .toBe('https://www.youtube.com/watch?v=M7lc1UVf-VE');
        fixture.nativeElement.querySelector('button').click();
        fixture.detectChanges();
        const frame = fixture.nativeElement.querySelector('iframe') as HTMLIFrameElement;
        expect(frame.src).toBe('https://www.youtube-nocookie.com/embed/M7lc1UVf-VE');
        expect(frame.getAttribute('title')).toBe('Пояснение схемы');
        fixture.destroy();
    });

    it('never trusts an arbitrary stored URL as an iframe source', () => {
        const fixture = TestBed.createComponent(NativeYoutubeComponent);
        fixture.componentRef.setInput('videoId', 'https://attacker.example/embed');
        fixture.componentRef.setInput('title', 'Video');
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('iframe, a')).toBeNull();
        expect(fixture.nativeElement.querySelector('[role=alert]')).not.toBeNull();
        fixture.destroy();
    });
});
