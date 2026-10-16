/**
 * The registry of anchor keys a tour definition may name. A page adds its key here when it mounts `mnTourAnchor`;
 * `tourDefinitionProblems` (tour-definition.ts) and its spec check every definition against this list, so a renamed or removed
 * anchor fails a test instead of silently showing «Этого места сейчас нет на экране».
 */
export const TOUR_ANCHOR_KEYS: readonly string[] = [
    'nav.decks', 'nav.community', 'nav.new', 'nav.profile',
    'hub.header', 'hub.material', 'hub.exercises', 'hub.start',
    'community.search', 'community.card', 'community.add',
    'own.name', 'own.material', 'own.exercise', 'own.start'
];
