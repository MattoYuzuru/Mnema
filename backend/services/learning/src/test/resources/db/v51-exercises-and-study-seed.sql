-- Rows as the V51 application left them: two decks in two lineages, exercises of every mechanic (one with two revisions and a
-- media reference), objectives with history, Study sessions on READY and PREPARING candidate generations, presentations,
-- one assessed attempt (tombstone, evidence, transition) and policy assignments. {{name}} is replaced by a stable UUID.
-- Foreign keys and triggers are off for the seed (session_replication_role = replica); V52 validates every new key against it.
SET session_replication_role = replica;

INSERT INTO app_learning.deck(deck_id,owner_id,reuse_scope_id,head_revision_id,row_version,created_at) VALUES
 ({{d1}},{{o1}},{{s1}},{{d1r9}},9,now()),({{d2}},{{o2}},{{s2}},{{d2r3}},3,now());
INSERT INTO app_learning.deck_revision(deck_id,revision_id,reuse_scope_id,owner_id,sequence,parent_revision_id,parent_sequence,command_id,
    title,description,created_at,members_root_id,exercises_root_id,members_pin_id,exercises_pin_id,member_count,exercise_count) VALUES
 ({{d1}},{{d1r0}},{{s1}},{{o1}},0,NULL,NULL,{{dc0}},'D1','',now(),{{mr1}},{{xr0}},{{mp1}},{{xp0}},0,0),
 ({{d1}},{{d1r1}},{{s1}},{{o1}},1,{{d1r0}},0,{{dc1}},'D1','',now(),{{mr1}},{{xr1}},{{mp1}},{{xp1}},2,0),
 ({{d1}},{{d1r2}},{{s1}},{{o1}},2,{{d1r1}},1,{{dc2}},'D1','',now(),{{mr1}},{{xr2}},{{mp1}},{{xp2}},2,1),
 ({{d1}},{{d1r3}},{{s1}},{{o1}},3,{{d1r2}},2,{{dc3}},'D1','',now(),{{mr1}},{{xr3}},{{mp1}},{{xp3}},2,2),
 ({{d1}},{{d1r4}},{{s1}},{{o1}},4,{{d1r3}},3,{{dc4}},'D1','',now(),{{mr1}},{{xr4}},{{mp1}},{{xp4}},2,3),
 ({{d1}},{{d1r5}},{{s1}},{{o1}},5,{{d1r4}},4,{{dc5}},'D1','',now(),{{mr1}},{{xr5}},{{mp1}},{{xp5}},2,4),
 ({{d1}},{{d1r6}},{{s1}},{{o1}},6,{{d1r5}},5,{{dc6}},'D1','',now(),{{mr1}},{{xr6}},{{mp1}},{{xp6}},2,5),
 ({{d1}},{{d1r7}},{{s1}},{{o1}},7,{{d1r6}},6,{{dc7}},'D1','',now(),{{mr1}},{{xr7}},{{mp1}},{{xp7}},2,6),
 ({{d1}},{{d1r8}},{{s1}},{{o1}},8,{{d1r7}},7,{{dc8}},'D1','',now(),{{mr1}},{{xr8}},{{mp1}},{{xp8}},2,7),
 ({{d1}},{{d1r9}},{{s1}},{{o1}},9,{{d1r8}},8,{{dc9}},'D1','',now(),{{mr1}},{{xr9}},{{mp1}},{{xp9}},2,7),
 ({{d2}},{{d2r0}},{{s2}},{{o2}},0,NULL,NULL,{{dc9b}},'D2','',now(),{{mr2}},{{xr9b}},{{mp2}},{{xp9b}},0,0),
 ({{d2}},{{d2r1}},{{s2}},{{o2}},1,{{d2r0}},0,{{dc10}},'D2','',now(),{{mr2}},{{xr10}},{{mp2}},{{xp10}},1,0),
 ({{d2}},{{d2r2}},{{s2}},{{o2}},2,{{d2r1}},1,{{dc11}},'D2','',now(),{{mr2}},{{xr11}},{{mp2}},{{xp11}},1,1),
 ({{d2}},{{d2r3}},{{s2}},{{o2}},3,{{d2r2}},2,{{dc12}},'D2','',now(),{{mr2}},{{xr12}},{{mp2}},{{xp12}},1,2);
