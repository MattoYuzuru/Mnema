package app.mnema.learning.generation.mbm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MbmAutoFixerTest {

    @Test
    void cleanSourceIsReturnedUnchanged() {
        MbmAutoFixer.Fixed fixed = MbmAutoFixer.fix("# T\n\ntext\n\n\n- a\n");
        assertThat(fixed.text()).isEqualTo("# T\n\ntext\n\n\n- a\n");
        assertThat(fixed.applied()).isEmpty();
    }

    @Test
    void harmlessSlipsAreRepairedAndReported() {
        MbmAutoFixer.Fixed fixed = MbmAutoFixer.fix("\r\n\r\n##### Deep   \r\ntext  \r\n\r\n\r\n\r\n\r\nmore\r\n\r\n\r\n");
        assertThat(fixed.text()).isEqualTo("### Deep\ntext\n\n\nmore\n");
        assertThat(fixed.applied()).containsExactlyInAnyOrder(MbmAutoFixer.Fix.LINE_ENDINGS, MbmAutoFixer.Fix.TRAILING_SPACES,
                MbmAutoFixer.Fix.HEADING_LEVEL, MbmAutoFixer.Fix.BLANK_LINES, MbmAutoFixer.Fix.EDGE_BLANK_LINES);
    }

    @Test
    void fencedRegionsAreNeverTouched() {
        String source = "::mermaid{title=\"t\" description=\"d\"}\n```mermaid\n#### keep   \n\n\n\n\nA --> B  \n```\nafter   \n";
        MbmAutoFixer.Fixed fixed = MbmAutoFixer.fix(source);
        assertThat(fixed.text()).isEqualTo(source.replace("after   ", "after"));
        assertThat(fixed.applied()).containsExactly(MbmAutoFixer.Fix.TRAILING_SPACES);
    }

    @Test
    void aLongerFenceOnlyClosesWithAtLeastAsManyBackticks() {
        String source = "````mermaid\n```\n#### keep   \n````\n#### fix\n";
        assertThat(MbmAutoFixer.fix(source).text()).isEqualTo("````mermaid\n```\n#### keep   \n````\n### fix\n");
    }

    @Test
    void codeFencesKeepTheirContentAndOnlyTheLanguageCaseIsFixed() {
        String source = "```SQL\n#### keep   \n\n\n\n\n\tx  \n```\n[[b2]] ````C++\n```\n  y  \n````\n#### fix  \n";
        MbmAutoFixer.Fixed fixed = MbmAutoFixer.fix(source);
        assertThat(fixed.text()).isEqualTo("```sql\n#### keep   \n\n\n\n\n\tx  \n```\n[[b2]] ````c++\n```\n  y  \n````\n### fix\n");
        assertThat(fixed.applied()).containsExactlyInAnyOrder(MbmAutoFixer.Fix.CODE_LANGUAGE_CASE, MbmAutoFixer.Fix.HEADING_LEVEL,
                MbmAutoFixer.Fix.TRAILING_SPACES);
        assertThat(MbmAutoFixer.fix("```sql\nx\n```\n").applied()).isEmpty();
        // not an identifier: left for the compiler to report
        assertThat(MbmAutoFixer.fix("```SQL Server\nx\n```\n").text()).isEqualTo("```SQL Server\nx\n```\n");
    }

    @Test
    void emptyAndBlankInputBecomeEmpty() {
        assertThat(MbmAutoFixer.fix("").text()).isEmpty();
        assertThat(MbmAutoFixer.fix(" \n\n").text()).isEmpty();
    }

    @Test
    void repairedHeadingsCompile() {
        String fixed = MbmAutoFixer.fix("#### Four\n\n\n\n\ntext").text();
        assertThat(new MbmCompiler().compile(fixed, MbmOptions.create(), new RandomIdAllocator())).isInstanceOf(MbmResult.Success.class);
    }

    @Test
    void repairListShowsLineAndRuleOnceWithoutContent() {
        var findings = java.util.List.of(
                new MbmFinding(3, 1, MbmCode.MBM_HEADING_LEVEL, null),
                new MbmFinding(3, 1, MbmCode.MBM_HEADING_LEVEL, null),
                new MbmFinding(5, 1, MbmCode.MBM_MISSING_ATTRIBUTE, "alt"),
                new MbmFinding(0, null, MbmCode.MBM_LINK_REJECTED_BY_PROFILE, null));
        assertThat(MbmRepairList.format(findings)).isEqualTo(
                "L3 -> MBM_HEADING_LEVEL: " + MbmCode.MBM_HEADING_LEVEL.rule() + "\n"
                        + "L5 -> MBM_MISSING_ATTRIBUTE (alt): " + MbmCode.MBM_MISSING_ATTRIBUTE.rule() + "\n"
                        + "options -> MBM_LINK_REJECTED_BY_PROFILE: " + MbmCode.MBM_LINK_REJECTED_BY_PROFILE.rule());
        assertThat(MbmRepairList.format(java.util.List.of())).isEmpty();
    }
}
