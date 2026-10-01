#!/usr/bin/env python3
"""Bounded smoke for the persistent local HTTPS composition; uses stdlib only."""

import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import ssl
from http.cookies import SimpleCookie
from urllib.parse import parse_qs, urlencode, urlsplit
import uuid


LEGACY_ACCOUNT_KEYS = {"email", "login", "password", "deckId", "captureId"}
ACCOUNT_KEYS = LEGACY_ACCOUNT_KEYS | {
    "schemaVersion", "studyMemberKey", "studyItemRevisionId", "studyAnswerNodeId", "studyExerciseId",
    "mechanics",
}
ACCOUNT_SCHEMA = 4
# FREE_RESPONSE is the primary Study fixture; these cover the remaining canonical #266 mechanics.
ADDITIONAL_MECHANICS = ("SELF_CHECK", "CLOZE", "CHOICE", "MATCH")
MECHANIC_STATE_KEYS = {"deckId", "memberKey", "itemRevisionId", "answerNodeId", "distractorNodeId", "exerciseId",
                       "ids"}
PRIVATE_PRESENTATION_KEYS = {"answerKey", "accepted", "correctOptionIds", "pairs", "bindings", "reference",
                             "title", "transcript"}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class Client:
    def __init__(self, origin, context):
        parsed = urlsplit(origin)
        require(parsed.scheme == "https" and parsed.hostname == "localhost" and parsed.port, "invalid local origin")
        self.host = parsed.hostname
        self.port = parsed.port
        self.context = context
        self.cookies = {}

    def request(self, method, path, payload=None, bearer=None, form=False, csrf=False, headers=None):
        request_headers = dict(headers or {})
        if self.cookies:
            request_headers["Cookie"] = "; ".join(f"{key}={value}" for key, value in self.cookies.items())
        if bearer:
            request_headers["Authorization"] = "Bearer " + bearer
        if csrf:
            status, _, value = self.request("GET", "/api/accounts/csrf")
            require(status == 200 and isinstance(value, dict), "Identity CSRF endpoint unavailable")
            request_headers[value["headerName"]] = value["token"]
            request_headers["Cookie"] = "; ".join(f"{key}={value}" for key, value in self.cookies.items())
        body = None
        if payload is not None:
            body = urlencode(payload) if form else json.dumps(payload, separators=(",", ":"))
            request_headers["Content-Type"] = (
                "application/x-www-form-urlencoded" if form else "application/json"
            )
        connection = http.client.HTTPSConnection(self.host, self.port, context=self.context, timeout=10)
        try:
            connection.request(method, path, body, request_headers)
            response = connection.getresponse()
            raw = response.read(1_048_577)
            require(len(raw) <= 1_048_576, "local response exceeded smoke bound")
            response_headers = response.getheaders()
            for name, value in response_headers:
                if name.lower() == "set-cookie":
                    parsed_cookie = SimpleCookie(value)
                    for key, morsel in parsed_cookie.items():
                        if morsel["max-age"] == "0":
                            self.cookies.pop(key, None)
                        else:
                            self.cookies[key] = morsel.value
            try:
                decoded = json.loads(raw) if raw else None
            except (UnicodeDecodeError, ValueError):
                decoded = raw.decode("utf-8", errors="replace")
            return response.status, {key.lower(): value for key, value in response_headers}, decoded
        finally:
            connection.close()


def load_or_create_account(path):
    if path.exists():
        value = json.loads(path.read_text())
        version = value.get("schemaVersion")
        if set(value) == LEGACY_ACCOUNT_KEYS or version in (2, 3):
            # Exercise fixtures from earlier schemas used retired mechanics; keep only credentials and
            # the authoring Deck/Capture identities. #266 itself requires a fresh local database.
            value = {key: value[key] for key in LEGACY_ACCOUNT_KEYS}
            value.update({
                "schemaVersion": ACCOUNT_SCHEMA, "studyMemberKey": None, "studyItemRevisionId": None,
                "studyAnswerNodeId": None, "studyExerciseId": None, "mechanics": {},
            })
        require(set(value) == ACCOUNT_KEYS and value["schemaVersion"] == ACCOUNT_SCHEMA,
                "invalid smoke account state")
        mechanics = value["mechanics"]
        require(isinstance(mechanics, dict) and set(mechanics) <= set(ADDITIONAL_MECHANICS),
                "invalid mechanic fixture state")
        for mechanic, fixture in mechanics.items():
            require(isinstance(fixture, dict) and set(fixture) == MECHANIC_STATE_KEYS,
                    f"invalid {mechanic} fixture state")
            require(all(item is None or isinstance(item, (str, dict)) for item in fixture.values()),
                    f"invalid {mechanic} fixture identity")
        fixture_values = [value[key] for key in (
            "studyMemberKey", "studyItemRevisionId", "studyAnswerNodeId"
        )]
        require(all(item is None for item in fixture_values) or all(isinstance(item, str) for item in fixture_values),
                "partial Study material state")
        require(value["studyExerciseId"] is None or all(isinstance(item, str) for item in fixture_values),
                "Study exercise state has no material")
        return value, False
    suffix = secrets.token_hex(8)
    return {
        "schemaVersion": ACCOUNT_SCHEMA,
        "email": f"local-smoke-{suffix}@example.invalid",
        "login": f"local_smoke_{suffix}",
        "password": secrets.token_urlsafe(32),
        "deckId": None,
        "captureId": None,
        "studyMemberKey": None,
        "studyItemRevisionId": None,
        "studyAnswerNodeId": None,
        "studyExerciseId": None,
        "mechanics": {},
    }, True