INSERT INTO app_learning.learning_item(deck_id,member_key,owner_id,reuse_scope_id,created_at) VALUES
 ({{d1}},{{m1a}},{{o1}},{{s1}},now()),({{d1}},{{m1b}},{{o1}},{{s1}},now()),({{d2}},{{m2}},{{o2}},{{s2}},now());
INSERT INTO app_learning.item_revision(deck_id,member_key,revision_id,reuse_scope_id,owner_id,item_sequence,parent_revision_id,
    parent_item_sequence,deck_revision_id,deck_sequence,command_id,format_version,content_root_id,descriptor_root_id,created_at) VALUES
 ({{d1}},{{m1a}},{{ir1a0}},{{s1}},{{o1}},0,NULL,NULL,{{d1r1}},1,{{c1}},1,{{x1}},{{x2}},now()),
 ({{d1}},{{m1a}},{{ir1a1}},{{s1}},{{o1}},1,{{ir1a0}},0,{{d1r2}},2,{{c2}},1,{{x3}},{{x4}},now()),
 ({{d1}},{{m1b}},{{ir1b0}},{{s1}},{{o1}},0,NULL,NULL,{{d1r1}},1,{{c3}},1,{{x5}},{{x6}},now()),
 ({{d2}},{{m2}},{{ir2}},{{s2}},{{o2}},0,NULL,NULL,{{d2r1}},1,{{c4}},1,{{x7}},{{x8}},now());
INSERT INTO app_learning.deck_head_item(deck_id,reuse_scope_id,member_key,revision_id,item_sequence,updated_at) VALUES
 ({{d1}},{{s1}},{{m1a}},{{ir1a1}},1,now()),({{d1}},{{s1}},{{m1b}},{{ir1b0}},0,now()),({{d2}},{{s2}},{{m2}},{{ir2}},0,now());

-- objectives: ob1a has two revisions, head on the second
INSERT INTO app_learning.memory_objective(deck_id,objective_id,objective_key,member_key,owner_id,reuse_scope_id,created_at) VALUES
 ({{d1}},{{ob1a}},{{ok1a}},{{m1a}},{{o1}},{{s1}},now()),({{d1}},{{ob1b}},{{ok1b}},{{m1b}},{{o1}},{{s1}},now()),
 ({{d2}},{{ob2}},{{ok2}},{{m2}},{{o2}},{{s2}},now());
INSERT INTO app_learning.objective_revision(deck_id,objective_id,revision_id,objective_sequence,parent_revision_id,
    parent_objective_sequence,deck_revision_id,deck_sequence,command_id,descriptor,created_at) VALUES
 ({{d1}},{{ob1a}},{{obr1a0}},0,NULL,NULL,{{d1r2}},2,{{c5}},'{"schemaVersion":"1","title":"One"}',now()),
 ({{d1}},{{ob1a}},{{obr1a1}},1,{{obr1a0}},0,{{d1r9}},9,{{c6}},'{"schemaVersion":"1","title":"One, revised"}',now()),
 ({{d1}},{{ob1b}},{{obr1b0}},0,NULL,NULL,{{d1r3}},3,{{c7}},'{"schemaVersion":"1","title":"Two"}',now()),
 ({{d2}},{{ob2}},{{obr2}},0,NULL,NULL,{{d2r2}},2,{{c8}},'{"schemaVersion":"1","title":"Three"}',now());
INSERT INTO app_learning.objective_head(deck_id,objective_id,revision_id,objective_sequence,updated_at) VALUES
 ({{d1}},{{ob1a}},{{obr1a1}},1,now()),({{d1}},{{ob1b}},{{obr1b0}},0,now()),({{d2}},{{ob2}},{{obr2}},0,now());

-- exercises of deck 1: one per mechanic; e1 has a second revision with a media reference
INSERT INTO app_learning.exercise_definition(deck_id,exercise_id,owner_id,reuse_scope_id,created_at) VALUES
 ({{d1}},{{e1}},{{o1}},{{s1}},now()),({{d1}},{{e2}},{{o1}},{{s1}},now()),({{d1}},{{e3}},{{o1}},{{s1}},now()),
 ({{d1}},{{e4}},{{o1}},{{s1}},now()),({{d1}},{{e5}},{{o1}},{{s1}},now()),({{d1}},{{e6}},{{o1}},{{s1}},now()),
 ({{d1}},{{e7}},{{o1}},{{s1}},now()),({{d2}},{{f1}},{{o2}},{{s2}},now()),({{d2}},{{f2}},{{o2}},{{s2}},now()),
 ({{d2}},{{f3}},{{o2}},{{s2}},now());
