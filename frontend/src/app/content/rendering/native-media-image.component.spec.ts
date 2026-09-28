import { TestBed } from '@angular/core/testing';

import { NativeMediaImageComponent } from './native-media-image.component';

describe('NativeMediaImageComponent', () => {
    it('starts an animated GIF on a static poster and opens a zoomable modal', () => {
        const fixture = TestBed.createComponent(NativeMediaImageComponent);
        fixture.componentRef.setInput('source', 'https://storage.example/motion.gif');
        fixture.componentRef.setInput('poster', 'https://storage.example/first.webp');
        fixture.componentRef.setInput('download', 'https://storage.example/download');
        fixture.componentRef.setInput('alt', 'Схема очереди');
        fixture.componentRef.setInput('animated', true);
        fixture.detectChanges();

        const preview = fixture.nativeElement.querySelector('.image-open img') as HTMLImageElement;
        expect(preview.src).toContain('first.webp');
        fixture.nativeElement.querySelector('.image-action').click();
        fixture.detectChanges();
        expect(preview.src).toContain('motion.gif');
        fixture.nativeElement.querySelector('.image-open').click();
        fixture.detectChanges();
        const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
        expect(dialog.open).toBeTrue();
        fixture.nativeElement.querySelector('button[aria-label="Увеличить"]').click();
        fixture.detectChanges();
        expect(dialog.querySelector('.image-canvas img')?.getAttribute('style')).toContain('width: 125%');
        fixture.nativeElement.querySelector('.image-actions button:last-child').click();
        expect(dialog.open).toBeFalse();
        fixture.destroy();
    });
});