def persist_account(path, account):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(account, separators=(",", ":")))
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def token(identity, redirect, account):
    status, _, _ = identity.request("POST", "/api/accounts/login", {
        "login": account["login"], "password": account["password"]
    }, csrf=True)
    require(status == 200, "persistent local smoke account login failed; reset local data and smoke state together")
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    state = secrets.token_urlsafe(16)
    query = urlencode({
        "response_type": "code", "client_id": "mnema-web", "redirect_uri": redirect,
        "scope": "openid learning.read learning.write", "state": state,
        "code_challenge": challenge, "code_challenge_method": "S256",
    })
    status, headers, _ = identity.request("GET", "/oauth2/authorize?" + query)
    require(status == 302, "PKCE authorization failed")
    callback = urlsplit(headers.get("location", ""))
    values = parse_qs(callback.query)
    require(callback.scheme == "https" and callback.netloc == urlsplit(redirect).netloc,
            "PKCE callback escaped the local frontend origin")
    require(values.get("state") == [state] and len(values.get("code", [])) == 1, "invalid PKCE callback")
    exchange = Client(f"https://localhost:{identity.port}", identity.context)
    status, _, result = exchange.request("POST", "/oauth2/token", {
        "grant_type": "authorization_code", "client_id": "mnema-web", "redirect_uri": redirect,
        "code": values["code"][0], "code_verifier": verifier,
    }, form=True)
    require(status == 200 and isinstance(result, dict) and result.get("access_token"), "PKCE token exchange failed")
    return result["access_token"]


def require_private(headers, label):
    cache = {part.strip().lower() for part in headers.get("cache-control", "").split(",")}
    require({"private", "no-store"}.issubset(cache), f"{label} private cache boundary missing")


def deck_head(web, access, deck_id):
    status, headers, deck = web.request("GET", f"/api/decks/{deck_id}", bearer=access)
    require(status == 200 and isinstance(deck, dict), "persistent smoke Deck did not survive restart")
    require_private(headers, "Deck")
    require(deck.get("deckId") == deck_id and isinstance(deck.get("revisionId"), str)
            and isinstance(deck.get("rowVersion"), str), "invalid Deck head")
    require(headers.get("etag") == f'"{deck["rowVersion"]}"', "Deck ETag mismatch")
    return deck


def study_document(answer_node, distractor_node=None):
    paragraphs = [{
        "id": answer_node, "type": "paragraph", "version": 1, "attrs": {},
        "content": [{
            "id": str(uuid.uuid4()), "type": "text", "version": 1,
            "attrs": {"text": "memory", "marks": []}, "content": [],
        }],
    }]
    if distractor_node is not None:
        paragraphs.append({
            "id": distractor_node, "type": "paragraph", "version": 1, "attrs": {},
            "content": [{
                "id": str(uuid.uuid4()), "type": "text", "version": 1,
                "attrs": {"text": "forgetting", "marks": []}, "content": [],
            }],
        })
    return {
        "formatVersion": 1,
        "root": {
            "id": str(uuid.uuid4()), "type": "doc", "version": 1, "attrs": {},
            "content": paragraphs,
        },
    }


def text_block(text):
    return {"kind": "TEXT", "text": text}


def material_block(member, revision, node):
    return {"kind": "MATERIAL", "memberKey": member, "itemRevisionId": revision, "nodeId": node}


def text_key(*accepted):
    return {"kind": "TEXT", "accepted": list(accepted), "normalization": ["UNICODE_NFC", "TRIM", "CASE_FOLD"],
            "matchingMode": "STRICT"}