INSERT INTO app_learning.exercise_revision(deck_id,exercise_id,revision_id,reuse_scope_id,exercise_sequence,parent_revision_id,
    parent_exercise_sequence,deck_revision_id,deck_sequence,command_id,exercise_type,schema_version,enabled,content,evaluator_policy,
    descriptor_root_id,created_at,answer_key) VALUES
 ({{d1}},{{e1}},{{er1a}},{{s1}},0,NULL,NULL,{{d1r2}},2,{{c10}},'SELF_CHECK',2,TRUE,'{"prompt":[{"kind":"TEXT","text":"q"}]}','{"id":"self-check"}',{{x10}},now(),'{"kind":"SELF_REPORT"}'),
 ({{d1}},{{e1}},{{er1b}},{{s1}},1,{{er1a}},0,{{d1r9}},9,{{c11}},'SELF_CHECK',2,TRUE,'{"prompt":[{"kind":"IMAGE","assetId":"{{asset}}","alt":"a"}]}','{"id":"self-check"}',{{x11}},now(),'{"kind":"SELF_REPORT"}'),
 ({{d1}},{{e2}},{{er2}},{{s1}},0,NULL,NULL,{{d1r3}},3,{{c12}},'FREE_RESPONSE',2,TRUE,'{"prompt":[{"kind":"TEXT","text":"q"}]}','{"id":"typed"}',{{x12}},now(),'{"kind":"TEXT"}'),
 ({{d1}},{{e3}},{{er3}},{{s1}},0,NULL,NULL,{{d1r4}},4,{{c13}},'CLOZE',2,TRUE,'{"prompt":[{"kind":"TEXT","text":"q"}]}','{"id":"cloze"}',{{x13}},now(),'{"kind":"CLOZE"}'),
 ({{d1}},{{e4}},{{er4}},{{s1}},0,NULL,NULL,{{d1r5}},5,{{c14}},'CHOICE',2,TRUE,'{"prompt":[{"kind":"TEXT","text":"q"}]}','{"id":"choice"}',{{x14}},now(),'{"kind":"CHOICE"}'),
 ({{d1}},{{e5}},{{er5}},{{s1}},0,NULL,NULL,{{d1r6}},6,{{c15}},'MATCH',2,TRUE,'{"prompt":[{"kind":"TEXT","text":"q"}]}','{"id":"match"}',{{x15}},now(),'{"kind":"MATCH"}'),
 ({{d1}},{{e6}},{{er6}},{{s1}},0,NULL,NULL,{{d1r7}},7,{{c16}},'ORDER',2,TRUE,'{"prompt":[{"kind":"TEXT","text":"q"}]}','{"id":"order"}',{{x16}},now(),'{"kind":"ORDER"}'),
 ({{d1}},{{e7}},{{er7}},{{s1}},0,NULL,NULL,{{d1r8}},8,{{c17}},'CATEGORIZE',2,TRUE,'{"prompt":[{"kind":"TEXT","text":"q"}]}','{"id":"categorize"}',{{x17}},now(),'{"kind":"CATEGORIZE"}'),
 ({{d2}},{{f1}},{{fr1}},{{s2}},0,NULL,NULL,{{d2r2}},2,{{c18}},'SELF_CHECK',2,TRUE,'{"prompt":[{"kind":"TEXT","text":"q"}]}','{"id":"self-check"}',{{x18}},now(),'{"kind":"SELF_REPORT"}'),
 ({{d2}},{{f2}},{{fr2}},{{s2}},0,NULL,NULL,{{d2r3}},3,{{c19}},'CHOICE',2,TRUE,'{"prompt":[{"kind":"TEXT","text":"q"}]}','{"id":"choice"}',{{x19}},now(),'{"kind":"CHOICE"}'),
 -- declares an asset that no reference row pins (readiness used to say yes for it, V52 says no); not on the roster
 ({{d2}},{{f3}},{{fr3}},{{s2}},0,NULL,NULL,{{d2r3}},3,{{c23}},'SELF_CHECK',2,TRUE,'{"prompt":[{"kind":"AUDIO","assetId":"{{asset2}}","title":"t"}]}','{"id":"self-check"}',{{x23}},now(),'{"kind":"SELF_REPORT"}');
