import { Component, TemplateRef, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import richDocumentJson from '../../../../../contracts/content/native-v1/valid/rich.json';
import { NativeDocument } from '../native-document';
import { NativeDocumentRendererComponent } from './native-document-renderer.component';
import { NativeMediaSurfaceComponent } from './native-media-surface.component';
import { MediaPlaybackApi } from './media-playback.api';
import { BlockOverlay, BlockSlotContext } from './native-top-block.directive';
import { documentOf, mixedNativeDocumentFixture, nativeNode } from './native-renderer.fixtures';

const id = (n: number): string => `20000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const paragraph = (n: number, text: string) => nativeNode('paragraph', {}, [nativeNode('text', { text, marks: [] })], { id: id(n) });
const sample = (): NativeDocument => documentOf([
    nativeNode('heading', { level: 2 }, [nativeNode('text', { text: 'Заголовок', marks: [] })], { id: id(1) }),
    paragraph(2, 'Первый абзац.'),
    nativeNode('bullet_list', {}, [nativeNode('list_item', {}, [paragraph(30, 'пункт')], { id: id(31) })], { id: id(3) }),
    nativeNode('blockquote', {}, [paragraph(40, 'цитата')], { id: id(4) }),
    nativeNode('code_block', { lang: 'sql', source: 'select 1;' }, [], { id: id(5) }),
    nativeNode('divider', {}, [], { id: id(6) })
]);

@Component({
    selector: 'app-overlay-host',
    imports: [NativeDocumentRendererComponent],
    template: `
      <app-native-document-renderer [document]="document" [exposeNodeIds]="true" [overlay]="overlay()" />
      <ng-template #slot let-nodeId let-placement="placement"><em class="slot" [attr.data-after]="nodeId" [attr.data-placement]="placement">{{ nodeId }}</em></ng-template>
    `
})
class OverlayHostComponent {
    readonly document = sample();
    readonly slot = viewChild.required<TemplateRef<BlockSlotContext>>('slot');
    readonly empty = new Set<string>();
    readonly overlay = signal<BlockOverlay | null>(null);
}

describe('node ids on the top-level blocks (the Workshop only)', () => {
    afterEach(() => TestBed.resetTestingModule());

    describe('the renderer', () => {
        let fixture: ComponentFixture<NativeDocumentRendererComponent>;
        const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

        beforeEach(async () => {
            await TestBed.configureTestingModule({ imports: [NativeDocumentRendererComponent] }).compileComponents();
            fixture = TestBed.createComponent(NativeDocumentRendererComponent);
        });

        it('puts none on a document unless it is asked to, so Browse and Study never carry node ids', () => {
            for (const document of [sample(), mixedNativeDocumentFixture(), richDocumentJson as unknown as NativeDocument]) {
                fixture.componentRef.setInput('document', document);
                fixture.detectChanges();
                expect(host().querySelectorAll('[data-node-id]')).toHaveLength(0);
                expect(host().querySelectorAll('[aria-busy], .is-rewriting, .is-target')).toHaveLength(0);
            }
        });

        it('puts the id of every top-level block on it when asked, and never on a nested one', () => {
            fixture.componentRef.setInput('document', sample());
            fixture.componentRef.setInput('exposeNodeIds', true);
            fixture.detectChanges();
            const marked = [...host().querySelectorAll<HTMLElement>('[data-node-id]')];
            expect(marked.map(node => node.dataset['nodeId'])).toEqual([1, 2, 3, 4, 5, 6].map(id));
            expect(marked.map(node => node.tagName.toLowerCase())).toEqual(['h3', 'p', 'ul', 'blockquote', 'figure', 'hr']);
            expect(host().querySelector('li')!.hasAttribute('data-node-id')).toBe(false);
            expect(host().querySelector('blockquote p')!.hasAttribute('data-node-id')).toBe(false);
            expect(marked.every(node => node.parentElement!.classList.contains('native-document'))).toBe(true);
        });

        it('exposes the blocks of every kind: media, schemes, tables, videos and the placeholder of an unknown block', () => {
            for (const document of [richDocumentJson as unknown as NativeDocument, mixedNativeDocumentFixture()]) {
                fixture.componentRef.setInput('document', document);
                fixture.componentRef.setInput('exposeNodeIds', true);
                fixture.detectChanges();
                const top = [...host().querySelectorAll<HTMLElement>('.native-document > *')];
                expect(top.length).toBeGreaterThan(0);
                expect(top.every(node => node.dataset['nodeId'] !== undefined)).toBe(true);
                expect(host().querySelectorAll('[data-node-id]')).toHaveLength(top.length);
            }
        });
    });

    describe('the overlay of the Workshop', () => {
        let fixture: ComponentFixture<OverlayHostComponent>;
        const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
        const block = (n: number): HTMLElement | null => host().querySelector<HTMLElement>(`[data-node-id="${id(n)}"]`);

        beforeEach(() => {
            TestBed.configureTestingModule({ imports: [OverlayHostComponent] });
            fixture = TestBed.createComponent(OverlayHostComponent);
            fixture.detectChanges();
        });
        const show = (change: Partial<BlockOverlay>): void => {
            fixture.componentInstance.overlay.set({ busy: new Set(), marked: new Set(), hidden: new Set(), before: new Set(), after: new Set(),
                template: fixture.componentInstance.slot(), ...change });
            fixture.detectChanges();
        };

        it('marks busy blocks (aria-busy and a class) and chosen blocks, and leaves the text alone', () => {
            show({ busy: new Set([id(2), id(3)]), marked: new Set([id(1)]) });
            expect(block(2)!.getAttribute('aria-busy')).toBe('true');
            expect(block(3)!.getAttribute('aria-busy')).toBe('true');
            expect(block(2)!.classList.contains('is-rewriting')).toBe(true);
            expect(block(1)!.classList.contains('is-target')).toBe(true);
            expect(block(1)!.hasAttribute('aria-busy')).toBe(false);
            expect(block(2)!.textContent).toBe('Первый абзац.');
            show({});
            expect(host().querySelectorAll('[aria-busy], .is-rewriting, .is-target')).toHaveLength(0);
        });

        it('leaves some blocks out and draws a slot before or after others, in place and in order', () => {
            show({ hidden: new Set([id(2)]), before: new Set([id(2)]), after: new Set([id(3), id(2)]) });
            expect(block(2)).toBeNull();
            const children = [...host().querySelectorAll('.native-document > *')];
            expect(children.map(node => node.tagName === 'EM' ? `${(node as HTMLElement).dataset['placement']}:${(node as HTMLElement).dataset['after']!.slice(-2)}` : node.tagName.toLowerCase()))
                .toEqual(['h3', 'before:02', 'after:02', 'ul', 'after:03', 'blockquote', 'figure', 'hr']);
            show({});
            expect(host().querySelectorAll('em.slot')).toHaveLength(0);
            expect(block(2)).not.toBeNull();
        });
    });

    describe('the media surface', () => {
        it('hands the same two inputs to the renderer', async () => {
            await TestBed.configureTestingModule({ imports: [NativeMediaSurfaceComponent],
                providers: [{ provide: MediaPlaybackApi, useValue: { read: () => new Promise(() => {}) } }] }).compileComponents();
            const surface = TestBed.createComponent(NativeMediaSurfaceComponent);
            surface.componentRef.setInput('document', sample());
            surface.detectChanges();
            expect(surface.nativeElement.querySelectorAll('[data-node-id]')).toHaveLength(0);
            surface.componentRef.setInput('exposeNodeIds', true);
            surface.detectChanges();
            expect(surface.nativeElement.querySelectorAll('[data-node-id]')).toHaveLength(6);
        });
    });
});
