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
    "p0Mechanics",
}
ADDITIONAL_MECHANICS = ("SELF_CHECK", "CLOZE_SINGLE", "SINGLE_CHOICE")
MECHANIC_STATE_KEYS = {"deckId", "memberKey", "itemRevisionId", "answerNodeId", "distractorNodeId", "exerciseId"}


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
        if set(value) == LEGACY_ACCOUNT_KEYS:
            value.update({
                "schemaVersion": 2, "studyMemberKey": None, "studyItemRevisionId": None,
                "studyAnswerNodeId": None, "studyExerciseId": None,
            })
        if value.get("schemaVersion") == 2 and set(value) == ACCOUNT_KEYS - {"p0Mechanics"}:
            value["schemaVersion"] = 3
            value["p0Mechanics"] = {}
        require(set(value) == ACCOUNT_KEYS and value["schemaVersion"] == 3, "invalid smoke account state")
        mechanics = value["p0Mechanics"]
        require(isinstance(mechanics, dict) and set(mechanics) <= set(ADDITIONAL_MECHANICS),
                "invalid P0 mechanic state")
        for mechanic, fixture in mechanics.items():
            require(isinstance(fixture, dict) and set(fixture) == MECHANIC_STATE_KEYS,
                    f"invalid {mechanic} fixture state")
            require(all(item is None or isinstance(item, str) for item in fixture.values()),
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
        "schemaVersion": 3,
        "email": f"local-smoke-{suffix}@example.invalid",
        "login": f"local_smoke_{suffix}",
        "password": secrets.token_urlsafe(32),
        "deckId": None,
        "captureId": None,
        "studyMemberKey": None,
        "studyItemRevisionId": None,
        "studyAnswerNodeId": None,
        "studyExerciseId": None,
        "p0Mechanics": {},
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


def provision_additional_mechanic(web, access, account, state_file, mechanic):
    fixtures = account["p0Mechanics"]
    fixture = fixtures.setdefault(mechanic, dict.fromkeys(MECHANIC_STATE_KEYS))
    if fixture["deckId"] is None:
        status, _, result = web.request("POST", "/api/decks", {
            "commandId": str(uuid.uuid4()),
            "metadata": {"title": f"P0 {mechanic} smoke", "description": "Persistent Study acceptance fixture"},
        }, bearer=access)
        require(status == 201 and isinstance(result, dict), f"{mechanic} Deck create failed")
        fixture["deckId"] = result["deck"]["deckId"]
        persist_account(state_file, account)
    deck_id = fixture["deckId"]
    if fixture["memberKey"] is None:
        deck = deck_head(web, access, deck_id)
        answer_node = str(uuid.uuid4())
        distractor_node = str(uuid.uuid4()) if mechanic == "SINGLE_CHOICE" else None
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
        deck = deck_head(web, access, deck_id)
        member, revision, answer_node = (fixture[key] for key in ("memberKey", "itemRevisionId", "answerNodeId"))
        bindings = [{
            "bindingId": str(uuid.uuid4()), "role": "ASSESSED", "memberKey": member,
            "itemRevisionId": revision, "nodeIds": [answer_node],
            "display": {"kind": "NODE_TEXT"}, "ordinal": 0,
        }]
        if mechanic == "SINGLE_CHOICE":
            for ordinal, node in enumerate((answer_node, fixture["distractorNodeId"]), 1):
                bindings.append({
                    "bindingId": str(uuid.uuid4()), "role": "OPTION", "memberKey": member,
                    "itemRevisionId": revision, "nodeIds": [node],
                    "display": {"kind": "NODE_TEXT"}, "ordinal": ordinal,
                })
        evaluator = ("self-check" if mechanic == "SELF_CHECK" else
                     "deterministic-choice" if mechanic == "SINGLE_CHOICE" else "deterministic-text")
        status, headers, acknowledgement = web.request(
            "POST", f"/api/decks/{deck_id}/exercises", {
                "commandId": str(uuid.uuid4()), "expectedDeckRevisionId": deck["revisionId"],
                "objective": {
                    "operation": "create",
                    "answerContract": {"schemaVersion": 1,
                                       "normalization": ["UNICODE_NFC", "TRIM", "CASE_FOLD"],
                                       "accepted": ["memory"]},
                },
                "exercise": {
                    "type": mechanic, "schemaVersion": 1, "enabled": True,
                    "prompt": {"kind": "CUSTOM_TEXT", "text": f"P0 {mechanic} smoke prompt"},
                    "bindings": bindings,
                    "evaluatorPolicy": {"id": evaluator, "version": "1"},
                },
            }, bearer=access, headers={"If-Match": f'"{deck["rowVersion"]}"'},
        )
        require(status == 201 and isinstance(acknowledgement, dict), f"{mechanic} exercise publication failed")
        require_private(headers, f"{mechanic} exercise")
        fixture["exerciseId"] = acknowledgement["exerciseId"]
        persist_account(state_file, account)
    status, headers, exercise = web.request(
        "GET", f"/api/decks/{deck_id}/exercises/{fixture['exerciseId']}", bearer=access,
    )
    require(status == 200 and isinstance(exercise, dict) and exercise.get("type") == mechanic
            and exercise.get("enabled") is True, f"{mechanic} exercise did not survive restart")
    require_private(headers, f"{mechanic} exercise")
    return fixture


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
        deck = deck_head(web, access, deck_id)
        status, headers, acknowledgement = web.request(
            "POST", f"/api/decks/{deck_id}/exercises", {
                "commandId": str(uuid.uuid4()), "expectedDeckRevisionId": deck["revisionId"],
                "objective": {
                    "operation": "create",
                    "answerContract": {
                        "schemaVersion": 1, "normalization": ["UNICODE_NFC", "TRIM", "CASE_FOLD"],
                        "accepted": ["memory"],
                    },
                },
                "exercise": {
                    "type": "TYPED", "schemaVersion": 1, "enabled": True,
                    "prompt": {"kind": "CUSTOM_TEXT", "text": "Type the retained smoke answer"},
                    "bindings": [{
                        "bindingId": str(uuid.uuid4()), "role": "ASSESSED", "memberKey": member,
                        "itemRevisionId": item_revision, "nodeIds": [answer_node],
                        "display": {"kind": "NODE_TEXT"}, "ordinal": 0,
                    }],
                    "evaluatorPolicy": {"id": "deterministic-text", "version": "1"},
                },
            }, bearer=access, headers={"If-Match": f'"{deck["rowVersion"]}"'}
        )
        require(status == 201 and isinstance(acknowledgement, dict), "Study exercise publication failed")
        require_private(headers, "Study exercise")
        exercise_id = acknowledgement.get("exerciseId")
        require(isinstance(exercise_id, str) and acknowledgement.get("enabled") is True,
                "invalid Study exercise acknowledgement")
        account["studyExerciseId"] = exercise_id
        persist_account(state_file, account)
    status, headers, exercise = web.request(
        "GET", f"/api/decks/{deck_id}/exercises/{exercise_id}", bearer=access
    )
    require(status == 200 and isinstance(exercise, dict), "persistent Study exercise did not survive restart")
    require_private(headers, "Study exercise")
    require(exercise.get("exerciseId") == exercise_id and exercise.get("type") == "TYPED"
            and exercise.get("enabled") is True, "Study exercise identity changed")
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


def presentation(session, mode, exercise_type="TYPED"):
    values = session.get("presentations")
    require(session.get("status") == "ACTIVE" and isinstance(values, list) and len(values) == 1,
            f"{mode} Study session did not issue one presentation")
    value = values[0]
    require(value.get("type") == exercise_type and isinstance(value.get("presentationId"), str)
            and isinstance(value.get("nonce"), str), f"invalid {mode} presentation")
    return value


def attempt_payload(value, answer="memory"):
    return {
        "attemptId": str(uuid.uuid4()), "presentationId": value["presentationId"], "nonce": value["nonce"],
        "response": {"kind": "TEXT", "text": answer}, "hintsUsed": [],
        "confidence": "KNEW", "durationMs": 1,
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
    fields = {"memberKey", "itemRevisionId", "state", "objectiveCoverage", "lastAssessedAt", "nextDue"}
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


def additional_p0_smoke(web, access, account, state_file):
    evidence_classes = {"SELF_CHECK": "LOW", "CLOZE_SINGLE": "MEDIUM", "SINGLE_CHOICE": "LOW"}
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
        if mechanic == "SELF_CHECK":
            attempt["response"] = {"kind": "SELF_CHECK", "rating": "FULL"}
            attempt["hintsUsed"] = ["REVEAL"]
        elif mechanic == "CLOZE_SINGLE":
            attempt["hintsUsed"] = ["REVEAL_FIRST_GRAPHEME"]
        else:
            options = shown.get("options")
            require(isinstance(options, list) and len(options) == 2, "single choice options missing")
            correct = [option for option in options if option.get("text", "").strip() == "memory"]
            require(len(correct) == 1 and isinstance(correct[0].get("optionId"), str),
                    "single choice correct option is ambiguous")
            attempt["response"] = {"kind": "CHOICE", "optionId": correct[0]["optionId"]}
        status, headers, outcome = submit_attempt(web, access, deck_id, scheduled["sessionId"], attempt)
        require(status == 200 and isinstance(outcome, dict), f"{mechanic} scheduled attempt failed")
        require_private(headers, f"{mechanic} Study attempt")
        evidence = outcome.get("evidence")
        require(outcome.get("status") == "ASSESSED"
                and isinstance(evidence, dict) and evidence.get("result") == "CORRECT"
                and evidence.get("evidenceClass") == evidence_classes[mechanic]
                and isinstance(outcome.get("transition"), dict),
                f"{mechanic} unexpected evidence: status={outcome.get('status')}, "
                f"result={evidence.get('result') if isinstance(evidence, dict) else None}, "
                f"class={evidence.get('evidenceClass') if isinstance(evidence, dict) else None}, "
                f"canonical={outcome.get('canonicalEffects')}")
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
    study["additionalP0"] = additional_p0_smoke(web, access, account, state_file)
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