INSERT INTO app_learning.exercise_content_binding(deck_id,exercise_id,exercise_revision_id,binding_id,binding_ordinal,role,member_key,
    item_revision_id,objective_id,objective_revision_id,node_ids) VALUES
 ({{d1}},{{e1}},{{er1a}},{{b1}},0,'ASSESSED',{{m1a}},{{ir1a0}},{{ob1a}},{{obr1a0}},'{}'),
 ({{d1}},{{e1}},{{er1b}},{{b2}},0,'ASSESSED',{{m1a}},{{ir1a1}},{{ob1a}},{{obr1a1}},'{}'),
 ({{d1}},{{e1}},{{er1b}},{{b3}},1,'CONTEXT',{{m1b}},{{ir1b0}},NULL,NULL,'{}'),
 ({{d1}},{{e2}},{{er2}},{{b4}},0,'ASSESSED',{{m1b}},{{ir1b0}},{{ob1b}},{{obr1b0}},'{}'),
 ({{d1}},{{e3}},{{er3}},{{b5}},0,'ASSESSED',{{m1a}},{{ir1a1}},{{ob1a}},{{obr1a1}},'{}'),
 ({{d1}},{{e4}},{{er4}},{{b6}},0,'ASSESSED',{{m1b}},{{ir1b0}},{{ob1b}},{{obr1b0}},'{}'),
 ({{d1}},{{e5}},{{er5}},{{b7}},0,'ASSESSED',{{m1a}},{{ir1a1}},{{ob1a}},{{obr1a1}},'{}'),
 ({{d1}},{{e6}},{{er6}},{{b8}},0,'ASSESSED',{{m1b}},{{ir1b0}},{{ob1b}},{{obr1b0}},'{}'),
 ({{d1}},{{e7}},{{er7}},{{b9}},0,'ASSESSED',{{m1a}},{{ir1a1}},{{ob1a}},{{obr1a1}},'{}'),
 ({{d2}},{{f1}},{{fr1}},{{b10}},0,'ASSESSED',{{m2}},{{ir2}},{{ob2}},{{obr2}},'{}'),
 ({{d2}},{{f2}},{{fr2}},{{b11}},0,'ASSESSED',{{m2}},{{ir2}},{{ob2}},{{obr2}},'{}');
INSERT INTO app_learning.exercise_media_ref(deck_id,exercise_id,exercise_revision_id,owner_id,asset_id,media_kind) VALUES
 ({{d1}},{{e1}},{{er1b}},{{o1}},{{asset}},'image');
INSERT INTO app_learning.deck_head_exercise(deck_id,exercise_id,revision_id,exercise_sequence,ordinal,updated_at) VALUES
 ({{d1}},{{e1}},{{er1b}},1,0,now()),({{d1}},{{e2}},{{er2}},0,1,now()),({{d1}},{{e3}},{{er3}},0,2,now()),({{d1}},{{e4}},{{er4}},0,3,now()),
 ({{d1}},{{e5}},{{er5}},0,4,now()),({{d1}},{{e6}},{{er6}},0,5,now()),({{d1}},{{e7}},{{er7}},0,6,now()),
 ({{d2}},{{f1}},{{fr1}},0,0,now()),({{d2}},{{f2}},{{fr2}},0,1,now());
INSERT INTO app_learning.deck_exercise_change(deck_id,deck_revision_id,deck_sequence,exercise_id,previous_revision_id,revision_id,ordinal) VALUES
 ({{d1}},{{d1r2}},2,{{e1}},NULL,{{er1a}},0),({{d1}},{{d1r3}},3,{{e2}},NULL,{{er2}},1),({{d1}},{{d1r4}},4,{{e3}},NULL,{{er3}},2),
 ({{d1}},{{d1r5}},5,{{e4}},NULL,{{er4}},3),({{d1}},{{d1r6}},6,{{e5}},NULL,{{er5}},4),({{d1}},{{d1r7}},7,{{e6}},NULL,{{er6}},5),
 ({{d1}},{{d1r8}},8,{{e7}},NULL,{{er7}},6),({{d1}},{{d1r9}},9,{{e1}},{{er1a}},{{er1b}},0),
 ({{d2}},{{d2r2}},2,{{f1}},NULL,{{fr1}},0),({{d2}},{{d2r3}},3,{{f2}},NULL,{{fr2}},1);