def mechanic_exercise(mechanic, member, revision, answer_node, distractor_node, ids):
    """Builds one text-only #266 exercise; media combinations are covered by service tests and the browser run."""
    subject = {"memberKey": member, "itemRevisionId": revision}
    if mechanic == "SELF_CHECK":
        content = {"prompt": [text_block("Recall the retained smoke concept")],
                   "reference": [material_block(member, revision, answer_node)]}
        key, evaluator = {"kind": "SELF_REPORT"}, "self-check"
    elif mechanic == "FREE_RESPONSE":
        content = {"prompt": [text_block("Type the retained smoke answer")], "reference": [],
                   "responseInput": "TEXT"}
        key, evaluator = text_key("memory", "long-term memory"), "deterministic-text"
    elif mechanic == "CLOZE":
        first, second = ids["blanks"]
        content = {"prompt": [text_block("Fill both blanks")], "passage": [
            text_block("Working "),
            {"kind": "BLANK", "blankId": first, "size": {"mode": "ANSWER_LENGTH"}, "firstLetterHint": True},
            text_block(" is short;\nlong-term "),
            {"kind": "BLANK", "blankId": second, "size": {"mode": "FIXED", "length": 8}, "firstLetterHint": False},
            text_block(" is durable."),
        ]}
        key = {"kind": "CLOZE", "blanks": [
            {"blankId": blank, "accepted": ["memory"], "normalization": ["UNICODE_NFC", "TRIM", "CASE_FOLD"],
             "matchingMode": "STRICT"} for blank in (first, second)
        ]}
        evaluator = "deterministic-cloze"
    elif mechanic == "CHOICE":
        memory, forgetting, recall = ids["options"]
        content = {"prompt": [text_block("Which words name retention?")], "selectionMode": "MULTIPLE", "options": [
            {"optionId": memory, "blocks": [material_block(member, revision, answer_node)]},
            {"optionId": forgetting, "blocks": [material_block(member, revision, distractor_node)]},
            {"optionId": recall, "blocks": [text_block("recall")]},
        ]}
        key, evaluator = {"kind": "CHOICE", "correctOptionIds": [memory, recall]}, "deterministic-choice"
    elif mechanic == "MATCH":
        left, right = ids["left"], ids["right"]
        content = {"prompt": [text_block("Match the translations")],
                   "left": [{"itemId": left[0], "blocks": [text_block("der Hund")]},
                            {"itemId": left[1], "blocks": [text_block("die Katze")]}],
                   "right": [{"itemId": right[0], "blocks": [text_block("dog")]},
                             {"itemId": right[1], "blocks": [material_block(member, revision, answer_node)]}]}
        key = {"kind": "MATCH", "pairs": [{"leftId": left[0], "rightId": right[0]},
                                          {"leftId": left[1], "rightId": right[1]}]}
        evaluator = "deterministic-match"
    else:
        raise AssertionError(f"unknown mechanic {mechanic}")
    return {"type": mechanic, "schemaVersion": 2, "enabled": True, "subject": subject, "content": content,
            "answerKey": key, "evaluatorPolicy": {"id": evaluator, "version": "1"}}


def mechanic_ids(mechanic):
    if mechanic == "CLOZE":
        return {"blanks": [str(uuid.uuid4()) for _ in range(2)]}
    if mechanic == "CHOICE":
        return {"options": [str(uuid.uuid4()) for _ in range(3)]}
    if mechanic == "MATCH":
        return {"left": [str(uuid.uuid4()) for _ in range(2)], "right": [str(uuid.uuid4()) for _ in range(2)]}
    return {}


def publish_exercise(web, access, deck_id, title, exercise, label):
    deck = deck_head(web, access, deck_id)
    status, headers, acknowledgement = web.request(
        "POST", f"/api/decks/{deck_id}/exercises", {
            "commandId": str(uuid.uuid4()), "expectedDeckRevisionId": deck["revisionId"],
            "objective": {"operation": "create", "title": title}, "exercise": exercise,
        }, bearer=access, headers={"If-Match": f'"{deck["rowVersion"]}"'},
    )
    require(status == 201 and isinstance(acknowledgement, dict) and acknowledgement.get("enabled") is True,
            f"{label} exercise publication failed: {status}")
    require_private(headers, f"{label} exercise")
    return acknowledgement["exerciseId"]


def read_exercise(web, access, deck_id, exercise_id, mechanic):
    status, headers, exercise = web.request("GET", f"/api/decks/{deck_id}/exercises/{exercise_id}", bearer=access)
    require(status == 200 and isinstance(exercise, dict) and exercise.get("type") == mechanic
            and exercise.get("schemaVersion") == 2 and exercise.get("enabled") is True
            and isinstance(exercise.get("answerKey"), dict) and isinstance(exercise.get("content"), dict),
            f"{mechanic} exercise did not survive restart")
    require_private(headers, f"{mechanic} exercise")
    return exercise


