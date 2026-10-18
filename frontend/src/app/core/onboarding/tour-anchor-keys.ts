/**
 * The registry of anchor keys. A key is the whole contract between a page and a tour definition: the component that owns the place
 * writes `mnTourAnchor="community.search"`, the definition names the same key, and `tour-anchor-registry.spec.ts` checks both
 * sides against this list (a key nobody mounts, a mounted key that is not here and a definition that names a key that is not here
 * all fail the spec instead of showing «Этого места сейчас нет на экране» to a learner). Add a key here when a page mounts it.
 */
export const TOUR_ANCHORS = {
    /** `/community`: the search, the shelves and the results. */
    communitySearch: 'community.search',
    /** `/d/:code`: the summary of the deck: title, author, size. */
    communityDeck: 'community.deck',
    /** `/d/:code`: «Добавить к себе». */
    communityAdd: 'community.add',
    /** `/decks/new`: the form of a new deck. */
    ownDetails: 'own.details',
    /** `/decks/:deckId/materials/new`: the composer, or the editor when AI is not available. */
    ownMaterial: 'own.material',
    /** `/decks/:deckId/materials/:memberKey/exercises/new`: the exercise form. */
    ownExercise: 'own.exercise',
    /** `/decks/:deckId`: «Учить», the way into the first session of a branch. */
    hubStart: 'hub.start',
    /** `/decks/:deckId`: the head of the hub: title, description and how fresh the deck is. */
    hubHeader: 'hub.header',
    /** `/decks/:deckId/materials/:memberKey`: the published text of a material. */
    materialRead: 'material.read',
    /** `/decks/:deckId/materials/:memberKey/exercises/new`: the list of the exercises the material already has. */
    materialExercises: 'material.exercises',
    /** The masthead link «Новая колода»: the place the in-place hint of «Путь» shows for the first deck (OB-E3). */
    navNewDeck: 'nav.newDeck',
    /** The question «Для чего вам Mnema?» on the profile while the goal is unanswered (hint of «Путь»). */
    profileGoal: 'profile.goal',
    /** `/decks/:deckId`: «Добавить материал» (hint of «Путь»). */
    hubAddMaterial: 'hub.addMaterial',
    /** `/decks/:deckId`: the list of materials, where a material's exercises are opened (hint of «Путь»). */
    hubMaterials: 'hub.materials',
    /** `/decks/:deckId`: «На потом» (hint of «Путь»). */
    hubCapture: 'hub.capture',
    /** `/decks/:deckId/capture`: the list of notes with their checkboxes (quest «Мастерская из заметок»). */
    captureNotes: 'capture.notes',
    /** `/decks/:deckId/capture`: the bar of the picked notes with «Создать материалы с ИИ» (it exists while a note is picked). */
    captureAssemble: 'capture.assemble',
    /** `/decks/:deckId/capture`: the microphone of the note field (quest «Ответить голосом»). */
    captureMic: 'capture.mic',
    /** The material editor: the control that puts a picture or a sound into the text (quest «Добавить картинку или звук»). */
    editorMedia: 'editor.media',
    /** The material editor: «Опубликовать». */
    editorPublish: 'editor.publish'
} as const;

export type TourAnchorKey = typeof TOUR_ANCHORS[keyof typeof TOUR_ANCHORS];

export const TOUR_ANCHOR_KEYS: readonly TourAnchorKey[] = Object.values(TOUR_ANCHORS);
