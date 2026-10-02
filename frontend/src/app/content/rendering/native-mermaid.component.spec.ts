import { TestBed } from '@angular/core/testing';

import { NativeMermaidComponent } from './native-mermaid.component';

describe('NativeMermaidComponent', () => {
    // The app loads these from src/theme/tokens.css; jsdom gets no application stylesheet, and Mermaid rejects empty colours.
    const tokens: Record<string, string> = { '--mn-sheet': '#fbf8ef', '--mn-soft': '#e8e1ed', '--mn-ink': '#281378',
        '--mn-font-body': 'system-ui, sans-serif' };
    beforeEach(() => {
        for (const [name, value] of Object.entries(tokens)) document.documentElement.style.setProperty(name, value);
    });
    afterEach(() => {
        for (const name of Object.keys(tokens)) document.documentElement.style.removeProperty(name);
    });

    it('keeps authored source inert and retains a readable description', async () => {
        await TestBed.configureTestingModule({ imports: [NativeMermaidComponent] }).compileComponents();
        const fixture = TestBed.createComponent(NativeMermaidComponent);
        fixture.componentRef.setInput('source', 'flowchart LR\nA["<script>globalThis.pwned=1</script>"] --> B');
        fixture.componentRef.setInput('title', 'Путь запроса');
        fixture.componentRef.setInput('description', 'Система A передаёт запрос системе B.');
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('figcaption')?.textContent).toContain('Система A передаёт запрос');
        expect(host.querySelector('pre code')?.textContent).toContain('<script>');
        expect(host.querySelector('script, svg, foreignObject')).toBeNull();
        fixture.destroy();
    });

    it('renders valid diagrams as isolated images and gives invalid source an accessible fallback', async () => {
        await TestBed.configureTestingModule({ imports: [NativeMermaidComponent] }).compileComponents();
        const fixture = TestBed.createComponent(NativeMermaidComponent);
        fixture.componentRef.setInput('source', 'flowchart LR\nClient --> API');
        fixture.componentRef.setInput('title', 'Схема запроса');
        fixture.componentRef.setInput('description', 'Клиент вызывает API.');
        fixture.detectChanges();

        await waitFor(() => fixture.componentInstance.imageUrl() !== null);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        expect(host.querySelector('img')?.getAttribute('src')).toMatch(/^blob:/);
        expect(host.querySelector('img')?.getAttribute('alt')).toBe('Схема запроса');
        expect(host.querySelector('svg, script, foreignObject')).toBeNull();

        const zoom = host.querySelector<HTMLButtonElement>('.diagram-zoom')!;
        zoom.click();
        fixture.detectChanges();
        const dialog = host.querySelector<HTMLDialogElement>('.diagram-dialog')!;
        expect(dialog.open).toBe(true);
        expect(dialog.querySelector('img')?.getAttribute('alt')).toBe('Схема запроса');
        host.querySelector<HTMLButtonElement>('.diagram-close')!.click();
        await fixture.whenStable();
        expect(dialog.open).toBe(false);
        expect(document.activeElement).toBe(zoom);

        fixture.componentRef.setInput('source', 'flowchart LR\nClient -->');
        fixture.detectChanges();
        await waitFor(() => fixture.componentInstance.failed());
        fixture.detectChanges();
        expect(host.querySelector('img')).toBeNull();
        expect(host.querySelector('[role="status"]')?.textContent).toContain('не удалось');
        expect(host.querySelector('figcaption')?.textContent).toContain('Клиент вызывает API');
        expect(host.querySelector('pre code')?.textContent).toContain('Client -->');
        fixture.destroy();
    }, 20000);
});

async function waitFor(predicate: () => boolean): Promise<void> {
    const deadline = Date.now() + 15000;
    while (!predicate() && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 25));
    }
    expect(predicate()).toBe(true);
}