INSERT INTO app_learning.exercise_new_mark(deck_id,exercise_id,owner_id,marked_at) VALUES ({{d1}},{{e3}},{{o1}},now());

-- Study: deck 1 has a READY generation, one still PREPARING (3 of 7 scanned by exercise_id order, with a UUID cursor); deck 2 a READY one
INSERT INTO app_learning.study_candidate_generation(deck_id,generation_id,owner_id,deck_revision_id,deck_sequence,exercises_root_id,
    status,source_cursor,scanned_count,candidate_count,expected_exercise_count,row_version,created_at,ready_at) VALUES
 ({{d1}},{{g1}},{{o1}},{{d1r9}},9,{{root1}},'READY',{{e7}},7,7,7,3,now(),now()),
 ({{d1}},{{g1p}},{{o1}},{{d1r8}},8,{{root1p}},'PREPARING',{{e3}},3,3,7,1,now(),NULL),
 ({{d2}},{{g2}},{{o2}},{{d2r3}},3,{{root2}},'READY',{{f2}},2,2,2,2,now(),now());
INSERT INTO app_learning.study_candidate(generation_id,candidate_ordinal,deck_id,exercise_id,exercise_revision_id,objective_id,
    objective_revision_id,member_key) VALUES
 ({{g1}},0,{{d1}},{{e1}},{{er1b}},{{ob1a}},{{obr1a1}},{{m1a}}),({{g1}},1,{{d1}},{{e2}},{{er2}},{{ob1b}},{{obr1b0}},{{m1b}}),
 ({{g1}},2,{{d1}},{{e3}},{{er3}},{{ob1a}},{{obr1a1}},{{m1a}}),({{g1}},3,{{d1}},{{e4}},{{er4}},{{ob1b}},{{obr1b0}},{{m1b}}),
 ({{g1}},4,{{d1}},{{e5}},{{er5}},{{ob1a}},{{obr1a1}},{{m1a}}),({{g1}},5,{{d1}},{{e6}},{{er6}},{{ob1b}},{{obr1b0}},{{m1b}}),
 ({{g1}},6,{{d1}},{{e7}},{{er7}},{{ob1a}},{{obr1a1}},{{m1a}}),
 ({{g1p}},0,{{d1}},{{e1}},{{er1a}},{{ob1a}},{{obr1a0}},{{m1a}}),({{g1p}},1,{{d1}},{{e2}},{{er2}},{{ob1b}},{{obr1b0}},{{m1b}}),
 ({{g1p}},2,{{d1}},{{e3}},{{er3}},{{ob1a}},{{obr1a1}},{{m1a}}),
 ({{g2}},0,{{d2}},{{f1}},{{fr1}},{{ob2}},{{obr2}},{{m2}}),({{g2}},1,{{d2}},{{f2}},{{fr2}},{{ob2}},{{obr2}},{{m2}});

