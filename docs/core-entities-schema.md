---
config:
  layout: dagre
---
erDiagram

    PUBLIC_DECKS {
        uuid deck_id
        int version
        uuid author_id
        text name
        text description
        uuid template_id
        boolean is_public
        boolean is_listed
        language_tag language_code
        text[] tags
        timestamptz created_at
        timestamptz updated_at
        timestamptz published_at
        uuid forked_from_deck
    }

    PUBLIC_CARDS {
        uuid deck_id
        int deck_version
        uuid card_id
        jsonb content
        int order_index
        text[] tags
        timestamptz created_at
        timestamptz updated_at
        boolean is_active
        text checksum
    }

    USER_DECKS {
        uuid user_deck_id
        uuid user_id
        uuid public_deck_id
        int subscribed_version
        int current_version
        boolean auto_update
        text algorithm_id
        jsonb algorithm_params
        text display_name
        text display_description
        timestamptz created_at
        timestamptz last_synced_at
        boolean is_archived
    }

    USER_CARDS {
        uuid user_card_id
        uuid user_id
        uuid subscription_id
        uuid public_card_id
        boolean is_custom
        boolean is_deleted
        text personal_note
        jsonb content_override
        timestamptz created_at
        timestamptz updated_at
        timestamptz last_review_at
        timestamptz next_review_at
        int review_count
        boolean is_suspended
    }

    CARD_TEMPLATES {
        uuid template_id
        uuid owner_id
        text name
        text description
        boolean is_public
        timestamptz created_at
        timestamptz updated_at
        jsonb layout
        jsonb ai_profile
        text icon_url
    }

    FIELD_TEMPLATES {
        uuid field_id
        uuid template_id
        text name
        text label
        card_field_type field_type
        boolean is_required
        boolean is_on_front
        int order_index
        text default_value
        text help_text
    }

    SR_ALGORITHMS {
        text algorithm_id
        text name
        text description
        text version
        jsonb config_schema
        jsonb default_config
        timestamptz created_at
    }

    SR_CARD_STATES {
        uuid user_card_id
        text algorithm_id
        jsonb state
        timestamptz last_review_at
        timestamptz next_review_at
        int review_count
    }

    SR_REVIEW_LOGS {
        bigserial id
        uuid user_card_id
        text algorithm_id
        timestamptz reviewed_at
        smallint rating
        int response_ms
        jsonb state_before
        jsonb state_after
        review_source source
    }
    CARD_TEMPLATES ||--o{ FIELD_TEMPLATES : "has fields"
    CARD_TEMPLATES ||--o{ PUBLIC_DECKS : "template for decks"
    PUBLIC_DECKS ||--o{ PUBLIC_CARDS : "has cards"
    PUBLIC_DECKS ||--o{ USER_DECKS : "subscribed as (by version)"
    USER_DECKS ||--o{ USER_CARDS : "contains cards"
    PUBLIC_CARDS ||--o{ USER_CARDS : "source for user card"
    SR_ALGORITHMS ||--o{ USER_DECKS : "selected algorithm"
    SR_ALGORITHMS ||--o{ SR_CARD_STATES : "used in state"
    SR_ALGORITHMS ||--o{ SR_REVIEW_LOGS : "logged with"
    USER_CARDS ||--|| SR_CARD_STATES : "has SRS state"
    USER_CARDS ||--o{ SR_REVIEW_LOGS : "has reviews"