def provision_additional_mechanic(web, access, account, state_file, mechanic):
    fixtures = account["mechanics"]
    fixture = fixtures.setdefault(mechanic, dict.fromkeys(MECHANIC_STATE_KEYS))
    if fixture["deckId"] is None:
        status, _, result = web.request("POST", "/api/decks", {
            "commandId": str(uuid.uuid4()),
            "metadata": {"title": f"{mechanic} smoke", "description": "Persistent Study acceptance fixture"},
        }, bearer=access)
        require(status == 201 and isinstance(result, dict), f"{mechanic} Deck create failed")
        fixture["deckId"] = result["deck"]["deckId"]
        persist_account(state_file, account)
    deck_id = fixture["deckId"]
    if fixture["memberKey"] is None:
        deck = deck_head(web, access, deck_id)
        answer_node = str(uuid.uuid4())
        distractor_node = str(uuid.uuid4())
        status, headers, acknowledgement = web.request(
            "POST", f"/api/decks/{deck_id}/items", {
                "commandId": str(uuid.uuid4()), "expectedDeckRevisionId": deck["revisionId"],
                "document": study_document(answer_node, distractor_node),
            }, bearer=access, headers={"If-Match": f'"{deck["rowVersion"]}"'},
        )
        require(status == 201 and isinstance(acknowledgement, dict), f"{mechanic} material publication failed")
        require_private(headers, f"{mechanic} material")
        changes = acknowledgement.get("changes")
        require(isinstance(changes, list) and len(changes) == 1, f"invalid {mechanic} material acknowledgement")
        fixture.update({
            "memberKey": changes[0]["memberKey"], "itemRevisionId": changes[0]["itemRevisionId"],
            "answerNodeId": answer_node, "distractorNodeId": distractor_node,
        })
        persist_account(state_file, account)
    status, headers, material = web.request(
        "GET", f"/api/decks/{deck_id}/items/{fixture['memberKey']}?revisionId={fixture['itemRevisionId']}",
        bearer=access,
    )
    require(status == 200 and isinstance(material, dict), f"{mechanic} material did not survive restart")
    require_private(headers, f"{mechanic} material")
    if fixture["exerciseId"] is None:
        fixture["ids"] = mechanic_ids(mechanic)
        exercise = mechanic_exercise(mechanic, fixture["memberKey"], fixture["itemRevisionId"],
                                     fixture["answerNodeId"], fixture["distractorNodeId"], fixture["ids"])
        fixture["exerciseId"] = publish_exercise(web, access, deck_id, f"{mechanic} smoke objective",
                                                 exercise, mechanic)
        persist_account(state_file, account)
    read_exercise(web, access, deck_id, fixture["exerciseId"], mechanic)
    return fixture


def capability_smoke(web, access, account):
    status, headers, capabilities = web.request("GET", "/api/capabilities", bearer=access)
    require(status == 200 and capabilities == {
        "aiAssessment": {"available": False, "reason": "DISABLED"},
        "speechToText": {"available": False, "reason": "DISABLED"},
    }, f"AI capabilities must default to disabled: {capabilities}")
    require_private(headers, "capabilities")
    member, revision = account["studyMemberKey"], account["studyItemRevisionId"]
    base = mechanic_exercise("FREE_RESPONSE", member, revision, account["studyAnswerNodeId"], None, {})
    ai = json.loads(json.dumps(base))
    ai["evaluatorPolicy"] = {"id": "ai-semantic", "version": "1", "rubric": {
        "referenceAnswer": "Memory retains learned information.",
        "criteria": [{"criterionId": str(uuid.uuid4()), "description": "Mentions retention", "critical": True}],
        "levels": [{"level": "COMPLETE", "description": "All criteria"},
                   {"level": "PARTIAL", "description": "Critical criteria only"},
                   {"level": "INSUFFICIENT", "description": "A critical criterion is missing"}],
    }}
    speech = json.loads(json.dumps(base))
    speech["content"]["responseInput"] = "TEXT_OR_SPEECH"
    legacy = json.loads(json.dumps(base))
    legacy["type"] = "TYPED"
    for label, exercise, expected, code in (("ai-semantic", ai, 409, "CAPABILITY_UNAVAILABLE"),
                                            ("speech input", speech, 409, "CAPABILITY_UNAVAILABLE"),
                                            ("retired type", legacy, 400, "INVALID_REQUEST")):
        deck = deck_head(web, access, account["deckId"])
        status, _, problem = web.request("POST", f"/api/decks/{account['deckId']}/exercises", {
            "commandId": str(uuid.uuid4()), "expectedDeckRevisionId": deck["revisionId"],
            "objective": {"operation": "create", "title": "Rejected capability probe"}, "exercise": exercise,
        }, bearer=access, headers={"If-Match": f'"{deck["rowVersion"]}"'})
        require(status == expected and isinstance(problem, dict) and problem.get("code") == code,
                f"{label} publication must be rejected with {code}: {status}")
        require(deck_head(web, access, account["deckId"])["rowVersion"] == deck["rowVersion"],
                f"rejected {label} publication advanced the Deck")
    return {"aiAssessment": "DISABLED", "speechToText": "DISABLED", "bypassRejected": True}


def provision_study_fixture(web, access, account, state_file):
    deck_id = account["deckId"]
    member = account["studyMemberKey"]
    item_revision = account["studyItemRevisionId"]
    answer_node = account["studyAnswerNodeId"]
    if member is None:
        answer_node = str(uuid.uuid4())
        deck = deck_head(web, access, deck_id)
        status, headers, acknowledgement = web.request(
            "POST", f"/api/decks/{deck_id}/items", {
                "commandId": str(uuid.uuid4()), "expectedDeckRevisionId": deck["revisionId"],
                "document": study_document(answer_node),
            }, bearer=access, headers={"If-Match": f'"{deck["rowVersion"]}"'}
        )
        require(status == 201 and isinstance(acknowledgement, dict), "Study material publication failed")
        require_private(headers, "Study material")
        changes = acknowledgement.get("changes")
        require(isinstance(changes, list) and len(changes) == 1, "invalid Study material acknowledgement")
        member = changes[0].get("memberKey")
        item_revision = changes[0].get("itemRevisionId")
        require(isinstance(member, str) and isinstance(item_revision, str), "missing Study material identity")
        account.update({
            "studyMemberKey": member, "studyItemRevisionId": item_revision,
            "studyAnswerNodeId": answer_node,
        })
        persist_account(state_file, account)
    status, headers, material = web.request(
        "GET", f"/api/decks/{deck_id}/items/{member}?revisionId={item_revision}", bearer=access
    )
    require(status == 200 and isinstance(material, dict), "persistent Study material did not survive restart")
    require_private(headers, "Study material")
    require(material.get("memberKey") == member and material.get("itemRevisionId") == item_revision,
            "Study material identity changed")

    exercise_id = account["studyExerciseId"]
    if exercise_id is None:
        exercise = mechanic_exercise("FREE_RESPONSE", member, item_revision, answer_node, None, {})
        exercise_id = publish_exercise(web, access, deck_id, "Retained smoke answer", exercise, "Study")
        account["studyExerciseId"] = exercise_id
        persist_account(state_file, account)
    exercise = read_exercise(web, access, deck_id, exercise_id, "FREE_RESPONSE")
    require(exercise.get("exerciseId") == exercise_id, "Study exercise identity changed")
    return member


