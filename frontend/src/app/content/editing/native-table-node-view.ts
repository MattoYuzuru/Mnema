import { Node as ProseMirrorNode } from 'prosemirror-model';
import { EditorView, NodeView } from 'prosemirror-view';

/** Editable table cells live in the document while the native table remains one versioned node. */
export class NativeTableNodeView implements NodeView {
    readonly dom: HTMLElement;
    private node: ProseMirrorNode;
    private shape = '';

    constructor(node: ProseMirrorNode, private readonly view: EditorView,
                private readonly getPos: () => number | undefined) {
        this.node = node;
        this.dom = document.createElement('div');
        this.dom.className = 'mnema-table-node';
        this.dom.contentEditable = 'false';
        this.render();
    }

    update(node: ProseMirrorNode): boolean {
        if (node.type !== this.node.type) return false;
        this.node = node;
        const shape = `${this.columns().length}:${this.rows().length}`;
        if (shape !== this.shape) this.render();
        else this.syncValues();
        return true;
    }

    selectNode(): void { this.dom.classList.add('ProseMirror-selectednode'); }
    deselectNode(): void { this.dom.classList.remove('ProseMirror-selectednode'); }
    stopEvent(event: Event): boolean {
        return event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement
            || event.target instanceof HTMLButtonElement;
    }
    ignoreMutation(): boolean { return true; }

    private columns(): string[] { return [...this.node.attrs['columns'] as string[]]; }
    private rows(): string[][] { return (this.node.attrs['rows'] as string[][]).map(row => [...row]); }

    private render(): void {
        const columns = this.columns();
        const rows = this.rows();
        this.shape = `${columns.length}:${rows.length}`;
        const heading = document.createElement('label');
        heading.className = 'mnema-table-caption';
        heading.textContent = 'Подпись таблицы';
        heading.append(this.metadataInput('caption', String(this.node.attrs['caption'])));
        const scroll = document.createElement('div');
        scroll.className = 'mnema-table-scroll';
        const table = document.createElement('table');
        const thead = table.createTHead();
        const headRow = thead.insertRow();
        columns.forEach((column, columnIndex) => {
            const header = document.createElement('th');
            header.scope = 'col';
            header.append(this.cellInput(column, `Заголовок ${columnIndex + 1}`, 'column', columnIndex));
            headRow.append(header);
        });
        const tbody = table.createTBody();
        rows.forEach((row, rowIndex) => {
            const tr = tbody.insertRow();
            row.forEach((cell, columnIndex) => {
                tr.insertCell().append(this.cellInput(cell, `Строка ${rowIndex + 1}, столбец ${columnIndex + 1}`,
                    'cell', rowIndex, columnIndex));
            });
        });
        scroll.append(table);
        const actions = document.createElement('div');
        actions.className = 'mnema-table-actions';
        if (rows.length < 100) actions.append(this.action('Добавить строку', () => {
            this.write(columns, [...rows, Array(columns.length).fill('')]);
        }));
        if (columns.length < 12) actions.append(this.action('Добавить столбец', () => {
            this.write([...columns, `Столбец ${columns.length + 1}`], rows.map(row => [...row, '']));
        }));
        if (rows.length > 1) actions.append(this.action('Удалить последнюю строку', () => {
            this.write(columns, rows.slice(0, -1));
        }));
        if (columns.length > 1) actions.append(this.action('Удалить последний столбец', () => {
            this.write(columns.slice(0, -1), rows.map(row => row.slice(0, -1)));
        }));
        const summary = document.createElement('label');
        summary.className = 'mnema-table-summary';
        summary.textContent = 'Пояснение таблицы';
        const summaryInput = document.createElement('textarea');
        summaryInput.rows = 2;
        summaryInput.value = String(this.node.attrs['summary'] ?? '');
        summaryInput.dataset['field'] = 'summary';
        summaryInput.disabled = !this.view.editable;
        summaryInput.addEventListener('input', () => this.writeMetadata('summary', summaryInput.value));
        summary.append(summaryInput);
        this.dom.replaceChildren(heading, scroll, actions, summary);
    }

    private metadataInput(field: 'caption', value: string): HTMLInputElement {
        const input = document.createElement('input');
        input.type = 'text';
        input.value = value;
        input.dataset['field'] = field;
        input.disabled = !this.view.editable;
        input.addEventListener('input', () => this.writeMetadata(field, input.value));
        return input;
    }

    private cellInput(value: string, label: string, kind: 'column' | 'cell', first: number,
                      second?: number): HTMLInputElement {
        const input = document.createElement('input');
        input.type = 'text';
        input.value = value;
        input.disabled = !this.view.editable;
        input.setAttribute('aria-label', label);
        input.dataset[kind] = String(first);
        if (second !== undefined) input.dataset['column'] = String(second);
        input.addEventListener('input', () => {
            const columns = this.columns();
            const rows = this.rows();
            if (kind === 'column') columns[first] = input.value;
            else if (rows[first] !== undefined && second !== undefined) rows[first]![second] = input.value;
            this.write(columns, rows);
        });
        return input;
    }

    private action(label: string, run: () => void): HTMLButtonElement {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        button.disabled = !this.view.editable;
        button.addEventListener('click', run);
        return button;
    }

    private syncValues(): void {
        const columns = this.columns();
        const rows = this.rows();
        this.dom.querySelectorAll<HTMLInputElement>('input').forEach(input => {
            if (input === document.activeElement) return;
            if (input.dataset['field'] === 'caption') { input.value = String(this.node.attrs['caption']); return; }
            const row = input.dataset['cell'];
            const column = input.dataset['column'];
            const value = row === undefined ? columns[Number(column)] : rows[Number(row)]?.[Number(column)];
            input.value = value ?? '';
        });
        const summary = this.dom.querySelector<HTMLTextAreaElement>('textarea[data-field="summary"]');
        if (summary && summary !== document.activeElement) summary.value = String(this.node.attrs['summary'] ?? '');
    }

    private writeMetadata(field: 'caption' | 'summary', value: string): void {
        if (!this.view.editable) return;
        const position = this.getPos();
        if (position === undefined) return;
        const attrs = { ...this.node.attrs, [field]: field === 'summary' && value.trim().length === 0 ? null : value };
        this.view.dispatch(this.view.state.tr.setNodeMarkup(position, undefined, attrs));
    }

    private write(columns: string[], rows: string[][]): void {
        if (!this.view.editable) return;
        const position = this.getPos();
        if (position === undefined) return;
        this.view.dispatch(this.view.state.tr.setNodeMarkup(position, undefined,
            { ...this.node.attrs, columns, rows }));
    }
}
