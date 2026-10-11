package app.mnema.learning.library;

/**
 * The only place that writes markup for a link preview: a fragment of {@code <head>} (a title, meta and link tags) for the web server to put into
 * {@code index.html} (nginx SSI, {@code docs/operations/link-previews.md}). Every dynamic value is plain text and goes through {@link #escape}, which
 * turns {@code & < > " '} into character references, so a value can neither leave its attribute or element nor open a tag, a comment or an SSI directive
 * ({@code <!--#}); the fragment contains no other markup than the fixed tags below and no script, style or event handler. The set mirrors the defaults
 * of {@code frontend/src/index.html}, so a substituted page is complete: it replaces the whole default block, never adds to it.
 */
final class PreviewHead {
    private static final String SITE = "Mnema";

    private PreviewHead() { }

    static String render(Preview preview) {
        String page = preview.kind() == Preview.Kind.GENERIC ? SITE : preview.title() + " — " + SITE;
        // The author is part of the sentence the messengers show: name, login, then the deck description.
        String shown = preview.author() == null || preview.kind() == Preview.Kind.PROFILE ? preview.description()
                : "Автор: " + preview.author().label() + ". " + preview.description();
        StringBuilder out = new StringBuilder(1024);
        out.append("<title>").append(escape(page)).append("</title>\n");
        meta(out, "name", "description", shown);
        meta(out, "name", "robots", preview.robots());
        if (preview.canonical() != null) out.append("<link rel=\"canonical\" href=\"").append(escape(preview.canonical())).append("\">\n");
        if (preview.author() != null) meta(out, "name", "author", preview.author().label());
        meta(out, "property", "og:site_name", SITE);
        meta(out, "property", "og:title", preview.title());
        meta(out, "property", "og:description", shown);
        meta(out, "property", "og:type", "website");
        if (preview.canonical() != null) meta(out, "property", "og:url", preview.canonical());
        meta(out, "property", "og:image", preview.image());
        meta(out, "property", "og:locale", "ru_RU");
        meta(out, "name", "twitter:card", "summary_large_image");
        meta(out, "name", "twitter:title", preview.title());
        meta(out, "name", "twitter:description", shown);
        meta(out, "name", "twitter:image", preview.image());
        return out.toString();
    }

    private static void meta(StringBuilder out, String attribute, String name, String content) {
        out.append("<meta ").append(attribute).append("=\"").append(name).append("\" content=\"").append(escape(content)).append("\">\n");
    }

    /** Text for an element or a double-quoted attribute: {@code & < > " '} become character references; control characters are replaced by a space (the values were made one line by {@link PreviewText#plain} before). */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(Character.isISOControl(current) ? ' ' : current);
            }
        }
        return out.toString();
    }
}