def resolve_session(web, access, deck_id, status, session):
    require(status in (201, 202) and isinstance(session, dict), "Study session start failed")
    for _ in range(5):
        if session.get("status") != "PREPARING":
            return session
        session_id = session.get("sessionId")
        require(isinstance(session_id, str), "PREPARING Study session has no identity")
        status, headers, session = web.request(
            "GET", f"/api/decks/{deck_id}/study-sessions/{session_id}", bearer=access
        )
        require(status == 200 and isinstance(session, dict), "Study preparation polling failed")
        require_private(headers, "Study session")
    raise AssertionError("Study preparation exceeded bounded smoke polling")


def start_session(web, access, deck_id, mode, source=None):
    payload = {"commandId": str(uuid.uuid4()), "mode": mode, "budget": {"maxPresentations": 20}}
    if mode == "SCHEDULED":
        payload["budget"]["maxNewObjectives"] = 5
    elif mode == "REPLAY":
        payload["sourceSessionId"] = source
    elif mode == "PRACTICE":
        payload.update({"includeNew": False, "order": "WEAKEST_FIRST"})
    status, headers, session = web.request(
        "POST", f"/api/decks/{deck_id}/study-sessions", payload, bearer=access
    )
    require_private(headers, "Study session")
    session = resolve_session(web, access, deck_id, status, session)
    require(session.get("mode") == mode, "Study session mode mismatch")
    return session


def private_keys(value, path=""):
    """Learner presentations must not contain answer keys, author media labels or bindings."""
    found = []
    if isinstance(value, dict):
        for key, child in value.items():
            allowed_reference = key == "reference" and path.endswith("SELF_CHECK.content")
            if key in PRIVATE_PRESENTATION_KEYS and not allowed_reference:
                found.append(f"{path}.{key}")
            found.extend(private_keys(child, f"{path}.{key}"))
    elif isinstance(value, list):
        for child in value:
            found.extend(private_keys(child, path))
    return found


def presentation(session, mode, exercise_type="FREE_RESPONSE"):
    values = session.get("presentations")
    require(session.get("status") == "ACTIVE" and isinstance(values, list) and len(values) == 1,
            f"{mode} Study session did not issue one presentation")
    value = values[0]
    require(value.get("type") == exercise_type and isinstance(value.get("presentationId"), str)
            and isinstance(value.get("nonce"), str) and isinstance(value.get("content"), dict),
            f"invalid {mode} presentation")
    leaked = private_keys({exercise_type: value})
    require(not leaked, f"{exercise_type} presentation leaked private fields: {leaked}")
    return value


def attempt_payload(value, answer="memory"):
    return {
        "attemptId": str(uuid.uuid4()), "presentationId": value["presentationId"], "nonce": value["nonce"],
        "response": {"kind": "TEXT", "text": answer}, "confidence": "KNEW", "durationMs": 1,
    }


def submit_attempt(web, access, deck_id, session_id, payload):
    return web.request(
        "POST", f"/api/decks/{deck_id}/study-sessions/{session_id}/attempts", payload, bearer=access
    )


def progress_snapshot(web, access, deck_id, member):
    status, headers, page = web.request(
        "GET", f"/api/decks/{deck_id}/study-progress?limit=100", bearer=access
    )
    require(status == 200 and isinstance(page, dict) and isinstance(page.get("items"), list),
            "Study progress read failed")
    require_private(headers, "Study progress")
    matches = [item for item in page["items"] if item.get("memberKey") == member]
    require(len(matches) == 1, "Study material is missing from progress")
    item = matches[0]
    fields = {"memberKey", "itemRevisionId", "state", "objectiveCoverage", "lastAssessedAt", "nextDue", "title"}
    require(set(item) == fields, "invalid Study progress shape")
    return item


def require_feedback_only(outcome, mode):
    require(isinstance(outcome, dict) and outcome.get("mode") == mode and outcome.get("status") == "ASSESSED",
            f"{mode} attempt was not assessed")
    require(outcome.get("canonicalEffects") is False and outcome.get("evidence") is None
            and outcome.get("transition") is None, f"{mode} attempt claimed canonical effects")


