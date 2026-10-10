-- After the draft DDL: create a copy B of the busiest deck A in A's scope (what Share/10 does), O(1) + heads job.
SELECT d.deck_id AS a, d.reuse_scope_id AS scope, d.owner_id AS a_owner, r.members_root_id AS mroot, r.exercises_root_id AS xroot, r.revision_id AS a_rev
FROM deck d JOIN deck_revision r ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id
WHERE d.deleted_at IS NULL ORDER BY r.member_count DESC LIMIT 1 \gset
SELECT gen_random_uuid() AS b, gen_random_uuid() AS b_rev, gen_random_uuid() AS b_owner, gen_random_uuid() AS mpin, gen_random_uuid() AS xpin \gset
INSERT INTO storage_pin(reuse_scope_id,pin_id,root_id,pin_kind,owner_kind,owner_id,actor_id)
  VALUES (:'scope',:'mpin',:'mroot','durable','deck.revision',:'b_rev',:'b_owner'),
         (:'scope',:'xpin',:'xroot','durable','deck.revision',:'b_rev',:'b_owner');
INSERT INTO deck(deck_id,owner_id,reuse_scope_id,head_revision_id,row_version,created_at)
  VALUES (:'b',:'b_owner',:'scope',:'b_rev',0,now());
INSERT INTO deck_revision(deck_id,revision_id,reuse_scope_id,owner_id,sequence,command_id,title,description,created_at,
    members_root_id,exercises_root_id,members_pin_id,exercises_pin_id,member_count,exercise_count)
  SELECT :'b',:'b_rev',:'scope',:'b_owner',0,gen_random_uuid(),title,description,now(),members_root_id,exercises_root_id,
    :'mpin',:'xpin',member_count,exercise_count FROM deck_revision WHERE deck_id=:'a' AND revision_id=:'a_rev';
-- Heads job (prototype: from A's head projection; Share/10 reads the published manifest).
INSERT INTO deck_head_item(deck_id,member_key,revision_id,item_sequence,updated_at,reuse_scope_id)
  SELECT :'b',member_key,revision_id,item_sequence,now(),reuse_scope_id FROM deck_head_item WHERE deck_id=:'a';
SET CONSTRAINTS ALL IMMEDIATE;
SELECT 'copy heads', count(*) FROM deck_head_item WHERE deck_id=:'b';
-- Copy edits one material: a new lineage revision whose parent is A's revision; A later edits the same material.
SELECT member_key AS m, revision_id AS p, item_sequence AS ps FROM deck_head_item WHERE deck_id=:'a' LIMIT 1 \gset
SELECT count(*) AS same_seq_rows FROM item_revision WHERE reuse_scope_id=:'scope' AND member_key=:'m' AND item_sequence=:ps+1;
-- Tombstone the source: the copy still reads every head through lineage rows.
UPDATE deck SET deleted_at=now() WHERE deck_id=:'a';
SELECT 'copy readable after source tombstone', count(*) FROM deck_head_item h JOIN item_revision r
  ON r.reuse_scope_id=h.reuse_scope_id AND r.member_key=h.member_key AND r.revision_id=h.revision_id WHERE h.deck_id=:'b';
-- Every object reachable from B's roots is held: root pinned, descendants have incoming edges.
WITH RECURSIVE reach(id) AS (SELECT :'mroot'::uuid UNION SELECT :'xroot'::uuid
  UNION SELECT e.child_id FROM storage_edge e JOIN reach ON e.parent_id=reach.id AND e.reuse_scope_id=:'scope')
SELECT 'reachable from copy', count(*),
  count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM storage_edge e WHERE e.reuse_scope_id=:'scope' AND e.child_id=reach.id)
                     AND NOT EXISTS (SELECT 1 FROM storage_pin p WHERE p.reuse_scope_id=:'scope' AND p.root_id=reach.id)) AS unheld
FROM reach;