INSERT INTO app_learning.study_session(account_id,session_id,deck_id,command_id,mode,status,timezone,local_study_date,deck_revision_id,
    deck_sequence,exercise_generation_id,selection_policy_version,reducer_config_id,seed,budget,issued_count,batch_start,batch_size,
    scan_cursor,wrapped,include_new,practice_order,source_session_id,row_version,created_at,expires_at,completed_at,max_new_objectives,
    issued_new_objectives) VALUES
 ({{o1}},{{ss1}},{{d1}},{{c20}},'SCHEDULED','ACTIVE','UTC',current_date,{{d1r9}},9,{{g1}},'deck-due-new-v3',
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',7,10,2,0,2,2,FALSE,TRUE,NULL,NULL,0,now(),now()+interval '1 day',NULL,5,2),
 ({{o1}},{{ss1p}},{{d1}},{{c21}},'SCHEDULED','PREPARING','UTC',current_date,{{d1r8}},8,{{g1p}},'deck-due-new-v3',
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',9,10,0,0,0,0,FALSE,TRUE,NULL,NULL,0,now(),now()+interval '1 day',NULL,5,0),
 ({{o2}},{{ss2}},{{d2}},{{c22}},'SCHEDULED','ACTIVE','UTC',current_date,{{d2r3}},3,{{g2}},'deck-due-new-v3',
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',3,10,1,0,1,1,FALSE,TRUE,NULL,NULL,0,now(),now()+interval '1 day',NULL,5,1);
INSERT INTO app_learning.study_policy_assignment(account_id,deck_id,objective_id,reducer_config_id,assigned_at) VALUES
 ({{o1}},{{d1}},{{ob1a}},'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',now()),({{o1}},{{d1}},{{ob1b}},'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',now()),
 ({{o2}},{{d2}},{{ob2}},'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',now());
INSERT INTO app_learning.study_state(account_id,deck_id,objective_id,learning_epoch,level,correct_streak,lapse_count,last_assessed_at,
    next_due,reducer_config_id,transition_sequence,row_version,introduced_at,updated_at) VALUES
 ({{o1}},{{d1}},{{ob1a}},0,1,1,0,now(),now()+interval '2 days','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',1,1,now(),now()),
 ({{o1}},{{d1}},{{ob1b}},0,0,0,0,NULL,NULL,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',0,0,now(),now()),
 ({{o2}},{{d2}},{{ob2}},0,0,0,0,NULL,NULL,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',0,0,now(),now());
INSERT INTO app_learning.study_presentation(account_id,session_id,presentation_id,presentation_ordinal,nonce,deck_id,generation_id,
    candidate_ordinal,exercise_id,exercise_revision_id,exercise_type,objective_id,objective_revision_id,learning_epoch,content,evaluator,
    answer_key,issued_at,expires_at,reveal) VALUES
 ({{o1}},{{ss1}},{{p1}},0,'nonce-nonce-nonce-1',{{d1}},{{g1}},0,{{e1}},{{er1b}},'SELF_CHECK',{{ob1a}},{{obr1a1}},0,'{}','{}','{}',now(),now()+interval '1 day','{}'),
 ({{o1}},{{ss1}},{{p2}},1,'nonce-nonce-nonce-2',{{d1}},{{g1}},1,{{e2}},{{er2}},'FREE_RESPONSE',{{ob1b}},{{obr1b0}},0,'{}','{}','{}',now(),now()+interval '1 day','{}'),
 ({{o2}},{{ss2}},{{p3}},0,'nonce-nonce-nonce-3',{{d2}},{{g2}},0,{{f1}},{{fr1}},'SELF_CHECK',{{ob2}},{{obr2}},0,'{}','{}','{}',now(),now()+interval '1 day','{}');
INSERT INTO app_learning.study_exposure(account_id,session_id,presentation_id,deck_id,objective_id,learning_epoch,exposed_at) VALUES
 ({{o1}},{{ss1}},{{p1}},{{d1}},{{ob1a}},0,now()),({{o1}},{{ss1}},{{p2}},{{d1}},{{ob1b}},0,now());
INSERT INTO app_learning.study_attempt_tombstone(attempt_id,account_id,session_id,presentation_id,deck_id,payload_hash,mode,status,outcome,
    receipt_expires_at,submitted_at) VALUES
 ({{a1}},{{o1}},{{ss1}},{{p1}},{{d1}},decode(repeat('ab',32),'hex'),'SCHEDULED','ASSESSED','{}',NULL,now());
INSERT INTO app_learning.study_evidence(attempt_id,account_id,deck_id,objective_id,objective_revision_id,learning_epoch,result,
    evidence_class,reason_codes,evaluator_id,evaluator_version,hints_used,confidence,duration_ms,accepted_at) VALUES
 ({{a1}},{{o1}},{{d1}},{{ob1a}},{{obr1a1}},0,'CORRECT','HIGH','[]','self-check','1','[]',NULL,1000,now());
INSERT INTO app_learning.study_transition(account_id,deck_id,objective_id,learning_epoch,transition_sequence,attempt_id,before_level,
    after_level,before_correct_streak,after_correct_streak,before_lapse_count,after_lapse_count,accepted_at,next_due,reducer_id,
    reducer_version,reducer_config_id,config_hash) VALUES
 ({{o1}},{{d1}},{{ob1a}},0,1,{{a1}},0,1,0,1,0,0,now(),now()+interval '2 days','baseline','1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','hash');