def restart_material(web, access, deck_id, member):
    status, headers, result = web.request("POST", f"/api/decks/{deck_id}/study-restarts", {
        "commandId": str(uuid.uuid4()), "memberKeys": [member],
    }, bearer=access)
    require(status == 200 and isinstance(result, dict) and result.get("objectiveCount") == 1,
            "Study restart failed")
    require_private(headers, "Study restart")
    epochs = result.get("learningEpochs")
    require(isinstance(epochs, list) and len(epochs) == 1, "Study restart did not advance one objective")
    return result


def verify_complete(web, access, deck_id, session_id, mode):
    status, headers, session = web.request(
        "GET", f"/api/decks/{deck_id}/study-sessions/{session_id}", bearer=access
    )
    require(status == 200 and isinstance(session, dict) and session.get("status") == "COMPLETE",
            f"{mode} Study session did not complete")
    require_private(headers, "Study session")


def study_smoke(web, access, account, state_file):
    deck_id = account["deckId"]
    member = provision_study_fixture(web, access, account, state_file)
    anonymous_payload = {"commandId": str(uuid.uuid4()), "mode": "SCHEDULED",
                         "budget": {"maxPresentations": 1, "maxNewObjectives": 1}}
    anonymous, _, _ = web.request(
        "POST", f"/api/decks/{deck_id}/study-sessions", anonymous_payload
    )
    require(anonymous == 401, "anonymous Study session creation must fail closed")

    scheduled = start_session(web, access, deck_id, "SCHEDULED")
    recovered_empty = scheduled.get("status") == "EMPTY"
    if recovered_empty:
        restart_material(web, access, deck_id, member)
        scheduled = start_session(web, access, deck_id, "SCHEDULED")
    scheduled_presentation = presentation(scheduled, "SCHEDULED")
    scheduled_attempt = attempt_payload(scheduled_presentation)
    status, headers, scheduled_outcome = submit_attempt(
        web, access, deck_id, scheduled["sessionId"], scheduled_attempt
    )
    require(status == 200 and isinstance(scheduled_outcome, dict), "scheduled Study attempt failed")
    require_private(headers, "Study attempt")
    evidence = scheduled_outcome.get("evidence")
    transition = scheduled_outcome.get("transition")
    require(scheduled_outcome.get("mode") == "SCHEDULED" and scheduled_outcome.get("status") == "ASSESSED"
            and isinstance(evidence, dict) and evidence.get("result") == "CORRECT"
            and evidence.get("evidenceClass") == "HIGH" and isinstance(transition, dict),
            "scheduled Study attempt did not produce canonical evidence")
    retry_status, retry_headers, retry_outcome = submit_attempt(
        web, access, deck_id, scheduled["sessionId"], scheduled_attempt
    )
    require(retry_status == 200 and retry_headers.get("idempotency-replayed") == "true"
            and retry_outcome == scheduled_outcome, "scheduled Study attempt retry was not idempotent")
    verify_complete(web, access, deck_id, scheduled["sessionId"], "SCHEDULED")

    assessed = progress_snapshot(web, access, deck_id, member)
    coverage = assessed.get("objectiveCoverage")
    require(assessed.get("state") == "ON_TRACK" and isinstance(coverage, dict)
            and coverage.get("assessed") == 1 and assessed.get("lastAssessedAt") is not None,
            "scheduled Study progress was not observed")
    status, headers, sources = web.request(
        "GET", f"/api/decks/{deck_id}/study-sessions/replay-sources", bearer=access
    )
    require(status == 200 and isinstance(sources, dict) and isinstance(sources.get("items"), list),
            "Study replay sources read failed")
    require_private(headers, "Study replay sources")
    require(any(item.get("sessionId") == scheduled["sessionId"] for item in sources["items"]),
            "completed scheduled session is not replayable")

    restart = restart_material(web, access, deck_id, member)
    restarted = progress_snapshot(web, access, deck_id, member)
    require(restarted.get("state") == "DUE" and restarted.get("lastAssessedAt") is None,
            "Study restart did not reset current progress")

    replay = start_session(web, access, deck_id, "REPLAY", scheduled["sessionId"])
    replay_payload = attempt_payload(presentation(replay, "REPLAY"))
    status, headers, replay_outcome = submit_attempt(web, access, deck_id, replay["sessionId"], replay_payload)
    require(status == 200, "Study replay attempt failed")
    require_private(headers, "Study replay attempt")
    require_feedback_only(replay_outcome, "REPLAY")
    verify_complete(web, access, deck_id, replay["sessionId"], "REPLAY")
    require(progress_snapshot(web, access, deck_id, member) == restarted,
            "Study replay changed canonical progress")

    practice = start_session(web, access, deck_id, "PRACTICE")
    practice_payload = attempt_payload(presentation(practice, "PRACTICE"), "wrong")
    status, headers, practice_outcome = submit_attempt(
        web, access, deck_id, practice["sessionId"], practice_payload
    )
    require(status == 200, "Study practice attempt failed")
    require_private(headers, "Study practice attempt")
    require_feedback_only(practice_outcome, "PRACTICE")
    verify_complete(web, access, deck_id, practice["sessionId"], "PRACTICE")
    require(progress_snapshot(web, access, deck_id, member) == restarted,
            "Study practice changed canonical progress")
    return {
        "scheduled": "ASSESSED", "progress": "observed",
        "restartEpoch": restart["learningEpochs"][0]["learningEpoch"],
        "replayCanonicalEffects": False, "practiceCanonicalEffects": False,
        "emptySessionRecovered": recovered_empty,
    }


