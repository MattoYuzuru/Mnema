// Real Learning API fixture shared by --catalog and --recs; never touches retained or production data.
import { randomUUID } from 'node:crypto';

const paragraph = (text, index) => ({ id: `00000000-0000-4000-8000-${String(index * 2 + 1).padStart(12, '0')}`, type: 'paragraph', version: 1, attrs: {},
  content: [{ id: `00000000-0000-4000-8000-${String(index * 2 + 2).padStart(12, '0')}`, type: 'text', version: 1, attrs: { text, marks: [] }, content: [] }] });
const nativeDocument = lines => ({ formatVersion: 1, root: { id: '00000000-0000-4000-8000-000000000000', type: 'doc', version: 1, attrs: {}, content: lines.map(paragraph) } });

export async function seedCatalogDeck(learning, need, bearer, spec, { visibility, materials = 10, withExercise = true }) {
    const created = await learning('POST', '/api/decks', { bearer, payload: { commandId: randomUUID(), metadata: { title: spec.title, description: spec.description } } });
    need(created.status === 201, `${spec.title}: deck creation answered ${created.status}`);
    const deckId = created.body.deck?.deckId ?? created.body.deckId;
    need(deckId, `${spec.title}: the created deck has no id`);
    let deck = await learning('GET', `/api/decks/${deckId}`, { bearer });
    need(deck.status === 200, `${spec.title}: deck read answered ${deck.status}`);
    const keys = Array.from({ length: materials }, () => randomUUID());
    const bulk = await learning('POST', `/api/decks/${deckId}/items/publications`, { bearer, headers: { 'If-Match': deck.etag }, payload: {
      commandId: randomUUID(), expectedDeckRevisionId: deck.body.revisionId,
      changes: keys.map((memberKey, index) => ({ operation: 'create', memberKey, document: nativeDocument([`${spec.title}: карточка ${index + 1}`]) })) } });
    need(bulk.status === 200, `${spec.title}: the bulk of materials answered ${bulk.status} ${JSON.stringify(bulk.body?.code ?? bulk.body?.detail ?? null)}`);
    if (withExercise) {
      deck = await learning('GET', `/api/decks/${deckId}`, { bearer });
      const item = await learning('GET', `/api/decks/${deckId}/items/${keys[0]}`, { bearer });
      need(deck.status === 200 && item.status === 200, `${spec.title}: the first material cannot be read`);
      const node = item.body.document?.root?.content?.[0]?.id;
      need(node, `${spec.title}: the first material has no paragraph to quote`);
      const exercise = await learning('POST', `/api/decks/${deckId}/exercises`, { bearer, headers: { 'If-Match': deck.etag }, payload: {
        commandId: randomUUID(), expectedDeckRevisionId: deck.body.revisionId, objective: { operation: 'create', title: 'Слово: проверка' },
        exercise: { type: 'FREE_RESPONSE', schemaVersion: 2, enabled: true, subject: { memberKey: item.body.memberKey, itemRevisionId: item.body.itemRevisionId },
          content: { prompt: [{ kind: 'TEXT', text: 'Введите слово «проверка»' }],
            reference: [{ kind: 'MATERIAL', memberKey: item.body.memberKey, itemRevisionId: item.body.itemRevisionId, nodeId: node }], responseInput: 'TEXT' },
          answerKey: { kind: 'TEXT', accepted: ['проверка'], normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' },
          evaluatorPolicy: { id: 'deterministic-text', version: '1' } } } });
      need(exercise.status === 201, `${spec.title}: the exercise answered ${exercise.status} ${JSON.stringify(exercise.body?.code ?? exercise.body?.detail ?? null)}`);
    }
    deck = await learning('GET', `/api/decks/${deckId}`, { bearer });
    const published = await learning('PUT', `/api/decks/${deckId}/publication`, { bearer, headers: { 'If-Match': '"0"' }, payload: {
      commandId: randomUUID(), visibility,
      metadata: { topicId: spec.topicId, contentLanguage: spec.content, targetLanguage: spec.target, level: spec.level, tags: spec.tags },
      requestsEnabled: true, publish: { expectedHeadRevisionId: deck.body.revisionId, releaseNote: null } } });
    need(published.status === 200, `${spec.title}: the publication answered ${published.status} ${JSON.stringify(published.body?.code ?? published.body?.failed ?? null)}`);
    const state = published.body.publication;
    return { deckId, code: state.publicCode, link: state.link, title: spec.title };
  }
