package app.mnema.learning.library;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewHeadTest {
    private static final String ORIGIN = "https://mnema.app";
    private static final String HOSTILE = "\"><script>alert(1)</script><!--# include virtual=\"/x\" --> & 'q'";

    private static Preview deck(String title, String description, Preview.Author author) {
        return new Preview(Preview.Kind.DECK, title, description, ORIGIN + "/d/Kq7xT3mNpR/slug", "index,follow", ORIGIN + Preview.IMAGE_PATH, author);
    }

    @Test
    void aHostileValueCannotLeaveItsAttributeOrElementOrOpenATagACommentOrADirective() {
        String html = PreviewHead.render(deck(HOSTILE, HOSTILE, new Preview.Author("anna.k", "<img src=x onerror=alert(1)>")));
        assertThat(html).doesNotContain("<script", "<!--", "<img", "\"><");
        assertThat(html).contains("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;&lt;!--# include virtual=&quot;/x&quot; --&gt; &amp; &#39;q&#39;");
        // the structure is the fixed set of tags: every line is one tag, and every tag is a meta, link or title
        for (String line : html.split("\n")) {
            assertThat(line).matches("<title>[^<>\"]*</title>|<meta (name|property)=\"[a-z:_]+\" content=\"[^<>\"]*\">|<link rel=\"canonical\" href=\"[^<>\"]*\">");
        }
    }

    @Test
    void aPublicDeckCarriesTheCanonicalAddressTheAuthorAndIndexing() {
        String html = PreviewHead.render(deck("Испанские глаголы", "Двести самых частых глаголов.", new Preview.Author("anna.k", "Анна")));
        assertThat(html).contains("<title>Испанские глаголы — Mnema</title>", "<meta name=\"robots\" content=\"index,follow\">",
                "<link rel=\"canonical\" href=\"https://mnema.app/d/Kq7xT3mNpR/slug\">", "<meta property=\"og:url\" content=\"https://mnema.app/d/Kq7xT3mNpR/slug\">",
                "<meta property=\"og:title\" content=\"Испанские глаголы\">", "<meta name=\"author\" content=\"Анна (@anna.k)\">",
                "<meta property=\"og:description\" content=\"Автор: Анна (@anna.k). Двести самых частых глаголов.\">",
                "<meta property=\"og:image\" content=\"https://mnema.app/assets/og-image.png\">", "<meta name=\"twitter:card\" content=\"summary_large_image\">");
    }

    @Test
    void aDeckWithoutAnAuthorCardNamesNoAuthor() {
        String html = PreviewHead.render(deck("Глаголы", "Описание.", null));
        assertThat(html).doesNotContain("name=\"author\"", "Автор:").contains("<meta property=\"og:description\" content=\"Описание.\">");
        assertThat(PreviewHead.render(deck("Глаголы", "Описание.", new Preview.Author("anna.k", null)))).contains("Автор: @anna.k. Описание.");
    }

    @Test
    void theGenericCardIsNoindexWithoutACanonicalOrAUrl() {
        String html = PreviewHead.render(Preview.generic(ORIGIN));
        assertThat(html).contains("<title>Mnema</title>", "<meta name=\"robots\" content=\"noindex\">", "content=\"Mnema — учиться, понимать, помнить\"")
                .doesNotContain("canonical", "og:url", "name=\"author\"", "index,follow");
    }

    @Test
    void theFragmentMirrorsTheTagSetOfTheDefaultBlockOfIndexHtml() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("frontend/src/index.html"))) root = root.getParent();
        String page = java.nio.file.Files.readString(root.resolve("frontend/src/index.html"));
        String block = page.substring(page.indexOf("<!--# block name=\"mnema_head\" -->"), page.indexOf("<!--# endblock -->"));
        Matcher tag = Pattern.compile("<meta (name|property)=\"([a-z:_]+)\"").matcher(block);
        java.util.Set<String> defaults = new java.util.TreeSet<>();
        while (tag.find()) defaults.add(tag.group(2));
        defaults.add("canonical");
        java.util.Set<String> rendered = new java.util.TreeSet<>();
        Matcher produced = Pattern.compile("<meta (name|property)=\"([a-z:_]+)\"").matcher(PreviewHead.render(deck("t", "d", new Preview.Author("anna.k", null))));
        while (produced.find()) rendered.add(produced.group(2));
        rendered.add("canonical");
        // everything the default block declares is replaced; "author" is the only addition
        rendered.remove("author");
        assertThat(rendered).isEqualTo(defaults);
    }
}