def mechanic_response(web, access, deck_id, session_id, mechanic, shown, ids):
    """Returns the response plus mechanic-specific server interactions performed before submit."""
    base = f"/api/decks/{deck_id}/study-sessions/{session_id}"
    content = shown["content"]
    if mechanic == "SELF_CHECK":
        require(content.get("reference") == [text_block("memory")], "self-check reference was not resolved")
        return {"kind": "SELF_CHECK", "rating": "FULL"}
    if mechanic == "CLOZE":
        blanks = [part for part in content.get("passage", []) if part.get("kind") == "BLANK"]
        require([part["blankId"] for part in blanks] == ids["blanks"], "cloze blanks were not pinned")
        require(blanks[0]["size"] == {"mode": "ANSWER_LENGTH", "length": 6}, "cloze answer length mismatch")
        hint_path = f"{base}/presentations/{shown['presentationId']}/hints"
        hints = []
        for _ in range(2):
            status, headers, hint = web.request("POST", hint_path, {"nonce": shown["nonce"],
                                                                   "blankId": ids["blanks"][0]}, bearer=access)
            require(status == 200 and hint == {"presentationId": shown["presentationId"],
                                               "blankId": ids["blanks"][0], "firstLetter": "m"},
                    f"cloze first-letter hint failed: {status} {hint}")
            require_private(headers, "cloze hint")
            hints.append(hint)
        status, _, _ = web.request("POST", hint_path, {"nonce": shown["nonce"], "blankId": ids["blanks"][1]},
                                   bearer=access)
        require(status == 400, "hint must be refused for a blank without first-letter hints")
        return {"kind": "CLOZE", "blanks": [{"blankId": blank, "text": " Memory "} for blank in ids["blanks"]]}
    if mechanic == "CHOICE":
        options = content.get("options")
        require(isinstance(options, list) and [option["optionId"] for option in options] == ids["options"]
                and options[0]["blocks"] == [text_block("memory")], "choice options were not resolved")
        require(content.get("selectionMode") == "MULTIPLE", "choice selection mode missing")
        return {"kind": "CHOICE", "optionIds": [ids["options"][2], ids["options"][0]]}
    if mechanic == "MATCH":
        left = [item["itemId"] for item in content.get("left", [])]
        right = [item["itemId"] for item in content.get("right", [])]
        require(sorted(left) == sorted(ids["left"]) and sorted(right) == sorted(ids["right"]),
                "match sides were not issued")
        wrong = {"presentationId": shown["presentationId"], "nonce": shown["nonce"],
                 "leftId": ids["left"][0], "rightId": ids["right"][1]}
        for _ in range(2):
            status, _, checked = web.request("POST", f"{base}/pair-checks", wrong, bearer=access)
            require(status == 200 and checked == {"correct": False}, "wrong pair check was not durable")
        return {"kind": "MATCH", "pairs": [{"leftId": l, "rightId": r} for l, r in zip(ids["left"], ids["right"])]}
    raise AssertionError(f"unknown mechanic {mechanic}")


def additional_mechanics_smoke(web, access, account, state_file):
    expected = {"SELF_CHECK": ("CORRECT", "LOW"), "CLOZE": ("CORRECT", "MEDIUM"),
                "CHOICE": ("CORRECT", "LOW"), "MATCH": ("PARTIAL", "LOW")}
    for mechanic in ADDITIONAL_MECHANICS:
        fixture = provision_additional_mechanic(web, access, account, state_file, mechanic)
        deck_id, member = fixture["deckId"], fixture["memberKey"]
        scheduled = start_session(web, access, deck_id, "SCHEDULED")
        if scheduled.get("status") == "EMPTY":
            restart_material(web, access, deck_id, member)
            scheduled = start_session(web, access, deck_id, "SCHEDULED")
        shown = presentation(scheduled, "SCHEDULED", mechanic)
        attempt = attempt_payload(shown)
        attempt["confidence"] = None
        attempt["response"] = mechanic_response(web, access, deck_id, scheduled["sessionId"], mechanic, shown,
                                                fixture["ids"])
        status, headers, outcome = submit_attempt(web, access, deck_id, scheduled["sessionId"], attempt)
        require(status == 200 and isinstance(outcome, dict), f"{mechanic} scheduled attempt failed")
        require_private(headers, f"{mechanic} Study attempt")
        evidence = outcome.get("evidence")
        result, evidence_class = expected[mechanic]
        require(outcome.get("status") == "ASSESSED"
                and isinstance(evidence, dict) and evidence.get("result") == result
                and evidence.get("evidenceClass") == evidence_class
                and isinstance(outcome.get("transition"), dict),
                f"{mechanic} unexpected evidence: status={outcome.get('status')}, "
                f"result={evidence.get('result') if isinstance(evidence, dict) else None}, "
                f"class={evidence.get('evidenceClass') if isinstance(evidence, dict) else None}, "
                f"canonical={outcome.get('canonicalEffects')}")
        if mechanic == "MATCH":
            require("PAIR_RETRY" in evidence.get("reasonCodes", []), "earlier wrong pair was erased")
        if mechanic == "CLOZE":
            blanks = outcome.get("feedback", {}).get("blanks", [])
            require([blank.get("hinted") for blank in blanks] == [True, False], "per-blank hint evidence missing")
        retry_status, retry_headers, retry = submit_attempt(web, access, deck_id, scheduled["sessionId"], attempt)
        require(retry_status == 200 and retry_headers.get("idempotency-replayed") == "true"
                and retry == outcome, f"{mechanic} retry changed the result")
        verify_complete(web, access, deck_id, scheduled["sessionId"], mechanic)
        observed = progress_snapshot(web, access, deck_id, member)
        require(observed.get("state") in ("LEARNING", "ON_TRACK")
                and observed.get("objectiveCoverage", {}).get("assessed") == 1
                and observed.get("lastAssessedAt") is not None,
                f"{mechanic} progress mismatch: state={observed.get('state')}, "
                f"coverage={observed.get('objectiveCoverage')}")
    return list(ADDITIONAL_MECHANICS)


def full_smoke(web, identity, state_file):
    account, fresh = load_or_create_account(state_file)
    if fresh:
        status, _, _ = identity.request("POST", "/api/accounts/register", {
            "email": account["email"], "loginName": account["login"], "password": account["password"],
            "profileUsername": account["login"],
        }, csrf=True)
        require(status == 201, "local smoke account registration failed")
    access = token(identity, f"https://localhost:{web.port}/auth/callback", account)
    status, headers, page = web.request("GET", "/api/decks?limit=20", bearer=access)
    require(status == 200 and isinstance(page, dict) and isinstance(page.get("items"), list),
            "same-origin authenticated Learning route failed")
    require(headers.get("cache-control") == "private, no-store", "private Learning cache boundary missing")
    if fresh:
        status, _, result = web.request("POST", "/api/decks", {
            "commandId": str(uuid.uuid4()),
            "metadata": {"title": "Local launcher smoke", "description": "Persistent HTTPS composition"},
        }, bearer=access)
        require(status == 201 and isinstance(result, dict), "local authoring Deck create failed")
        account["deckId"] = result["deck"]["deckId"]
        status, _, capture = web.request("POST", "/api/capture-notes", {
            "commandId": str(uuid.uuid4()), "deckId": account["deckId"],
            "source": "local-full-stack-smoke", "text": "Persistent authoring smoke",
        }, bearer=access)
        require(status == 201 and isinstance(capture, dict), "local Capture authoring failed")
        account["captureId"] = capture["capture"]["noteId"]
        persist_account(state_file, account)
    deck_head(web, access, account["deckId"])
    status, _, _ = web.request("GET", f"/api/capture-notes/{account['captureId']}", bearer=access)
    require(status == 200, "persistent Capture did not survive restart")
    study = study_smoke(web, access, account, state_file)
    study["mechanics"] = ["FREE_RESPONSE", *additional_mechanics_smoke(web, access, account, state_file)]
    study["capabilities"] = capability_smoke(web, access, account)
    persist_account(state_file, account)
    print(json.dumps({
        "state": "passed", "https": True, "pkce": True, "sameOriginLearning": True,
        "persistentAuthoring": True, "study": study,
        "accountReused": not fresh,
    }, sort_keys=True))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--web-origin", required=True)
    parser.add_argument("--identity-origin", required=True)
    parser.add_argument("--ca", type=Path, required=True)
    parser.add_argument("--state-file", type=Path, required=True)
    parser.add_argument("--readiness-only", action="store_true")
    args = parser.parse_args()
    context = ssl.create_default_context(cafile=str(args.ca))
    web, identity = Client(args.web_origin, context), Client(args.identity_origin, context)
    require(web.request("GET", "/api/actuator/health/readiness")[0] == 200, "Learning readiness failed")
    require(identity.request("GET", "/api/actuator/health/readiness")[0] == 200, "Identity readiness failed")
    status, _, body = web.request("GET", "/app-config.js")
    require(status == 200 and isinstance(body, str) and args.identity_origin in body,
            "frontend runtime Identity configuration missing")
    status, headers, _ = web.request("GET", "/api/decks")
    require(status == 401 and "set-cookie" not in headers, "anonymous same-origin Learning must fail closed")
    if args.readiness_only:
        print(json.dumps({"state": "ready", "https": True, "sameOriginLearning": True}, sort_keys=True))
    else:
        args.state_file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        full_smoke(web, identity, args.state_file)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, ssl.SSLError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(json.dumps({"state": "failed", "reason": str(error)}))
        raise SystemExit(1)
